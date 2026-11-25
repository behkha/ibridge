import AVFoundation
import os

/// A relayed call's sound on the iPhone (PROTOCOL.md 8). Microphone (voice processing: echo cancellation) → 16 kHz mono
/// → one Opus packet per 20 ms out; Opus packets in → jitter buffer → speaker. Runs only while CallKit's audio session
/// is active: `start()` from `provider(_:didActivate:)`, `stop()` when it ends. `start`/`stop`/`receive` from the main thread.
final class CallAudio: @unchecked Sendable {
    /// One encoded 20 ms frame, called on the capture queue.
    var onFrame: ((Data) -> Void)?

    /// Muted: silence frames keep going so `seq` keeps running.
    var muted: Bool {
        get { shared.withLock { $0.muted } }
        set { shared.withLock { $0.muted = newValue } }
    }

    static let rate = 16_000.0
    static let frameLength = 320 // 20 ms at 16 kHz

    private struct Shared { var muted = false; var running = false; var micFrames = 0 }

    private let log = Logger(subsystem: "app.ibridge", category: "audio")
    private let engine = AVAudioEngine()
    private let pcm = AVAudioFormat(standardFormatWithSampleRate: CallAudio.rate, channels: 1)!
    private let opus: AVAudioFormat
    private let shared = OSAllocatedUnfairLock(initialState: Shared())
    private let jitter = OSAllocatedUnfairLock(initialState: JitterBuffer())
    private let tx = DispatchQueue(label: "app.ibridge.audio.tx", qos: .userInteractive)
    private let rx = DispatchQueue(label: "app.ibridge.audio.rx", qos: .userInteractive)
    private var sink: AVAudioSinkNode?
    private var source: AVAudioSourceNode?
    private var timer: DispatchSourceTimer?
    private var configObserver: NSObjectProtocol?
    // Capture queue only.
    private var micFormat: AVAudioFormat?
    private var resampler: AVAudioConverter?
    private var encoder: AVAudioConverter?
    private var fifo: [Float] = []
    // Receive queue only.
    private var decoder: AVAudioConverter?

    init() {
        // Opus packets of 20 ms at 16 kHz, mono (Apple's own codec through AVAudioConverter).
        var d = AudioStreamBasicDescription(mSampleRate: Self.rate, mFormatID: kAudioFormatOpus, mFormatFlags: 0, mBytesPerPacket: 0,
                                            mFramesPerPacket: UInt32(Self.frameLength), mBytesPerFrame: 0, mChannelsPerFrame: 1,
                                            mBitsPerChannel: 0, mReserved: 0)
        opus = AVAudioFormat(streamDescription: &d)!
        configObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            // A route change (AirPods, speaker, car) rebuilt the hardware graph: start it again on the new one.
            guard let self, self.shared.withLock({ $0.running }) else { return }
            self.log.notice("audio route changed: restarting")
            self.stop()
            self.start()
        }
    }

    // MARK: control

    func start() {
        guard !shared.withLock({ $0.running }) else { return }
        do {
            let input = engine.inputNode
            try input.setVoiceProcessingEnabled(true)
            let inFormat = input.outputFormat(forBus: 0)
            guard inFormat.sampleRate > 0, inFormat.channelCount > 0, inFormat.commonFormat == .pcmFormatFloat32,
                  let mono = AVAudioFormat(standardFormatWithSampleRate: inFormat.sampleRate, channels: 1),
                  let resampler = AVAudioConverter(from: mono, to: pcm),
                  let encoder = AVAudioConverter(from: pcm, to: opus),
                  let decoder = AVAudioConverter(from: opus, to: pcm) else {
                log.error("no usable microphone format or Opus codec: \(inFormat.description, privacy: .public)")
                teardown()
                return
            }
            encoder.bitRate = 24_000
            encoder.bitRateStrategy = AVAudioBitRateStrategy_Variable
            tx.sync {
                self.micFormat = mono
                self.resampler = resampler
                self.encoder = encoder
                self.fifo = []
            }
            rx.sync { self.decoder = decoder }
            jitter.withLock { $0 = JitterBuffer() }

            let channels = Int(inFormat.channelCount), interleaved = inFormat.isInterleaved
            let sink = AVAudioSinkNode { [weak self] _, frames, list in
                self?.captured(list, frames: Int(frames), channels: channels, interleaved: interleaved)
                return noErr
            }
            let source = AVAudioSourceNode(format: pcm) { [weak self] _, _, frames, list in
                // Mono, deinterleaved: one buffer.
                guard let out = list.pointee.mBuffers.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
                if let self { self.jitter.withLockUnchecked { $0.render(out, Int(frames)) } } else { out.update(repeating: 0, count: Int(frames)) }
                return noErr
            }
            engine.attach(sink)
            engine.attach(source)
            engine.connect(input, to: sink, format: inFormat)
            engine.connect(source, to: engine.mainMixerNode, format: pcm)
            self.sink = sink
            self.source = source
            engine.prepare()
            try engine.start()
            shared.withLock { $0.running = true; $0.micFrames = 0 }
            startLog()
            log.notice("audio started: mic \(Int(inFormat.sampleRate), privacy: .public) Hz × \(channels, privacy: .public), Opus 16 kHz 20 ms")
        } catch {
            log.error("audio start failed: \(String(describing: error), privacy: .public)")
            teardown()
        }
    }

    func stop() {
        guard shared.withLock({ $0.running }) else { return }
        shared.withLock { $0.running = false }
        teardown()
        log.notice("audio stopped")
    }

    /// A new call stream: its `seq` numbers start over.
    func newStream() {
        jitter.withLock { $0 = JitterBuffer() }
    }

    /// One received Opus packet with its `seq`.
    func receive(seq: UInt16, opus packet: Data) {
        rx.async { [self] in
            guard shared.withLock({ $0.running }), let decoder, !packet.isEmpty else { return }
            let input = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: packet.count)
            packet.copyBytes(to: input.data.assumingMemoryBound(to: UInt8.self), count: packet.count)
            input.byteLength = UInt32(packet.count)
            input.packetCount = 1
            input.packetDescriptions?[0] = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
            guard let out = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Self.frameLength * 6)) else { return }
            var given = false
            var error: NSError?
            let status = decoder.convert(to: out, error: &error) { _, s in
                if given { s.pointee = .noDataNow; return nil }
                given = true
                s.pointee = .haveData
                return input
            }
            if status == .error { log.error("Opus decode failed: \(String(describing: error), privacy: .public)") }
            let n = Int(out.frameLength)
            let samples = n > 0 ? Array(UnsafeBufferPointer(start: out.floatChannelData![0], count: n)) : []
            jitter.withLock { $0.push(seq: seq, pcm: samples) }
        }
    }

    // MARK: capture

    /// Real-time thread: copy channel 0 out and leave the work to the capture queue.
    private func captured(_ list: UnsafePointer<AudioBufferList>, frames: Int, channels: Int, interleaved: Bool) {
        // The first buffer: channel 0 when deinterleaved, every channel when interleaved.
        guard frames > 0, let p = list.pointee.mBuffers.mData?.assumingMemoryBound(to: Float.self) else { return }
        var samples = [Float](repeating: 0, count: frames)
        if interleaved && channels > 1 {
            for i in 0..<frames { samples[i] = p[i * channels] }
        } else {
            samples.withUnsafeMutableBufferPointer { $0.baseAddress!.update(from: p, count: frames) }
        }
        tx.async { [weak self] in self?.encode(samples) }
    }

    /// Capture queue: resample to 16 kHz, cut 20 ms frames, encode each.
    private func encode(_ samples: [Float]) {
        guard let micFormat, let resampler, let encoder,
              let input = AVAudioPCMBuffer(pcmFormat: micFormat, frameCapacity: AVAudioFrameCount(samples.count)) else { return }
        input.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { input.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        let capacity = AVAudioFrameCount(Double(samples.count) * Self.rate / micFormat.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        _ = resampler.convert(to: out, error: &error) { _, s in
            if given { s.pointee = .noDataNow; return nil }
            given = true
            s.pointee = .haveData
            return input
        }
        fifo.append(contentsOf: UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))
        let muted = self.muted
        while fifo.count >= Self.frameLength {
            guard let frame = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: AVAudioFrameCount(Self.frameLength)) else { return }
            frame.frameLength = AVAudioFrameCount(Self.frameLength)
            let dst = frame.floatChannelData![0]
            if muted { dst.update(repeating: 0, count: Self.frameLength) } else {
                fifo.withUnsafeBufferPointer { dst.update(from: $0.baseAddress!, count: Self.frameLength) }
            }
            fifo.removeFirst(Self.frameLength)
            let packet = AVAudioCompressedBuffer(format: opus, packetCapacity: 1, maximumPacketSize: max(encoder.maximumOutputPacketSize, 1500))
            var fed = false
            let status = encoder.convert(to: packet, error: &error) { _, s in
                if fed { s.pointee = .noDataNow; return nil }
                fed = true
                s.pointee = .haveData
                return frame
            }
            guard status != .error, packet.packetCount > 0, packet.byteLength > 0 else { continue }
            shared.withLock { $0.micFrames += 1 }
            onFrame?(Data(bytes: packet.data, count: Int(packet.byteLength)))
        }
    }

    // MARK: helpers

    private func teardown() {
        timer?.cancel()
        timer = nil
        engine.stop()
        if let sink { engine.detach(sink) }
        if let source { engine.detach(source) }
        sink = nil
        source = nil
        try? engine.inputNode.setVoiceProcessingEnabled(false)
        tx.async { [weak self] in self?.fifo = []; self?.resampler = nil; self?.encoder = nil }
        rx.async { [weak self] in self?.decoder = nil }
    }

    /// Once a second while a call runs: frames in/out of the jitter buffer, its depth, concealed and late frames.
    private func startLog() {
        let t = DispatchSource.makeTimerSource(queue: rx)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let (s, depth) = self.jitter.withLock { j -> (JitterBuffer.Stats, Int) in
                defer { j.stats = .init() }
                return (j.stats, j.depth)
            }
            let mic = self.shared.withLock { s -> Int in defer { s.micFrames = 0 }; return s.micFrames }
            self.log.notice("audio: in \(s.framesIn, privacy: .public) out \(s.framesOut, privacy: .public) depth \(depth, privacy: .public) concealed \(s.concealed, privacy: .public) late \(s.late, privacy: .public) dropped \(s.dropped, privacy: .public) added \(s.added, privacy: .public) mic \(mic, privacy: .public)")
        }
        t.resume()
        timer = t
    }
}

/// Playout of received frames (PROTOCOL.md 8, "Timing"): starts once 60 ms are buffered, conceals a missing frame
/// with a short fade, and when the two clocks drift drops or adds one frame at a quiet moment. Not thread-safe.
struct JitterBuffer {
    static let target = 3 // frames: 60 ms
    struct Stats { var framesIn = 0, framesOut = 0, concealed = 0, late = 0, dropped = 0, added = 0 }
    var stats = Stats()
    var depth: Int { frames.count }

    private struct Frame { var seq: UInt16; var pcm: [Float]; var quiet: Bool }
    private static let silence = [Float](repeating: 0, count: CallAudio.frameLength)
    private var frames: [Frame] = []
    private var next: UInt16 = 0
    private var playing = false
    private var current: [Float] = JitterBuffer.silence
    private var pos = 0
    private var fade = false
    private var last: [Float] = []
    private var lastQuiet = true
    private var missRun = 0
    private var lateRun = 0
    private var depthSum = 0
    private var depthCount = 0
    /// +1: drop one frame at a quiet moment; -1: add one; 0: on target.
    private var trim = 0
    private var trimWait = 0

    mutating func push(seq: UInt16, pcm: [Float]) {
        stats.framesIn += 1
        let pcm = pcm.isEmpty ? Self.silence : pcm
        let behind = (playing && Int16(bitPattern: seq &- next) < 0) || frames.last.map { Int16(bitPattern: seq &- $0.seq) <= 0 } == true
        if behind {
            stats.late += 1 // its turn has passed
            lateRun += 1
            guard lateRun > 10 else { return }
            // The sender started its numbers over: follow it.
            frames.removeAll()
            playing = false
        }
        lateRun = 0
        frames.append(Frame(seq: seq, pcm: pcm, quiet: Self.isQuiet(pcm)))
        if frames.count > 25 { // half a second after a stall: keep the newest
            frames.removeFirst()
            stats.dropped += 1
            if playing { next = frames[0].seq }
        }
    }

    /// Real-time thread: fills `count` samples.
    mutating func render(_ out: UnsafeMutablePointer<Float>, _ count: Int) {
        var i = 0
        while i < count {
            if pos >= current.count {
                pos = 0
                fade = false
                current = nextFrame()
                if current.isEmpty { current = Self.silence }
            }
            let n = min(count - i, current.count - pos)
            if fade {
                let len = Float(current.count)
                for k in 0..<n { out[i + k] = current[pos + k] * (1 - Float(pos + k) / len) }
            } else {
                current.withUnsafeBufferPointer { (out + i).update(from: $0.baseAddress! + pos, count: n) }
            }
            i += n
            pos += n
        }
    }

    private mutating func nextFrame() -> [Float] {
        if !playing {
            guard frames.count >= Self.target else { return Self.silence }
            playing = true
            next = frames[0].seq
            missRun = 0
        }
        measureDrift()
        if trim < 0, lastQuiet || trimWait > 50 { // behind: one frame of silence
            trim = 0
            stats.added += 1
            return Self.silence
        }
        while true {
            guard let head = frames.first else { return conceal(skip: false) } // late: wait for it
            let gap = Int(Int16(bitPattern: head.seq &- next))
            if gap > 0 {
                if gap <= Self.target { return conceal(skip: true) } // the sender dropped a few frames
                next = head.seq // a long stall: go on with the newest sound
            }
            frames.removeFirst()
            next = head.seq &+ 1
            if trim > 0, frames.count >= Self.target - 1, head.quiet || trimWait > 50 { // ahead: skip one frame
                trim = 0
                stats.dropped += 1
                continue
            }
            stats.framesOut += 1
            missRun = 0
            last = head.pcm
            lastQuiet = head.quiet
            return head.pcm
        }
    }

    /// The first missing frame fades the last one out; more are silence. 200 ms of nothing buffers up again.
    private mutating func conceal(skip: Bool) -> [Float] {
        if skip { next &+= 1 }
        stats.concealed += 1
        missRun += 1
        if missRun > 10 { playing = false }
        if missRun == 1, !last.isEmpty { fade = true; return last }
        return Self.silence
    }

    /// Every 2 s: an average depth well off 60 ms means the clocks drift; correct it by one frame.
    private mutating func measureDrift() {
        depthSum += frames.count
        depthCount += 1
        if trim != 0 { trimWait += 1 }
        guard depthCount >= 100 else { return }
        let avg = Double(depthSum) / Double(depthCount)
        if trim == 0 {
            if avg > Double(Self.target) + 1.5 { trim = 1; trimWait = 0 }
            else if avg < Double(Self.target) - 1.5 { trim = -1; trimWait = 0 }
        }
        depthSum = 0
        depthCount = 0
    }

    /// Under -40 dBFS.
    private static func isQuiet(_ pcm: [Float]) -> Bool {
        var sum: Float = 0
        for x in pcm { sum += x * x }
        return sum / Float(max(pcm.count, 1)) < 1e-4
    }
}

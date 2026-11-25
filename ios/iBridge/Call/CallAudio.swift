import AVFoundation
import os

/// A relayed call's sound on the iPhone (PROTOCOL.md 8). Microphone (voice processing: echo cancellation) → 16 kHz mono
/// → one Opus packet per 20 ms out; Opus packets in → jitter buffer → speaker. Runs only while CallKit's audio session
/// is active: `start()` from `provider(_:didActivate:)`, `stop()` when it ends. `start`/`stop`/`receive` from the main thread.
///
/// The sink and render callbacks run on the real-time audio thread: they never allocate, free, dispatch or wait on a lock
/// held for more than a fixed-size copy. They only touch `MicRing` and `JitterBuffer`, whose storage is allocated up front.
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
    private let jitter = JitterBuffer()
    private let tx = DispatchQueue(label: "app.ibridge.audio.tx", qos: .userInteractive)
    private let rx = DispatchQueue(label: "app.ibridge.audio.rx", qos: .userInteractive)
    private var sink: AVAudioSinkNode?
    private var source: AVAudioSourceNode?
    private var timer: DispatchSourceTimer?
    private var pump: DispatchSourceTimer?
    private var configObserver: NSObjectProtocol?
    // Capture queue only.
    private var mic: MicRing?
    private var micBuffer: AVAudioPCMBuffer?
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
            let ring = MicRing(capacity: Int(inFormat.sampleRate / 2)) // 500 ms
            guard let micBuffer = AVAudioPCMBuffer(pcmFormat: mono, frameCapacity: AVAudioFrameCount(ring.capacity)) else {
                teardown()
                return
            }
            tx.sync {
                self.mic = ring
                self.micBuffer = micBuffer
                self.resampler = resampler
                self.encoder = encoder
                self.fifo = []
            }
            rx.sync { self.decoder = decoder }
            jitter.reset()

            // Real-time thread, both: no `self`, only the preallocated ring and jitter buffer.
            let channels = Int(inFormat.channelCount), interleaved = inFormat.isInterleaved
            let sink = AVAudioSinkNode { _, frames, list in
                ring.write(list, frames: Int(frames), channels: channels, interleaved: interleaved)
                return noErr
            }
            let jitter = self.jitter
            let source = AVAudioSourceNode(format: pcm) { _, _, frames, list in
                // Mono, deinterleaved: one buffer.
                guard let out = list.pointee.mBuffers.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
                jitter.render(out, min(Int(frames), Int(list.pointee.mBuffers.mDataByteSize) / MemoryLayout<Float>.size))
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
            startPump()
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
        jitter.reset()
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
            jitter.push(seq: seq, samples: out.floatChannelData![0], count: Int(out.frameLength))
        }
    }

    // MARK: capture

    /// Every 10 ms on the capture queue: takes what the microphone wrote to the ring.
    private func startPump() {
        let t = DispatchSource.makeTimerSource(queue: tx)
        t.schedule(deadline: .now() + .milliseconds(10), repeating: .milliseconds(10), leeway: .milliseconds(2))
        t.setEventHandler { [weak self] in self?.encode() }
        t.resume()
        pump = t
    }

    /// Capture queue: resample to 16 kHz, cut 20 ms frames, encode each.
    private func encode() {
        guard let mic, let micBuffer, let resampler, let encoder else { return }
        let (count, dropped) = mic.read(into: micBuffer.floatChannelData![0], limit: Int(micBuffer.frameCapacity))
        if dropped > 0 { log.error("microphone ring full: \(dropped, privacy: .public) samples dropped") }
        guard count > 0 else { return }
        micBuffer.frameLength = AVAudioFrameCount(count)
        let capacity = AVAudioFrameCount(Double(count) * Self.rate / micBuffer.format.sampleRate) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm, frameCapacity: capacity) else { return }
        var given = false
        var error: NSError?
        _ = resampler.convert(to: out, error: &error) { _, s in
            if given { s.pointee = .noDataNow; return nil }
            given = true
            s.pointee = .haveData
            return micBuffer
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
        pump?.cancel()
        pump = nil
        engine.stop()
        if let sink { engine.detach(sink) }
        if let source { engine.detach(source) }
        sink = nil
        source = nil
        try? engine.inputNode.setVoiceProcessingEnabled(false)
        tx.async { [weak self] in
            self?.fifo = []; self?.resampler = nil; self?.encoder = nil; self?.mic = nil; self?.micBuffer = nil
        }
        rx.async { [weak self] in self?.decoder = nil }
    }

    /// Once a second while a call runs: frames in/out of the jitter buffer, its depth, concealed and late frames.
    private func startLog() {
        let t = DispatchSource.makeTimerSource(queue: rx)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let (s, depth) = self.jitter.takeStats()
            let mic = self.shared.withLock { s -> Int in defer { s.micFrames = 0 }; return s.micFrames }
            self.log.notice("audio: in \(s.framesIn, privacy: .public) out \(s.framesOut, privacy: .public) depth \(depth, privacy: .public) concealed \(s.concealed, privacy: .public) late \(s.late, privacy: .public) dropped \(s.dropped, privacy: .public) added \(s.added, privacy: .public) mic \(mic, privacy: .public)")
        }
        t.resume()
        timer = t
    }
}

/// Microphone samples from the real-time sink (the one writer) to the capture queue (the one reader), in storage
/// allocated once. The lock covers only the ring's counters: each side reads them, copies outside the lock into or out of
/// the part only it may touch (the writer: the free part; the reader: the filled part), then publishes the new counters.
/// Taking and releasing the lock orders the copies against the counters, so the other side never sees a half-written sample.
final class MicRing: @unchecked Sendable {
    let capacity: Int
    private let buf: UnsafeMutablePointer<Float>
    private struct Counters { var head = 0; var count = 0; var dropped = 0 }
    private let counters = OSAllocatedUnfairLock(initialState: Counters())

    init(capacity: Int) {
        self.capacity = max(capacity, 4096)
        buf = .allocate(capacity: self.capacity)
        buf.initialize(repeating: 0, count: self.capacity)
    }

    deinit { buf.deallocate() }

    /// Real-time thread: channel 0 of the first buffer (every channel when interleaved). A full ring drops the newest.
    func write(_ list: UnsafePointer<AudioBufferList>, frames: Int, channels: Int, interleaved: Bool) {
        guard frames > 0, let p = list.pointee.mBuffers.mData?.assumingMemoryBound(to: Float.self) else { return }
        let step = interleaved && channels > 1 ? channels : 1
        let (head, count) = counters.withLock { ($0.head, $0.count) }
        let n = min(frames, capacity - count)
        var w = (head + count) % capacity
        for i in 0..<n {
            buf[w] = p[i * step]
            w += 1
            if w == capacity { w = 0 }
        }
        counters.withLock { $0.count += n; $0.dropped += frames - n }
    }

    /// Capture queue: up to `limit` samples, oldest first, and how many a full ring dropped since the last read.
    func read(into dst: UnsafeMutablePointer<Float>, limit: Int) -> (count: Int, dropped: Int) {
        let (head, count) = counters.withLock { ($0.head, $0.count) }
        let n = min(limit, count)
        let first = min(n, capacity - head)
        dst.update(from: buf + head, count: first)
        if n > first { (dst + first).update(from: buf, count: n - first) }
        return counters.withLock { c in
            c.head = (c.head + n) % capacity
            c.count -= n
            defer { c.dropped = 0 }
            return (n, c.dropped)
        }
    }
}

/// Playout of received frames (PROTOCOL.md 8, "Timing"): starts once 60 ms are buffered, conceals a missing frame
/// with a short fade, and when the two clocks drift drops or adds one frame at a quiet moment.
/// `push`, `reset` and `takeStats` from the receive queue or main, `render` from the real-time thread. Frames live in
/// slots allocated once; the lock is held for at most one call's worth of samples and fixed-size frame copies, never
/// around an allocation, a free or a decode.
final class JitterBuffer: @unchecked Sendable {
    static let target = 3 // frames: 60 ms
    static let capacity = 25 // frames: half a second after a stall, keep the newest
    struct Stats { var framesIn = 0, framesOut = 0, concealed = 0, late = 0, dropped = 0, added = 0 }

    private let state = OSAllocatedUnfairLock(uncheckedState: State())

    deinit { state.withLockUnchecked { $0.free() } }

    func reset() { state.withLockUnchecked { $0.reset() } }

    /// One decoded frame. Short ones are padded with silence, an empty one is a silent frame.
    func push(seq: UInt16, samples: UnsafePointer<Float>, count: Int) {
        let quiet = Self.isQuiet(samples, count)
        state.withLockUnchecked { $0.push(seq: seq, samples: samples, count: count, quiet: quiet) }
    }

    /// Real-time thread: fills `count` samples.
    func render(_ out: UnsafeMutablePointer<Float>, _ count: Int) {
        state.withLockUnchecked { $0.render(out, count) }
    }

    /// The counters since the last call, and the depth in frames.
    func takeStats() -> (Stats, Int) {
        state.withLockUnchecked { s in
            defer { s.stats = .init() }
            return (s.stats, s.count)
        }
    }

    /// Under -40 dBFS.
    private static func isQuiet(_ p: UnsafePointer<Float>, _ n: Int) -> Bool {
        guard n > 0 else { return true }
        var sum: Float = 0
        for i in 0..<n { sum += p[i] * p[i] }
        return sum / Float(n) < 1e-4
    }

    private struct State {
        static let len = CallAudio.frameLength
        static let slots = JitterBuffer.capacity + 1 // a push lands before the oldest is dropped
        let pcm = UnsafeMutablePointer<Float>.allocate(capacity: slots * len)
        let seqs = UnsafeMutablePointer<UInt16>.allocate(capacity: slots)
        let quiet = UnsafeMutablePointer<Bool>.allocate(capacity: slots)
        let cur = UnsafeMutablePointer<Float>.allocate(capacity: len)  // the frame playing
        let last = UnsafeMutablePointer<Float>.allocate(capacity: len) // the last frame played, for concealment
        var head = 0
        var count = 0
        var stats = Stats()
        var next: UInt16 = 0
        var playing = false
        var pos = 0
        var fade = false
        var hasLast = false
        var lastQuiet = true
        var missRun = 0
        var lateRun = 0
        var depthSum = 0
        var depthCount = 0
        /// +1: drop one frame at a quiet moment; -1: add one; 0: on target.
        var trim = 0
        var trimWait = 0

        init() {
            pcm.initialize(repeating: 0, count: Self.slots * Self.len)
            seqs.initialize(repeating: 0, count: Self.slots)
            quiet.initialize(repeating: true, count: Self.slots)
            cur.initialize(repeating: 0, count: Self.len)
            last.initialize(repeating: 0, count: Self.len)
        }

        func free() {
            pcm.deallocate(); seqs.deallocate(); quiet.deallocate(); cur.deallocate(); last.deallocate()
        }

        mutating func reset() {
            head = 0; count = 0; stats = Stats(); next = 0; playing = false
            cur.update(repeating: 0, count: Self.len); pos = 0; fade = false
            hasLast = false; lastQuiet = true; missRun = 0; lateRun = 0
            depthSum = 0; depthCount = 0; trim = 0; trimWait = 0
        }

        mutating func push(seq: UInt16, samples: UnsafePointer<Float>, count n: Int, quiet q: Bool) {
            stats.framesIn += 1
            let tail = count > 0 ? seqs[(head + count - 1) % Self.slots] : nil
            let behind = (playing && Int16(bitPattern: seq &- next) < 0) || tail.map { Int16(bitPattern: seq &- $0) <= 0 } == true
            if behind {
                stats.late += 1 // its turn has passed
                lateRun += 1
                guard lateRun > 10 else { return }
                // The sender started its numbers over: follow it.
                count = 0
                playing = false
            }
            lateRun = 0
            let slot = (head + count) % Self.slots
            let dst = pcm + slot * Self.len
            let m = min(max(n, 0), Self.len)
            if m > 0 { dst.update(from: samples, count: m) }
            if m < Self.len { (dst + m).update(repeating: 0, count: Self.len - m) }
            seqs[slot] = seq
            quiet[slot] = q
            count += 1
            if count > JitterBuffer.capacity {
                head = (head + 1) % Self.slots
                count -= 1
                stats.dropped += 1
                if playing { next = seqs[head] }
            }
        }

        mutating func render(_ out: UnsafeMutablePointer<Float>, _ n: Int) {
            var i = 0
            while i < n {
                if pos >= Self.len {
                    pos = 0
                    fade = false
                    nextFrame()
                }
                let k = min(n - i, Self.len - pos)
                if fade {
                    let len = Float(Self.len)
                    for j in 0..<k { out[i + j] = cur[pos + j] * (1 - Float(pos + j) / len) }
                } else {
                    (out + i).update(from: cur + pos, count: k)
                }
                i += k
                pos += k
            }
        }

        /// Puts the next frame to play in `cur`.
        private mutating func nextFrame() {
            if !playing {
                guard count >= JitterBuffer.target else { return silence() }
                playing = true
                next = seqs[head]
                missRun = 0
            }
            measureDrift()
            if trim < 0, lastQuiet || trimWait > 50 { // behind: one frame of silence
                trim = 0
                stats.added += 1
                return silence()
            }
            while true {
                guard count > 0 else { return conceal(skip: false) } // late: wait for it
                let seq = seqs[head]
                let gap = Int(Int16(bitPattern: seq &- next))
                if gap > 0 {
                    if gap <= JitterBuffer.target { return conceal(skip: true) } // the sender dropped a few frames
                    next = seq // a long stall: go on with the newest sound
                }
                let slot = head
                head = (head + 1) % Self.slots
                count -= 1
                next = seq &+ 1
                if trim > 0, count >= JitterBuffer.target - 1, quiet[slot] || trimWait > 50 { // ahead: skip one frame
                    trim = 0
                    stats.dropped += 1
                    continue
                }
                stats.framesOut += 1
                missRun = 0
                // The slot is free once the lock is released: copy it out now.
                cur.update(from: pcm + slot * Self.len, count: Self.len)
                last.update(from: cur, count: Self.len)
                hasLast = true
                lastQuiet = quiet[slot]
                return
            }
        }

        private func silence() {
            cur.update(repeating: 0, count: Self.len)
        }

        /// The first missing frame fades the last one out; more are silence. 200 ms of nothing buffers up again.
        private mutating func conceal(skip: Bool) {
            if skip { next &+= 1 }
            stats.concealed += 1
            missRun += 1
            if missRun > 10 { playing = false }
            if missRun == 1, hasLast {
                fade = true
                cur.update(from: last, count: Self.len)
                return
            }
            silence()
        }

        /// Every 2 s: an average depth well off 60 ms means the clocks drift; correct it by one frame.
        private mutating func measureDrift() {
            depthSum += count
            depthCount += 1
            if trim != 0 { trimWait += 1 }
            guard depthCount >= 100 else { return }
            let avg = Double(depthSum) / Double(depthCount)
            if trim == 0 {
                if avg > Double(JitterBuffer.target) + 1.5 { trim = 1; trimWait = 0 }
                else if avg < Double(JitterBuffer.target) - 1.5 { trim = -1; trimWait = 0 }
            }
            depthSum = 0
            depthCount = 0
        }
    }
}

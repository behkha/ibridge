import AVFoundation
import os

/// A relayed call's sound on the iPhone (PROTOCOL.md 8). Microphone (voice processing: echo cancellation) → 16 kHz mono
/// → one Opus packet per 20 ms out; Opus packets in → jitter buffer → speaker. Runs only while CallKit's audio session
/// is active: `start()` from `provider(_:didActivate:)`, `stop()` when it ends. `start`/`stop`/`receive` from the main thread.
///
/// The sink and render callbacks run on the real-time audio thread: they never allocate, free, dispatch or wait on a lock
/// held for more than a fixed-size copy. They only touch `MicRing` and `JitterBuffer`, whose storage is allocated up front.
final class CallAudio: @unchecked Sendable {
    /// One encoded 20 ms frame and when it was ready (uptime ns), called on the capture queue.
    var onFrame: ((Data, UInt64) -> Void)?

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
    /// Frames given to the encoder and packets it returned: a gap means it holds sound back.
    private var encoderIn = 0
    private var encoderOut = 0
    private let micWait = Lag()
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
            let session = AVAudioSession.sharedInstance()
            log.notice("audio started: mic \(Int(inFormat.sampleRate), privacy: .public) Hz × \(channels, privacy: .public), Opus 16 kHz 20 ms; session \(Int(session.sampleRate), privacy: .public) Hz, I/O \(Self.ms(session.ioBufferDuration), privacy: .public) ms, input \(Self.ms(session.inputLatency), privacy: .public) ms, output \(Self.ms(session.outputLatency), privacy: .public) ms, presentation in \(Self.ms(input.presentationLatency), privacy: .public) out \(Self.ms(self.engine.outputNode.presentationLatency), privacy: .public) ms")
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

    /// Debug `audio.tune` (PROTOCOL.md 6): the jitter buffer's start depth and spare frames, the audio I/O buffer.
    func tune(start: Int?, spare: Int?, ioMs: Int?) {
        jitter.tune(start: start, spare: spare)
        if let ioMs {
            try? AVAudioSession.sharedInstance().setPreferredIOBufferDuration(Double(min(max(ioMs, 2), 40)) / 1000)
        }
        log.notice("tune: jitter start \(start ?? -1, privacy: .public) spare \(spare ?? -1, privacy: .public), I/O \(ioMs ?? -1, privacy: .public) ms → \(Self.ms(AVAudioSession.sharedInstance().ioBufferDuration), privacy: .public) ms")
    }

    /// Queued `AUDIO` frames: from the encoder to the radio (the central adds to it).
    let sendLag = Lag()
    /// Receive queue only: from the link to the jitter buffer, and the longest wait between two frames.
    private let decodeLag = Lag()
    private var lastArrival: UInt64 = 0
    private var arrivalGap: UInt64 = 0
    private var shortDecodes = 0

    /// One received Opus packet with its `seq`.
    func receive(seq: UInt16, opus packet: Data) {
        let at = DispatchTime.now().uptimeNanoseconds
        rx.async { [self] in
            if lastArrival > 0 { arrivalGap = max(arrivalGap, at &- lastArrival) }
            lastArrival = at
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
            if Int(out.frameLength) != Self.frameLength { shortDecodes += 1 }
            jitter.push(seq: seq, samples: out.floatChannelData![0], count: Int(out.frameLength))
            decodeLag.add(since: at)
        }
    }

    // MARK: capture

    /// Every 5 ms on the capture queue: takes what the microphone wrote to the ring.
    private func startPump() {
        let t = DispatchSource.makeTimerSource(queue: tx)
        t.schedule(deadline: .now() + .milliseconds(5), repeating: .milliseconds(5), leeway: .milliseconds(1))
        t.setEventHandler { [weak self] in self?.encode() }
        t.resume()
        pump = t
    }

    /// Capture queue: resample to 16 kHz, cut 20 ms frames, encode each.
    private func encode() {
        guard let mic, let micBuffer, let resampler, let encoder else { return }
        let tick = DispatchTime.now().uptimeNanoseconds
        let (count, dropped) = mic.read(into: micBuffer.floatChannelData![0], limit: Int(micBuffer.frameCapacity))
        // The oldest sample just read waited this long in the ring (an estimate from the count).
        if count > 0 { micWait.add(since: tick &- UInt64(Double(count) / micBuffer.format.sampleRate * 1e9)) }
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
            encoderIn += 1
            guard status != .error, packet.packetCount > 0, packet.byteLength > 0 else { continue }
            encoderOut += Int(packet.packetCount)
            shared.withLock { $0.micFrames += 1 }
            onFrame?(Data(bytes: packet.data, count: Int(packet.byteLength)), DispatchTime.now().uptimeNanoseconds)
        }
    }

    // MARK: helpers

    static func ms(_ seconds: TimeInterval) -> String { String(format: "%.1f", seconds * 1000) }

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
            self?.encoderIn = 0; self?.encoderOut = 0
        }
        rx.async { [weak self] in self?.decoder = nil; self?.lastArrival = 0; self?.arrivalGap = 0 }
    }

    /// Once a second while a call runs: frames in/out of the jitter buffer, its depth, concealed and late frames.
    private func startLog() {
        let t = DispatchSource.makeTimerSource(queue: rx)
        t.schedule(deadline: .now() + 1, repeating: 1)
        t.setEventHandler { [weak self] in
            guard let self else { return }
            let s = self.jitter.takeStats()
            let mic = self.shared.withLock { s -> Int in defer { s.micFrames = 0 }; return s.micFrames }
            let (encoderHeld, micWait) = self.tx.sync { (self.encoderIn - self.encoderOut, self.micWait.take()) }
            let gap = self.arrivalGap / 1_000_000
            self.arrivalGap = 0
            let short = self.shortDecodes
            self.shortDecodes = 0
            // Lags are avg/max ms: mic = waiting in the microphone ring, send = encoded until written to the radio,
            // decode = received until in the jitter buffer; depth = frames left after each take (min/avg/max).
            self.log.notice("audio: in \(s.framesIn, privacy: .public) out \(s.framesOut, privacy: .public) depth \(s.depth, privacy: .public) concealed \(s.concealed, privacy: .public) late \(s.late, privacy: .public) dropped \(s.dropped, privacy: .public) trimmed \(s.trimmed, privacy: .public) mic \(mic, privacy: .public) | gap \(gap, privacy: .public) ms, lag mic \(micWait, privacy: .public) send \(self.sendLag.take(), privacy: .public) decode \(self.decodeLag.take(), privacy: .public), encoder holds \(encoderHeld, privacy: .public), short decodes \(short, privacy: .public)")
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

/// Playout of received frames (PROTOCOL.md 8, "Timing"): starts once `start` frames are buffered, conceals a missing frame
/// with a short fade, and keeps itself as shallow as the link allows: a frame that is late is waited for (the buffer grows
/// by one), and when for 1 s there were always more than `spare` frames left after each take, that many extra frames are
/// dropped, each at a quiet moment (or within half a second). So a burst after a link stall is gone in a second or two,
/// and clock drift is absorbed the same way. After running dry it trims nothing for 10 s, so it settles at the depth the
/// sender's bursts need instead of trimming into the next gap.
/// `push`, `reset`, `tune` and `takeStats` from the receive queue or main, `render` from the real-time thread. Frames live in
/// slots allocated once; the lock is held for at most one call's worth of samples and fixed-size frame copies, never
/// around an allocation, a free or a decode.
final class JitterBuffer: @unchecked Sendable {
    static let maxSkip = 3 // frames the sender may drop that are concealed in place; a longer gap jumps to the newest
    static let capacity = 25 // frames: half a second after a stall, keep the newest
    struct Stats { var framesIn = 0, framesOut = 0, concealed = 0, late = 0, dropped = 0, trimmed = 0, depth = "-" }

    private let state = OSAllocatedUnfairLock(uncheckedState: State())

    deinit { state.withLockUnchecked { $0.free() } }

    func reset() { state.withLockUnchecked { $0.reset() } }

    func tune(start: Int?, spare: Int?) {
        state.withLockUnchecked { s in
            if let start { s.start = min(max(start, 1), 10) }
            if let spare { s.spare = min(max(spare, 0), 10) }
        }
    }

    /// One decoded frame. Short ones are padded with silence, an empty one is a silent frame.
    func push(seq: UInt16, samples: UnsafePointer<Float>, count: Int) {
        let quiet = Self.isQuiet(samples, count)
        state.withLockUnchecked { $0.push(seq: seq, samples: samples, count: count, quiet: quiet) }
    }

    /// Real-time thread: fills `count` samples.
    func render(_ out: UnsafeMutablePointer<Float>, _ count: Int) {
        state.withLockUnchecked { $0.render(out, count) }
    }

    /// The counters since the last call; `depth` is the frames left after each take, min/avg/max.
    func takeStats() -> Stats {
        state.withLockUnchecked { s in
            defer { s.stats = .init(); s.depthN = 0; s.depthSum = 0; s.depthMin = .max; s.depthMax = 0 }
            var r = s.stats
            if s.depthN > 0 { r.depth = "\(s.depthMin)/\(String(format: "%.1f", Double(s.depthSum) / Double(s.depthN)))/\(s.depthMax)" }
            return r
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
        /// Frames buffered before playing starts (40 ms), and frames kept in hand beyond the one playing. Kept across calls.
        var start = 2
        var spare = 1
        var head = 0
        var count = 0
        var stats = Stats()
        var next: UInt16 = 0
        var playing = false
        var pos = 0
        var fade = false
        var hasLast = false
        var missRun = 0
        var lateRun = 0
        /// The fewest frames left after a take in this 1 s window, and how many frames are still to be dropped.
        var windowMin = Int.max
        var takes = 0
        var trim = 0
        var trimWait = 0
        /// Takes left before trimming may start again after the buffer ran dry.
        var holdOff = 0
        var depthN = 0, depthSum = 0, depthMin = Int.max, depthMax = 0

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
            hasLast = false; missRun = 0; lateRun = 0
            windowMin = .max; takes = 0; trim = 0; trimWait = 0; holdOff = 0
            depthN = 0; depthSum = 0; depthMin = .max; depthMax = 0
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
                guard count >= start else { return silence() }
                playing = true
                next = seqs[head]
                missRun = 0
            }
            while true {
                guard count > 0 else { return conceal(skip: false) } // late: wait for it (the buffer grows by one)
                let seq = seqs[head]
                let gap = Int(Int16(bitPattern: seq &- next))
                if gap > 0 {
                    if gap <= JitterBuffer.maxSkip { return conceal(skip: true) } // the sender dropped a few frames
                    next = seq // a long stall: go on with the newest sound
                }
                let slot = head
                head = (head + 1) % Self.slots
                count -= 1
                next = seq &+ 1
                if trim > 0, count > 0, quiet[slot] || trimWait > 25 { // ahead: skip one frame, quietly if it can
                    trim -= 1
                    trimWait = 0
                    stats.trimmed += 1
                    continue
                }
                measure()
                stats.framesOut += 1
                missRun = 0
                // The slot is free once the lock is released: copy it out now.
                cur.update(from: pcm + slot * Self.len, count: Self.len)
                last.update(from: cur, count: Self.len)
                hasLast = true
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
            windowMin = 0 // the buffer ran dry: no trimming for a while
            trim = 0
            holdOff = 500
            if missRun > 10 { playing = false }
            if missRun == 1, hasLast {
                fade = true
                cur.update(from: last, count: Self.len)
                return
            }
            silence()
        }

        /// After each take: every second, frames always left beyond `spare` are delay the link does not need; drop them.
        private mutating func measure() {
            depthN += 1; depthSum += count; depthMin = min(depthMin, count); depthMax = max(depthMax, count)
            windowMin = min(windowMin, count)
            if trim > 0 { trimWait += 1 }
            if holdOff > 0 { holdOff -= 1 }
            takes += 1
            guard takes >= 50 else { return }
            if trim == 0, holdOff == 0, windowMin > spare { trim = windowMin - spare; trimWait = 0 }
            takes = 0
            windowMin = .max
        }
    }
}

/// How long one stage held its frames, for the once-a-second log: "avg/max" in ms, "-" when none passed. Any thread
/// except the real-time one.
final class Lag: @unchecked Sendable {
    private struct Sums { var n = 0; var sum: UInt64 = 0; var max: UInt64 = 0 }
    private let sums = OSAllocatedUnfairLock(initialState: Sums())

    /// `since`: uptime nanoseconds when the frame entered the stage.
    func add(since: UInt64) {
        let d = DispatchTime.now().uptimeNanoseconds &- since
        guard d < 10_000_000_000 else { return }
        sums.withLock { s in s.n += 1; s.sum += d; s.max = Swift.max(s.max, d) }
    }

    func take() -> String {
        sums.withLock { s in
            defer { s = Sums() }
            return s.n == 0 ? "-" : "\(s.sum / UInt64(s.n) / 1_000_000)/\(s.max / 1_000_000)"
        }
    }
}

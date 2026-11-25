import Foundation
import os

/// Debug `bench` (PROTOCOL.md 6): how many `AUDIO` records the link carries each way. Calls need 50 a second.
/// Android sends `n` stream-0 records as fast as flow control allows; this iPhone counts them, then sends `n` back
/// the same way, and Android answers `bench.done` with its own count and time. Results go to the log (`idevicesyslog -p iBridge`).
@MainActor
final class Bench {
    let n: Int
    let size: Int
    /// Called once with a summary for the screen (nil when the run was cut off).
    var onFinish: ((String?) -> Void)?
    /// Called when records may go out: the central pulls them with `nextFrame()`.
    var onSendReady: (() -> Void)?

    private let log = Logger(subsystem: "app.ibridge", category: "bench")
    private var got = 0
    private var gotBytes = 0
    private var firstAt: Date?
    private var lastAt: Date?
    private var toSend = 0
    private var seq: UInt16 = 0
    private var sendStart: Date?
    private var sendEnd: Date?
    private var incoming: String?
    private var watchdog: Timer?
    private var lastProgress = Date()

    init(n: Int, size: Int) {
        self.n = n
        self.size = size
    }

    func started() {
        log.notice("bench: asked Android for \(self.n, privacy: .public) records of \(self.size, privacy: .public) bytes")
        lastProgress = Date()
        watchdog = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.check() }
        }
    }

    /// One stream-0 record from Android.
    func received(_ plain: Data) {
        guard toSend == 0, sendStart == nil else { return }
        let now = Date()
        if firstAt == nil { firstAt = now }
        lastAt = now
        lastProgress = now
        got += 1
        gotBytes += plain.count
        if got >= n { startSending() }
    }

    /// The next record to send back, while the reply runs.
    func nextFrame() -> Data? {
        guard toSend > 0 else { return nil }
        if sendStart == nil { sendStart = Date() }
        toSend -= 1
        if toSend == 0 {
            sendEnd = Date()
            log.notice("bench I→A: handed \(self.n, privacy: .public) records to Bluetooth in \(Self.ms(self.sendStart, self.sendEnd), privacy: .public) ms")
        }
        lastProgress = Date()
        var plain = Data([0, UInt8(seq >> 8), UInt8(seq & 0xFF)])
        plain.append(Data(count: max(size, 1)))
        seq &+= 1
        return plain
    }

    /// `bench.done {n, ms}`: Android's count of this iPhone's records and how long they took to arrive.
    func androidDone(_ m: [String: Any]) {
        let count = m.int("n") ?? 0
        let ms = max(m.int("ms") ?? 0, 1)
        let rate = Double(count) * 1000 / Double(ms)
        let line = "bench I→A (Android's count): \(count) records in \(ms) ms = \(Int(rate)) rec/s"
        log.notice("\(line, privacy: .public)")
        finish([incoming ?? "A→I: none", "I→A: \(count) in \(ms) ms, \(Int(rate))/s"].joined(separator: "\n"))
    }

    func cancel() {
        guard watchdog != nil else { return }
        log.notice("bench: link dropped, run cut off")
        finish(nil)
    }

    private func startSending() {
        let ms = Self.ms(firstAt, lastAt)
        let seconds = max(Double(ms) / 1000, 0.001)
        let rate = Double(got) / seconds
        let kbit = Double(gotBytes) * 8 / seconds / 1000
        // On the air each record also carries type, counter and tag (25 bytes) plus a chunk header.
        let airKbit = Double(gotBytes + got * 26) * 8 / seconds / 1000
        log.notice("bench A→I: \(self.got, privacy: .public)/\(self.n, privacy: .public) records in \(ms, privacy: .public) ms = \(Int(rate), privacy: .public) rec/s, \(Int(kbit), privacy: .public) kbit/s plaintext, \(Int(airKbit), privacy: .public) kbit/s on the air (calls need 50 rec/s)")
        incoming = "A→I: \(got) in \(ms) ms, \(Int(rate))/s"
        toSend = n
        onSendReady?()
    }

    /// Android stopped sending before `n` (it may drop records): reply with what arrived. Nothing at all: give up.
    private func check() {
        let idle = Date().timeIntervalSince(lastProgress)
        if toSend == 0, sendStart == nil, idle > 3 {
            if got > 0 {
                log.notice("bench: only \(self.got, privacy: .public) of \(self.n, privacy: .public) records came; replying anyway")
                startSending()
            } else if idle > 10 {
                log.notice("bench: no records from Android in 10 s (does its build know `bench`?)")
                finish("A→I: no records")
            }
        } else if sendEnd != nil, idle > 15 {
            log.notice("bench: no bench.done from Android in 15 s")
            finish((incoming ?? "") + "\nI→A: no answer")
        }
    }

    private func finish(_ report: String?) {
        watchdog?.invalidate()
        watchdog = nil
        let done = onFinish
        onFinish = nil
        done?(report.map { "Bench\n" + $0 })
    }

    private static func ms(_ a: Date?, _ b: Date?) -> Int {
        guard let a, let b else { return 0 }
        return Int(b.timeIntervalSince(a) * 1000)
    }
}

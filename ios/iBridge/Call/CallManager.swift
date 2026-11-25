import AVFoundation
import CallKit
import Foundation
import os

/// Relayed calls (PROTOCOL.md 8): the iPhone's own call screen through CallKit, the call messages to Android,
/// and the call's sound as `AUDIO` records. One call at a time. Main actor only.
@MainActor
final class CallManager: NSObject {
    /// Sends one message on the live link; false while not live. Set by the central.
    var send: (([String: Any]) -> Bool)?
    /// Queues one `AUDIO` plaintext. Set by the central.
    var sendAudio: ((Data) -> Void)?

    private let store: Store
    private let provider: CXProvider
    private let controller = CXCallController()
    private let audio = CallAudio()
    private let log = Logger(subsystem: "app.ibridge", category: "call")

    /// The one call CallKit knows about.
    private struct Relay {
        let uuid: UUID
        let outgoing: Bool
        let addr: String
        var call: String?       // Android's call id; for a dialed call, from its first `call.state`
        var req: String?        // our `call.dial` id
        var answered = false    // incoming: answered on this iPhone
        var stream: UInt8?      // from `call.state`; audio flows once it is known
        var connecting = false  // outgoing: CallKit told it started connecting
        var connected = false
        var seen = true         // Android mentioned it since the link last came up
        var stated = false      // a `call.state` came for it: Android re-sends that state after relinking
    }
    private var relay: Relay?
    private var txSeq: UInt16 = 0
    /// Bumped whenever the relay changes or the link goes up or down: stale timers check it and do nothing.
    private var epoch = 0

    init(store: Store) {
        self.store = store
        let config = CXProviderConfiguration()
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber]
        config.includesCallsInRecents = false // iBridge's Calls tab has them already
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil) // main queue
        audio.onFrame = { [weak self] opus in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.sendFrame(opus) } }
        }
    }

    /// No call in progress (the bench needs the link to itself).
    var isIdle: Bool { relay == nil }

    // MARK: placing a call

    /// Keypad and call-back: places a call through Android. CallKit shows its own screen.
    func dial(_ addr: String, sim: Int? = nil) {
        guard relay == nil, store.canCall else { return }
        let uuid = UUID()
        relay = Relay(uuid: uuid, outgoing: true, addr: addr, req: UUID().uuidString)
        epoch += 1
        dialSim = sim
        configureSession()
        let action = CXStartCallAction(call: uuid, handle: CXHandle(type: .phoneNumber, value: addr))
        controller.request(CXTransaction(action: action)) { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.log.error("start call refused: \(String(describing: error), privacy: .public)")
                    if self?.relay?.uuid == uuid { self?.finish() }
                }
            }
        }
    }

    private var dialSim: Int?

    // MARK: messages from Android

    /// Handles a call message. True when it is fully handled here, so the phase-1 alert must not run.
    func handle(_ m: [String: Any]) -> Bool {
        switch m.str("t") {
        case "call.ring": return ring(m)
        case "call.state": state(m); return true
        case "call.end": end(m); return false // the store still clears a phase-1 alert and its ringing state
        default: return false
        }
    }

    private func ring(_ m: [String: Any]) -> Bool {
        guard let id = m.str("call"), let addr = m.str("addr") else { return false }
        if let r = relay, r.call == id { relay?.seen = true; return true } // re-sent after `hello`
        // Not ready, or busy with another call (no call waiting): the phase-1 alert.
        guard store.callsReady, relay == nil else { return false }
        let uuid = UUID()
        relay = Relay(uuid: uuid, outgoing: false, addr: addr, call: id)
        epoch += 1
        configureSession()
        let name = m.str("name") ?? store.contactName(for: addr)
        provider.reportNewIncomingCall(with: uuid, update: update(addr: addr, name: name)) { [weak self] error in
            guard let error else { return }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.relay?.uuid == uuid else { return }
                    self.log.error("CallKit refused the ring: \(String(describing: error), privacy: .public)")
                    self.finish()
                    // Do Not Disturb or a blocked number: the user chose silence. Anything else: ring the phase-1 way.
                    let code = (error as NSError).code
                    if code != CXErrorCodeIncomingCallError.filteredByDoNotDisturb.rawValue,
                       code != CXErrorCodeIncomingCallError.filteredByBlockList.rawValue {
                        self.store.ringing = (id, addr, m.str("name"))
                        Notify.call(id: id, from: name ?? addr, addr: addr)
                    }
                }
            }
        }
        log.notice("ringing with CallKit")
        return true
    }

    private func state(_ m: [String: Any]) {
        guard let id = m.str("call"), let state = m.str("state") else { return }
        let req = m.str("req")
        guard var r = relay, r.call == id || (r.call == nil && req != nil && r.req == req) else {
            // A relayed call this iPhone doesn't show (the app was closed during it, or the user already hung up):
            // nobody can hear it, so it ends.
            log.notice("call.state \(state, privacy: .public) for a call not shown here: hanging up")
            _ = send?(["t": "call.hangup", "call": id])
            return
        }
        r.call = id
        r.seen = true
        r.stated = true
        if let s = m.int("stream"), s > 0, s < 256, r.stream != UInt8(s) {
            r.stream = UInt8(s)
            txSeq = 0
            audio.newStream()
        }
        if r.outgoing, !r.connecting, state == "dialing" || state == "alerting" || state == "active" {
            r.connecting = true
            provider.reportOutgoingCall(with: r.uuid, startedConnectingAt: nil)
        }
        if state == "active", !r.connected {
            r.connected = true
            if r.outgoing { provider.reportOutgoingCall(with: r.uuid, connectedAt: nil) }
        }
        relay = r
        log.notice("call.state \(state, privacy: .public) stream \(r.stream.map { Int($0) } ?? -1, privacy: .public)")
    }

    private func end(_ m: [String: Any]) {
        let id = m.str("call"), req = m.str("req")
        guard let r = relay, (id != nil && r.call == id) || (req != nil && r.req == req) else { return }
        let how = m.str("how") ?? "ended"
        let reason: CXCallEndedReason
        switch how {
        case "answered": reason = .answeredElsewhere
        case "declined": reason = .declinedElsewhere
        case "missed": reason = .unanswered
        case "failed": reason = .failed
        default: reason = .remoteEnded
        }
        log.notice("call.end \(how, privacy: .public)")
        provider.reportCall(with: r.uuid, endedAt: nil, reason: reason)
        finish()
    }

    // MARK: audio

    /// One `AUDIO` plaintext of a call: `stream(1) || seq(2) || opus`. Other streams are dropped.
    /// Audio passes queued `DATA`, so a call's first frames can come before the `call.state` naming their stream:
    /// a call answered or placed here that has no stream yet takes the first nonzero one (PROTOCOL.md 8).
    func receiveAudio(_ plain: Data) {
        let b = plain.startIndex
        guard plain.count > 3, let r = relay, plain[b] != 0 else { return }
        if r.stream == nil, r.answered || r.outgoing {
            relay?.stream = plain[b]
            txSeq = 0
            audio.newStream()
            log.notice("stream \(Int(plain[b]), privacy: .public) taken from the first frame")
        }
        guard plain[b] == relay?.stream else { return }
        let seq = UInt16(plain[b + 1]) << 8 | UInt16(plain[b + 2])
        audio.receive(seq: seq, opus: Data(plain.dropFirst(3)))
    }

    private func sendFrame(_ opus: Data) {
        guard let stream = relay?.stream, !opus.isEmpty, opus.count <= 400 else { return }
        var plain = Data([stream, UInt8(txSeq >> 8), UInt8(txSeq & 0xFF)])
        plain.append(opus)
        txSeq &+= 1
        sendAudio?(plain)
    }

    private func configureSession() {
        // CallKit activates the session; iBridge only sets it up beforehand (PROTOCOL.md 8, `provider(_:didActivate:)`).
        let s = AVAudioSession.sharedInstance()
        do {
            try s.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try s.setPreferredIOBufferDuration(0.01)
        } catch {
            log.error("audio session setup failed: \(String(describing: error), privacy: .public)")
        }
    }

    // MARK: the link

    /// The session ended. Android keeps a relayed call 20 s for the iPhone to come back; after that it is gone.
    func linkLost() {
        guard relay != nil else { return }
        relay?.seen = false
        epoch += 1
        let e = epoch
        DispatchQueue.main.asyncAfter(deadline: .now() + 22) { [weak self] in
            guard let self, self.epoch == e, let r = self.relay, !r.seen else { return }
            self.log.notice("link still down: ending the call")
            self.provider.reportCall(with: r.uuid, endedAt: nil, reason: .failed)
            self.finish()
        }
    }

    /// Live again: Android re-sends `call.ring` / `call.state` for a call that still lasts. One it doesn't mention is over.
    /// A call answered or dialed here with no `call.state` yet has nothing for Android to re-send: the answer and dial
    /// timeouts decide that one.
    func linkLive() {
        guard relay != nil else { return }
        relay?.seen = false
        epoch += 1
        let e = epoch
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak self] in
            guard let self, self.epoch == e, let r = self.relay, !r.seen, r.stated || !(r.answered || r.outgoing) else { return }
            self.log.notice("Android didn't mention the call after relinking: it is over")
            self.provider.reportCall(with: r.uuid, endedAt: nil, reason: r.connected ? .remoteEnded : .failed)
            self.finish()
        }
    }

    // MARK: helpers

    private func update(addr: String, name: String?) -> CXCallUpdate {
        let u = CXCallUpdate()
        u.remoteHandle = CXHandle(type: .phoneNumber, value: addr)
        u.localizedCallerName = name
        u.hasVideo = false
        u.supportsDTMF = true
        u.supportsHolding = false
        u.supportsGrouping = false
        u.supportsUngrouping = false
        return u
    }

    private func finish() {
        relay = nil
        dialSim = nil
        epoch += 1
        audio.stop()
    }
}

extension CallManager: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated {
            log.notice("CallKit reset")
            if let id = relay?.call { _ = send?(["t": "call.hangup", "call": id]) }
            finish()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated {
            guard var r = relay, r.uuid == action.callUUID, let req = r.req else { action.fail(); return }
            var m: [String: Any] = ["t": "call.dial", "req": req, "to": r.addr]
            if let sim = dialSim { m["sim"] = sim }
            guard send?(m) == true else { action.fail(); finish(); return }
            action.fulfill()
            r.seen = true
            relay = r
            provider.reportCall(with: r.uuid, updated: update(addr: r.addr, name: store.contactName(for: r.addr)))
            log.notice("call.dial sent")
            // No `call.state` at all (an older Android build, a lost record): the call failed. Not tied to `epoch`,
            // so a relink in between doesn't cancel it.
            let uuid = r.uuid
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                guard let self, let r = self.relay, r.uuid == uuid, !r.stated else { return }
                self.log.notice("no call.state for call.dial in 15 s")
                self.provider.reportCall(with: r.uuid, endedAt: nil, reason: .failed)
                self.finish()
            }
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        MainActor.assumeIsolated {
            guard let r = relay, r.uuid == action.callUUID, let id = r.call, send?(["t": "call.answer", "call": id]) == true else {
                action.fail()
                return
            }
            relay?.answered = true
            action.fulfill()
            log.notice("call.answer sent")
            // No `call.state` within 15 s (a lost record, a relink that dropped it): nobody hears this call, so it ends.
            let uuid = r.uuid
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                guard let self, let r = self.relay, r.uuid == uuid, !r.stated else { return }
                self.log.notice("no call.state for call.answer in 15 s")
                _ = self.send?(["t": "call.hangup", "call": id])
                self.provider.reportCall(with: r.uuid, endedAt: nil, reason: .failed)
                self.finish()
            }
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolated {
            guard let r = relay, r.uuid == action.callUUID else { action.fulfill(); return }
            if let id = r.call {
                // Ringing and not answered here: decline it, as the phase-1 alert does.
                let t = !r.outgoing && !r.answered ? "call.decline" : "call.hangup"
                _ = send?(["t": t, "call": id])
                log.notice("\(t, privacy: .public) sent")
            }
            // A dialed call without its first `call.state` yet: that state finds no call here and is hung up then.
            action.fulfill()
            finish()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolated {
            audio.muted = action.isMuted
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXPlayDTMFCallAction) {
        MainActor.assumeIsolated {
            guard let id = relay?.call else { action.fail(); return }
            for d in action.digits where "0123456789*#".contains(d) {
                _ = send?(["t": "call.dtmf", "call": id, "digit": String(d)])
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {
        action.fail() // one call at a time, no hold
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            log.notice("audio session active")
            audio.start()
        }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            log.notice("audio session inactive")
            audio.stop()
        }
    }
}

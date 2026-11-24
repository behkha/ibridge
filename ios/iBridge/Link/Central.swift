import CoreBluetooth
import Foundation
import os
import UIKit

/// The iPhone as BLE central (PROTOCOL.md section 1): finds the Android peripheral, runs the handshake,
/// keeps reconnecting in the background through state restoration.
@MainActor
final class Central: NSObject {
    private let store: Store
    private var manager: CBCentralManager!
    private let log = Logger(subsystem: "app.ibridge", category: "link")

    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var handshake: Handshake?
    private var framer = Framer()
    private var deframer = Deframer()
    private var outbox: [Data] = []

    /// Set while the user is pairing; cleared on success or failure.
    private var pairing: PairingStart?
    private var pairingStarted = Date.distantPast
    /// Peripherals that answered NOT_PAIRED: someone else's iBridge. Ignored for 15 minutes.
    private var ignored: [UUID: Date] = [:]
    private var lastKnown: UUID? {
        get { UserDefaults.standard.string(forKey: "peripheral").flatMap(UUID.init) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "peripheral") }
    }

    private var watchdog: Timer?
    private var pingSentAt: Date?
    /// Connected, but Android's iBridge service wasn't there yet: its next advertisement means it is.
    private var serviceMissing = false
    /// Seconds before the next reconnect: 1, doubling to 60 while attempts keep failing; back to 1 once live.
    private var retryDelay: Double = 1

    init(store: Store) {
        self.store = store
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionRestoreIdentifierKey: "app.ibridge.central"])
        // Never sit idle while not linked, and never trust a "linked" state Android no longer shares.
        watchdog = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkLink() }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkLink(probe: true) }
        }
        store.onMorePages = { [weak self] kind, cursor in self?.send(["t": "sync", "kind": kind, "cursor": cursor ?? NSNull(), "limit": 200]) }
        store.onOutbox = { [weak self] in self?.flush() }
    }

    // MARK: public

    func pair(_ start: PairingStart) {
        pairing = start
        pairingStarted = Date()
        ignored = [:]
        store.pairingError = nil
        disconnect()
        connectOrScan()
    }

    func cancelPairing() {
        pairing = nil
        if store.peer == nil { disconnect(); manager.stopScan() }
    }

    func unpair() {
        send(["t": "unpair"])
        lastKnown = nil
        store.unpair()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.disconnect() }
    }

    /// Queues the text in the outbox; it goes now when live, or once the link is back.
    @discardableResult
    func sendText(to: String, body: String, sim: Int? = nil) -> String {
        store.enqueue(to: to, body: body, sim: sim)
    }

    /// Sends queued texts and read marks, in order, while a session is live (PROTOCOL.md 6, "While the link is down").
    func flush() {
        guard store.link == .linked, let session = store.session, handshake?.isLive == true else { return }
        for i in store.outbox.indices where store.outbox[i].session == nil && !store.outbox[i].failed {
            let o = store.outbox[i]
            var m: [String: Any] = ["t": "send", "req": o.req, "to": o.to, "body": o.body]
            if let sim = o.sim { m["sim"] = sim }
            guard send(m) else { return }
            store.outbox[i].session = session
        }
        if !store.pendingRead.isEmpty {
            let ids = store.pendingRead.sorted { (Int64($0) ?? 0) < (Int64($1) ?? 0) }
            for start in stride(from: 0, to: ids.count, by: 500) {
                guard send(["t": "read", "ids": Array(ids[start..<min(start + 500, ids.count)])]) else { return }
            }
            store.pendingRead = []
        }
        store.save()
    }

    func declineCall(_ id: String) { send(["t": "call.decline", "call": id]) }

    /// Set when contacts changed during a contacts sync: one more full sync runs when it ends.
    private var contactsAgain = false

    /// Full contacts sync from `null`. Two at once would interleave their pages, so a second ask waits for the first.
    func refreshContacts() {
        if store.syncing.contains("contacts") { contactsAgain = true; return }
        guard send(["t": "sync", "kind": "contacts", "cursor": NSNull(), "limit": 200]) else { return }
        store.cursors["contacts"] = nil
        store.syncing.insert("contacts")
    }

    @discardableResult
    func send(_ m: [String: Any]) -> Bool {
        guard let h = handshake, h.isLive, let packet = try? h.seal(m) else { return false }
        queue(packet)
        return true
    }

    // MARK: connection

    /// Drops the connection and connects again; the pause lets iOS finish the cancel before the new connect,
    /// and grows while attempts keep failing (lost bond, foreign build) so the radio never spins.
    private func reconnect() {
        disconnect()
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, 60)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.connectOrScan() }
    }

    /// Keeps a scan and a pending connect running while not linked. With `probe`, pings a link that looks live:
    /// a restarted Android can leave the radio link up with no session behind it.
    private func checkLink(probe: Bool = false) {
        guard manager.state == .poweredOn, store.peer != nil || pairing != nil else { return }
        if let sent = pingSentAt, Date().timeIntervalSince(sent) > 5 {
            log.notice("no pong in 5 s; dropping a dead link")
            pingSentAt = nil
            disconnect()
            connectOrScan()
            return
        }
        if handshake?.isLive == true {
            if probe, pingSentAt == nil, send(["t": "ping"]) { pingSentAt = Date() }
            return
        }
        if peripheral == nil || peripheral?.state == .disconnected { reset(); connectOrScan(); return }
        if let p = peripheral, p.state == .connected, rx == nil {
            p.discoverServices([Proto.service]) // the service may have appeared since the last look
            return
        }
        if !manager.isScanning {
            log.notice("watchdog: scan was off, restarting")
            manager.scanForPeripherals(withServices: [Proto.service], options: nil)
        }
    }

    private func connectOrScan() {
        guard manager.state == .poweredOn, peripheral == nil else { return }
        if pairing == nil, store.peer == nil { return }
        if pairing == nil, let id = lastKnown, !isIgnored(id), let p = manager.retrievePeripherals(withIdentifiers: [id]).first {
            log.notice("pending connect to last known \(id.uuidString.prefix(8), privacy: .public)")
            connect(p) // pending connects never time out on iOS
        }
        log.notice("scanning")
        manager.scanForPeripherals(withServices: [Proto.service], options: nil)
        if store.peer != nil, store.link == .linked { store.link = .connecting }
    }

    private func isIgnored(_ id: UUID) -> Bool {
        guard let t = ignored[id] else { return false }
        return Date().timeIntervalSince(t) < 900
    }

    private func connect(_ p: CBPeripheral) {
        peripheral = p
        p.delegate = self
        manager.connect(p, options: nil)
    }

    private func disconnect() {
        if let p = peripheral { manager.cancelPeripheralConnection(p) }
        reset()
    }

    private func reset() {
        store.session = nil
        store.syncing = [] // pages of a dropped session never arrive
        contactsAgain = false
        pingSentAt = nil
        serviceMissing = false
        peripheral = nil
        rx = nil
        handshake = nil
        framer = Framer()
        deframer = Deframer()
        outbox = []
    }

    private func lost() {
        log.notice("link lost; app state \(Self.appState, privacy: .public)")
        reset()
        if store.peer != nil {
            if case .outOfReach = store.link {} else { store.link = .outOfReach(since: Date()) }
        }
        connectOrScan()
    }

    // MARK: packets

    private func queue(_ packet: Data) {
        guard let p = peripheral else { return }
        framer.split(packet, chunkSize: p.maximumWriteValueLength(for: .withoutResponse)).forEach { outbox.append($0) }
        pump()
    }

    private func pump() {
        guard let p = peripheral, let rx else { return }
        while !outbox.isEmpty && p.canSendWriteWithoutResponse {
            p.writeValue(outbox.removeFirst(), for: rx, type: .withoutResponse)
        }
    }

    private func ready() {
        let h = Handshake(identity: Identity.shared, myName: deviceName())
        handshake = h
        do {
            let events: [Handshake.Event]
            if let start = pairing { events = try h.startPairing(start) }
            else if let peer = store.peer { events = h.startSession(peer: peer) }
            else { disconnect(); return }
            handle(events)
        } catch { fail(error) }
    }

    private func handle(_ events: [Handshake.Event]) {
        for e in events {
            switch e {
            case .send(let p): queue(p)
            case .paired(let peer):
                pairing = nil
                PeerStore.save(peer)
                store.adopt(peer)
                lastKnown = peripheral?.identifier
                log.notice("paired with \(peer.name, privacy: .public)")
            case .live:
                log.notice("live")
                retryDelay = 1
                store.link = .linked
                store.session = UUID().uuidString
                lastKnown = peripheral?.identifier
                manager.stopScan()
                // A ring alert from before the link dropped may be stale; Android sends `call.ring` again if it still rings.
                if let r = store.ringing { Notify.clear(r.id); store.ringing = nil }
                for kind in ["sms", "calls"] {
                    store.syncing.insert(kind)
                    send(["t": "sync", "kind": kind, "cursor": store.cursors[kind] ?? NSNull(), "limit": 200])
                }
                if store.contacts.isEmpty { refreshContacts() }
                flush()
            case .message(let m) where m.str("t") == "pong":
                pingSentAt = nil
            case .message(let m):
                let contactsNowAllowed = m.str("t") == "status"
                    && (m["perms"] as? [String: Any])?["contacts"] as? Bool == true
                    && store.status?.perms["contacts"] == false
                store.apply(m)
                if contactsNowAllowed || m.str("t") == "contacts.changed" { refreshContacts() }
                if contactsAgain, m.str("t") == "page", m.str("kind") == "contacts", !store.syncing.contains("contacts") {
                    contactsAgain = false
                    refreshContacts()
                }
                if m.str("t") == "unpair" { lastKnown = nil }
            }
        }
    }

    private func fail(_ error: Error) {
        log.error("link failed: \(String(describing: error), privacy: .public)")
        let id = peripheral?.identifier
        if let e = error as? LinkError {
            switch e {
            case .notPaired where pairing == nil:
                if let id { ignored[id] = Date() }
                // Our own Android no longer knows this iPhone: it was unlinked or reinstalled there.
                if id != nil, id == lastKnown {
                    lastKnown = nil
                    store.unpair()
                    store.pairingError = String(localized: "Your Android phone unlinked this iPhone. Pair again.")
                }
            case .pairingClosed where pairing != nil:
                // Some other iBridge phone nearby, or ours with its pairing sheet closed. Keep looking for a while.
                if let id { ignored[id] = Date() }
                if Date().timeIntervalSince(pairingStarted) > 30 {
                    pairing = nil
                    store.pairingError = String(localized: "Can’t find your Android phone. Keep its pairing code open and nearby.")
                }
            case .codeMismatch, .badProof:
                if pairing != nil { store.pairingError = String(localized: "The code didn’t match. Check it and try again."); pairing = nil }
            case .tooManyAttempts:
                store.pairingError = String(localized: "Too many tries. Reopen the pairing code on Android.")
                pairing = nil
            default: break
            }
        }
        reconnect()
    }

    private static var appState: String {
        switch UIApplication.shared.applicationState {
        case .active: "active"
        case .inactive: "inactive"
        default: "background"
        }
    }

    private func deviceName() -> String {
        var info = utsname()
        uname(&info)
        let model = withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return ModelNames.name(for: model)
    }
}

extension Central: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        MainActor.assumeIsolated {
            log.notice("bluetooth state \(central.state.rawValue, privacy: .public), restored \(self.peripheral != nil, privacy: .public)")
            guard central.state == .poweredOn else {
                // Powering off cancels connects without callbacks: forget them so power-on starts clean.
                if store.peer != nil, store.link == .linked { store.link = .outOfReach(since: Date()) }
                reset()
                return
            }
            if let p = peripheral, handshake == nil {
                // Relaunched by state restoration: the session keys died with the old process. Handshake again.
                if p.state == .connected { p.discoverServices([Proto.service]) } else { connect(p) }
                manager.scanForPeripherals(withServices: [Proto.service], options: nil)
                return
            }
            connectOrScan()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            log.notice("state restoration")
            if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
                peripheral = p
                p.delegate = self
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            log.notice("discovered \(p.identifier.uuidString.prefix(8), privacy: .public) rssi \(RSSI.intValue, privacy: .public)")
            if isIgnored(p.identifier) { return }
            if let cur = peripheral {
                // Keep a live or in-progress link to this same phone. A pending connect to an old
                // identifier is dropped: Android rotates its address, so the scan result wins.
                if cur.identifier == p.identifier || cur.state == .connected {
                    // Connected before Android's iBridge service was up: its advertising means the service is here now.
                    if cur.identifier == p.identifier, cur.state == .connected, serviceMissing {
                        serviceMissing = false
                        cur.discoverServices([Proto.service])
                    }
                    return
                }
                manager.cancelPeripheralConnection(cur)
                reset()
            }
            connect(p)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        MainActor.assumeIsolated {
            log.notice("connected \(p.identifier.uuidString.prefix(8), privacy: .public)")
            p.discoverServices([Proto.service])
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated {
            log.notice("connect failed: \(String(describing: error), privacy: .public)")
            if p == peripheral { lost() }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { if p == peripheral { lost() } }
    }
}

extension Central: CBPeripheralDelegate {
    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            // Bonded, iOS may reconnect before Android's iBridge service is up. Keep the link and look again:
            // didModifyServices or the watchdog retries discovery.
            guard let s = p.services?.first(where: { $0.uuid == Proto.service }) else {
                log.notice("connected, but Android's iBridge service isn't there yet; waiting")
                serviceMissing = true
                return
            }
            serviceMissing = false
            p.discoverCharacteristics([Proto.rx, Proto.tx], for: s)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            rx = service.characteristics?.first { $0.uuid == Proto.rx }
            // Missing when discovery raced Android re-adding its service: start over rather than sit idle.
            guard rx != nil, let tx = service.characteristics?.first(where: { $0.uuid == Proto.tx }) else {
                log.notice("Android's iBridge characteristics missing; reconnecting")
                reconnect()
                return
            }
            p.setNotifyValue(true, for: tx)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if c.isNotifying { ready() } else { reconnect() }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            guard let chunk = c.value, let h = handshake else { return }
            do {
                if let packet = try deframer.feed(chunk) { handle(try h.receive(packet)) }
            } catch { fail(error) }
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        MainActor.assumeIsolated {
            log.notice("Android's services changed")
            if invalidatedServices.contains(where: { $0.uuid == Proto.service }) {
                // Android's iBridge restarted under us: the old session is gone.
                disconnect()
                connectOrScan()
            } else if rx == nil {
                p.discoverServices([Proto.service]) // maybe iBridge's service just appeared
            }
        }
    }

    nonisolated func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        MainActor.assumeIsolated { pump() }
    }
}

/// Marketing names for the link pill on Android ("Linked to iPhone 15"). iOS hides the user-set name.
enum ModelNames {
    static func name(for id: String) -> String {
        let table: [String: String] = [
            "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max", "iPhone12,8": "iPhone SE",
            "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max", "iPhone13,1": "iPhone 12 mini",
            "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max", "iPhone14,4": "iPhone 13 mini",
            "iPhone14,6": "iPhone SE", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus", "iPhone15,2": "iPhone 14 Pro",
            "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus", "iPhone16,1": "iPhone 15 Pro",
            "iPhone16,2": "iPhone 15 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,1": "iPhone 16 Pro",
            "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,5": "iPhone 16e",
            "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
        ]
        return table[id] ?? "iPhone"
    }
}

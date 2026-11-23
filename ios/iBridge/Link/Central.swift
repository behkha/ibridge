import CoreBluetooth
import Foundation
import os

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
    /// Peripherals that answered NOT_PAIRED: someone else's iBridge. Ignored for 15 minutes.
    private var ignored: [UUID: Date] = [:]
    private var lastKnown: UUID? {
        get { UserDefaults.standard.string(forKey: "peripheral").flatMap(UUID.init) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "peripheral") }
    }

    init(store: Store) {
        self.store = store
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main, options: [CBCentralManagerOptionRestoreIdentifierKey: "app.ibridge.central"])
        store.onMorePages = { [weak self] kind, cursor in self?.send(["t": "sync", "kind": kind, "cursor": cursor ?? NSNull(), "limit": 200]) }
    }

    // MARK: public

    func pair(_ start: PairingStart) {
        pairing = start
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

    func sendText(to: String, body: String, sim: Int? = nil) -> String {
        let req = UUID().uuidString
        var m: [String: Any] = ["t": "send", "req": req, "to": to, "body": body]
        if let sim { m["sim"] = sim }
        send(m)
        return req
    }

    func declineCall(_ id: String) { send(["t": "call.decline", "call": id]) }

    func refreshContacts() {
        store.cursors["contacts"] = nil
        send(["t": "sync", "kind": "contacts", "cursor": NSNull(), "limit": 200])
    }

    @discardableResult
    func send(_ m: [String: Any]) -> Bool {
        guard let h = handshake, h.isLive, let packet = try? h.seal(m) else { return false }
        queue(packet)
        return true
    }

    // MARK: connection

    private func connectOrScan() {
        guard manager.state == .poweredOn, peripheral == nil else { return }
        if pairing == nil, store.peer == nil { return }
        if pairing == nil, let id = lastKnown, let p = manager.retrievePeripherals(withIdentifiers: [id]).first {
            connect(p) // pending connects never time out on iOS
        }
        manager.scanForPeripherals(withServices: [Proto.service], options: nil)
        if store.peer != nil, store.link == .linked { store.link = .connecting }
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
        peripheral = nil
        rx = nil
        handshake = nil
        framer = Framer()
        deframer = Deframer()
        outbox = []
    }

    private func lost() {
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
                store.peer = peer
                lastKnown = peripheral?.identifier
                log.info("paired with \(peer.name, privacy: .public)")
            case .live:
                store.link = .linked
                lastKnown = peripheral?.identifier
                manager.stopScan()
                for kind in ["sms", "calls"] { send(["t": "sync", "kind": kind, "cursor": store.cursors[kind] ?? NSNull(), "limit": 200]) }
                if store.contacts.isEmpty { refreshContacts() }
            case .message(let m):
                let contactsNowAllowed = m.str("t") == "status"
                    && (m["perms"] as? [String: Any])?["contacts"] as? Bool == true
                    && store.status?.perms["contacts"] == false
                store.apply(m)
                if contactsNowAllowed { refreshContacts() }
            }
        }
    }

    private func fail(_ error: Error) {
        log.error("link failed: \(String(describing: error), privacy: .public)")
        if let e = error as? LinkError {
            switch e {
            case .notPaired where pairing == nil:
                if let id = peripheral?.identifier { ignored[id] = Date() }
            case .codeMismatch, .badProof:
                if pairing != nil { store.pairingError = String(localized: "The code didn’t match. Check it and try again."); pairing = nil }
            case .tooManyAttempts:
                store.pairingError = String(localized: "Too many tries. Reopen the pairing code on Android.")
                pairing = nil
            default: break
            }
        }
        disconnect()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in self?.connectOrScan() }
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
            if central.state == .poweredOn { connectOrScan() }
            else if store.peer != nil, store.link == .linked { lost() }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        MainActor.assumeIsolated {
            if let p = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral])?.first {
                peripheral = p
                p.delegate = self
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        MainActor.assumeIsolated {
            if let t = ignored[p.identifier], Date().timeIntervalSince(t) < 900 { return }
            if let cur = peripheral {
                // Keep a live or in-progress link to this same phone. A pending connect to an old
                // identifier is dropped: Android rotates its address, so the scan result wins.
                if cur.identifier == p.identifier || cur.state == .connected { return }
                manager.cancelPeripheralConnection(cur)
                reset()
            }
            connect(p)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        MainActor.assumeIsolated { p.discoverServices([Proto.service]) }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { if p == peripheral { lost() } }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        MainActor.assumeIsolated { if p == peripheral { lost() } }
    }
}

extension Central: CBPeripheralDelegate {
    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        MainActor.assumeIsolated {
            guard let s = p.services?.first(where: { $0.uuid == Proto.service }) else { disconnect(); return }
            p.discoverCharacteristics([Proto.rx, Proto.tx], for: s)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        MainActor.assumeIsolated {
            rx = service.characteristics?.first { $0.uuid == Proto.rx }
            guard rx != nil, let tx = service.characteristics?.first(where: { $0.uuid == Proto.tx }) else { disconnect(); return }
            p.setNotifyValue(true, for: tx)
        }
    }

    nonisolated func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        MainActor.assumeIsolated {
            if c.isNotifying { ready() } else { disconnect() }
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
        ]
        return table[id] ?? "iPhone"
    }
}

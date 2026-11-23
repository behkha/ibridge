import Foundation
import Observation
import UserNotifications

/// Everything the screens show. The Bluetooth central writes it; the views read it. Main actor only.
@Observable
@MainActor
final class Store {
    var link: LinkState = .unpaired
    var peer: PairedPeer?
    var status: PhoneStatus?
    var messages: [String: SMS] = [:]
    var calls: [String: Call] = [:]
    var contacts: [Contact] = []
    var latestCode: OneTimeCode?
    var ringing: (id: String, addr: String, name: String?)?
    var pairingError: String?
    /// Avatar colour per address, assigned in order of first sight and kept.
    var tints: [String: Int] = [:]
    var cursors: [String: String] = [:]

    var isDemo = false

    // MARK: derived

    var peerName: String { peer?.name ?? status?.name ?? "Android" }

    var threads: [ThreadRow] {
        Dictionary(grouping: messages.values, by: \.thread).compactMap { id, items in
            guard let last = items.max(by: { $0.ts < $1.ts }) else { return nil }
            let title = items.compactMap(\.name).first ?? contactName(for: last.addr) ?? last.addr
            return ThreadRow(id: id, addr: last.addr, title: title, last: last, unread: items.filter { $0.incoming && !$0.read }.count)
        }.sorted { $0.last.ts > $1.last.ts }
    }

    func conversation(_ thread: String) -> [SMS] {
        messages.values.filter { $0.thread == thread }.sorted { $0.ts < $1.ts }
    }

    var callList: [Call] { calls.values.sorted { $0.ts > $1.ts } }

    /// The code card shows the newest code from the last 10 minutes.
    var freshCode: OneTimeCode? {
        guard let c = latestCode, Date().timeIntervalSince(c.ts) < 600 || isDemo else { return nil }
        return c
    }

    func contactName(for addr: String) -> String? {
        let digits = addr.filter(\.isNumber).suffix(9)
        guard digits.count >= 6 else { return nil }
        return contacts.first { $0.phones.contains { $0.n.filter(\.isNumber).hasSuffix(digits) } }?.name
    }

    func tint(for addr: String) -> Int {
        if let t = tints[addr] { return t }
        let t = tints.count
        tints[addr] = t
        return t
    }

    // MARK: incoming messages (PROTOCOL.md 6)

    func apply(_ m: [String: Any]) {
        switch m.str("t") {
        case "status":
            status = PhoneStatus(m)
        case "sms":
            if let s = SMS(m) { insert(s, notify: true) }
        case "otp":
            if let code = m.str("code"), let ts = m.date("ts") {
                latestCode = OneTimeCode(sms: m.str("sms") ?? "", code: code, from: m.str("from") ?? "", ts: ts)
            }
        case "call":
            if let c = Call(m) { calls[c.id] = c }
        case "call.ring":
            if let id = m.str("call"), let addr = m.str("addr") {
                ringing = (id, addr, m.str("name"))
                Notify.call(id: id, from: m.str("name") ?? addr)
            }
        case "call.end":
            if ringing?.id == m.str("call") { ringing = nil }
            if let id = m.str("call") { Notify.clear(id) }
        case "page":
            page(m)
        case "unpair":
            unpair()
        default:
            break
        }
        save()
    }

    private func insert(_ s: SMS, notify: Bool) {
        let isNew = messages[s.id] == nil
        messages[s.id] = s
        _ = tint(for: s.addr)
        if notify && isNew && s.incoming {
            Notify.text(id: s.id, from: s.name ?? contactName(for: s.addr) ?? s.addr, body: s.body)
        }
    }

    private func page(_ m: [String: Any]) {
        guard let kind = m.str("kind") else { return }
        let items = m["items"] as? [[String: Any]] ?? []
        switch kind {
        case "sms": items.compactMap(SMS.init).forEach { insert($0, notify: false) }
        case "calls": items.compactMap(Call.init).forEach { calls[$0.id] = $0 }
        case "contacts":
            let fresh = items.compactMap(Contact.init)
            if cursors["contacts"] == nil { contacts = fresh } else { contacts += fresh }
        default: return
        }
        cursors[kind] = m.str("cursor")
        if (m.bool("more") ?? false) { onMorePages?(kind, m.str("cursor")) } else if kind == "contacts" { cursors["contacts"] = nil }
    }

    /// Set by the central: asks Android for the next page.
    var onMorePages: ((String, String?) -> Void)?

    func unpair() {
        peer = nil
        link = .unpaired
        status = nil
        PeerStore.save(nil)
    }

    // MARK: persistence

    private struct Saved: Codable {
        var messages: [SMS]; var calls: [Call]; var contacts: [Contact]; var code: OneTimeCode?; var tints: [String: Int]; var cursors: [String: String]
    }

    private static var file: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("store.json")
    }

    func load() {
        peer = PeerStore.load()
        link = peer == nil ? .unpaired : .connecting
        guard let data = try? Data(contentsOf: Self.file), let s = try? JSONDecoder().decode(Saved.self, from: data) else { return }
        messages = Dictionary(uniqueKeysWithValues: s.messages.map { ($0.id, $0) })
        calls = Dictionary(uniqueKeysWithValues: s.calls.map { ($0.id, $0) })
        contacts = s.contacts
        latestCode = s.code
        tints = s.tints
        cursors = s.cursors
    }

    func save() {
        guard !isDemo else { return }
        let s = Saved(messages: Array(messages.values), calls: Array(calls.values), contacts: contacts, code: latestCode, tints: tints, cursors: cursors)
        try? JSONEncoder().encode(s).write(to: Self.file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

enum PeerStore {
    static func load() -> PairedPeer? {
        Keychain.read("ibridge-peer").flatMap { try? JSONDecoder().decode(PairedPeer.self, from: $0) }
    }

    static func save(_ p: PairedPeer?) {
        if let p, let d = try? JSONEncoder().encode(p) { Keychain.write("ibridge-peer", d) } else { Keychain.delete("ibridge-peer") }
    }
}

/// Local notifications for texts and calls that arrive while the app is in the background.
enum Notify {
    static let callCategory = "call"

    static func setUp() {
        let c = UNUserNotificationCenter.current()
        c.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        let decline = UNNotificationAction(identifier: "decline", title: String(localized: "Decline"), options: [.destructive])
        let reply = UNTextInputNotificationAction(identifier: "reply", title: String(localized: "Text back"), options: [])
        c.setNotificationCategories([UNNotificationCategory(identifier: callCategory, actions: [decline, reply], intentIdentifiers: [])])
    }

    static func text(id: String, from: String, body: String) {
        let n = UNMutableNotificationContent()
        n.title = from
        n.body = body
        n.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "sms-\(id)", content: n, trigger: nil))
    }

    static func call(id: String, from: String) {
        let n = UNMutableNotificationContent()
        n.title = from
        n.body = String(localized: "Calling your Android phone")
        n.sound = .default
        n.categoryIdentifier = callCategory
        n.userInfo = ["call": id]
        n.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call-\(id)", content: n, trigger: nil))
    }

    static func clear(_ callId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call-\(callId)"])
    }
}

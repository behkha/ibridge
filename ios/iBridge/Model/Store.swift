import Foundation
import Observation
import UIKit
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
    /// Kinds with a paged sync in flight; live items must not move their cursor past unfetched pages.
    var syncing: Set<String> = []
    /// Public key of the Android phone whose texts and calls are stored here.
    var dataOwner: Data?
    /// A one-off problem to show the user, e.g. a text that failed to send.
    var alert: String?
    /// Texts and read marks waiting for a live link, oldest first.
    var outbox: [Outgoing] = []
    var pendingRead: Set<String> = []
    /// Id of the live session, nil while not linked. Set by the central.
    var session: String?
    /// The conversation on screen: its texts are read as they arrive, without a banner.
    var openThread: String?
    /// Set by the central: sends what the outbox holds when the link is live.
    var onOutbox: (() -> Void)?

    var isDemo = false
    /// Demo only: the conversation to open on launch.
    var demoThread: String?

    // MARK: derived

    var peerName: String { peer?.name ?? status?.name ?? "Android" }

    /// Stored texts plus the ones still in the outbox, shown as pending (sent to Android) or queued (waiting for it).
    private var allTexts: [SMS] {
        Array(messages.values) + outbox.map { o in
            SMS(id: "out-\(o.req)", thread: storedThread(for: o.to) ?? "to:\(o.to)", addr: o.to, name: nil, body: o.body, ts: o.ts,
                dir: "out", read: true, sim: o.sim ?? 0, state: o.failed ? "failed" : o.session == nil ? "queued" : "pending")
        }
    }

    var threads: [ThreadRow] {
        Dictionary(grouping: allTexts, by: \.thread).compactMap { id, items in
            guard let last = items.max(by: { $0.ts < $1.ts }) else { return nil }
            let title = items.compactMap(\.name).first ?? contactName(for: last.addr) ?? last.addr
            return ThreadRow(id: id, addr: last.addr, title: title, last: last, unread: items.filter { $0.incoming && !$0.read }.count)
        }.sorted { $0.last.ts > $1.last.ts }
    }

    func conversation(_ thread: String) -> [SMS] {
        allTexts.filter { $0.thread == thread }.sorted { $0.ts < $1.ts }
    }

    var callList: [Call] { calls.values.sorted { $0.ts > $1.ts } }

    /// The code card shows the newest code from the last 10 minutes.
    var freshCode: OneTimeCode? {
        guard let c = latestCode, Date().timeIntervalSince(c.ts) < 600 || isDemo else { return nil }
        return c
    }

    /// The thread a number already has, matched on its last 9 digits like contact names.
    func threadID(for addr: String) -> String? {
        allTexts.filter { Self.sameNumber($0.addr, addr) }.max { $0.ts < $1.ts }?.thread
    }

    private func storedThread(for addr: String) -> String? {
        messages.values.filter { Self.sameNumber($0.addr, addr) }.max { $0.ts < $1.ts }?.thread
    }

    /// Short (service) numbers match exactly; full numbers on their last 9 digits (0912… vs +98912…).
    /// Senders without digits ("MyBank") match on their name.
    static func sameNumber(_ a: String, _ b: String) -> Bool {
        let x = a.filter(\.isNumber), y = b.filter(\.isNumber)
        if y.isEmpty { return x.isEmpty && a.caseInsensitiveCompare(b) == .orderedSame }
        return y.count < 9 ? x == y : x.hasSuffix(y.suffix(9))
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
            if let s = SMS(m) { insert(s, notify: true); takeCode(m, sms: s); advance("sms", to: s.id) }
        case "sms.read":
            let ids = m["ids"] as? [String] ?? []
            for id in ids where messages[id]?.read == false { messages[id]?.read = true }
            Notify.clearTexts(ids)
        case "sms.gone":
            let ids = m["ids"] as? [String] ?? []
            ids.forEach { messages[$0] = nil }
            Notify.clearTexts(ids)
        case "otp":
            if let code = m.str("code"), let ts = m.date("ts") {
                latestCode = OneTimeCode(sms: m.str("sms") ?? "", code: code, from: m.str("from") ?? "", ts: ts)
            }
        case "call":
            if let c = Call(m) { addCall(c); advance("calls", to: c.id) }
        case "calls.gone":
            let ids = m["ids"] as? [String] ?? []
            ids.forEach { calls[$0] = nil }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids.map { "missed-\($0)" })
        case "call.ring":
            if let id = m.str("call"), let addr = m.str("addr") {
                ringing = (id, addr, m.str("name"))
                Notify.call(id: id, from: m.str("name") ?? contactName(for: addr) ?? addr, addr: addr)
            }
        case "call.end":
            guard let id = m.str("call") else { break }
            Notify.clear(id)
            if ringing?.id == id { ringing = nil } // a missed call is announced by its call-log row: `kind` tells missed from declined
        case "page":
            page(m)
        case "sent":
            let i = outbox.firstIndex { $0.req == m.str("req") }
            if m.bool("ok") == false {
                if let i { outbox[i].failed = true }
                alert = String(localized: "Your text wasn’t sent. Check the Android phone’s signal and try again.")
            } else if let i {
                outbox[i].sent = true // stays pending until Android's own `sms` row for it arrives
            }
        case "unpair":
            unpair()
        default:
            break
        }
        save()
        if !pendingRead.isEmpty { onOutbox?() }
    }

    private func insert(_ s: SMS, notify: Bool) {
        var s = s
        let old = messages[s.id]
        if old?.read == true { s.read = true } // read never turns back to unread (PROTOCOL.md "Read state")
        let onScreen = openThread == s.thread && UIApplication.shared.applicationState == .active
        if onScreen && s.incoming && !s.read { s.read = true; pendingRead.insert(s.id) } // sent once apply() is done
        messages[s.id] = s
        _ = tint(for: s.addr)
        // Android dates the row when it sends; 5 minutes of slack for the two phones' clocks keeps an older identical text out.
        if !s.incoming, let i = outbox.firstIndex(where: { $0.session != nil && s.ts > $0.ts.addingTimeInterval(-300) && Self.sameNumber($0.to, s.addr)
            && $0.body.trimmingCharacters(in: .whitespacesAndNewlines) == s.body.trimmingCharacters(in: .whitespacesAndNewlines) }) {
            outbox.remove(at: i) // Android's record of a text sent from here replaces the pending copy
        }
        if notify && old == nil && s.incoming && !s.read {
            Notify.text(id: s.id, from: s.name ?? contactName(for: s.addr) ?? s.addr, body: s.body)
        }
    }

    // MARK: outgoing

    /// Queues a text; the central sends it now when live, or as soon as the link is back.
    @discardableResult
    func enqueue(to: String, body: String, sim: Int?) -> String {
        let req = UUID().uuidString
        outbox.append(Outgoing(req: req, to: to, body: body, sim: sim, ts: Date()))
        save()
        onOutbox?()
        return req
    }

    /// Retry of a failed text: the failed copy goes, a new one is queued.
    func retry(_ m: SMS) {
        if m.id.hasPrefix("out-") { outbox.removeAll { "out-\($0.req)" == m.id } } else { messages[m.id] = nil }
        enqueue(to: m.addr, body: m.body, sim: m.sim > 0 ? m.sim : nil)
    }

    private func addCall(_ c: Call, notify: Bool = true) {
        let isNew = calls[c.id] == nil
        calls[c.id] = c
        if notify && isNew && c.kind == "missed" && Date().timeIntervalSince(c.ts) < 600 {
            Notify.missed(id: c.id, from: c.name ?? contactName(for: c.addr) ?? c.addr)
        }
    }

    /// The user is looking at this conversation: its incoming texts are read, here and (via the outbox) on Android.
    func markRead(thread: String) {
        let ids = messages.values.filter { $0.thread == thread && $0.incoming && !$0.read }.map(\.id)
        guard !ids.isEmpty else { return }
        ids.forEach { messages[$0]?.read = true }
        pendingRead.formUnion(ids)
        Notify.clearTexts(ids)
        save()
        onOutbox?()
    }

    /// Texts written to a link that dropped before Android answered, and still unknown to Android after the next
    /// full sms sync: shown as failed for the user to retry, never re-sent on their own (a duplicate is worse).
    private func failOrphans() {
        // One Android confirmed but whose row never came was sent all the same: it goes.
        outbox.removeAll { $0.session != nil && $0.session != session && $0.sent == true }
        for i in outbox.indices where outbox[i].session != nil && outbox[i].session != session && !outbox[i].failed {
            outbox[i].failed = true
        }
    }

    private func page(_ m: [String: Any]) {
        guard let kind = m.str("kind") else { return }
        let items = m["items"] as? [[String: Any]] ?? []
        switch kind {
        case "sms":
            for item in items {
                guard let s = SMS(item) else { continue }
                // Texts that arrived while the link was down: alert for the recent unread ones only.
                insert(s, notify: !s.read && Date().timeIntervalSince(s.ts) < 600)
                takeCode(item, sms: s)
            }
        case "calls": items.compactMap(Call.init).forEach { addCall($0) } // calls missed while away: recent ones alert
        case "contacts":
            let fresh = items.compactMap(Contact.init)
            if cursors["contacts"] == nil { contacts = fresh } else { contacts += fresh }
        default: return
        }
        cursors[kind] = m.str("cursor")
        if (m.bool("more") ?? false) { onMorePages?(kind, m.str("cursor")) } else {
            syncing.remove(kind)
            if kind == "contacts" { cursors["contacts"] = nil }
            if kind == "sms" { failOrphans() }
        }
    }

    /// Set by the central: asks Android for the next page.
    var onMorePages: ((String, String?) -> Void)?

    /// The code card takes the newest code from the last 10 minutes, live or synced.
    private func takeCode(_ d: [String: Any], sms: SMS) {
        guard sms.incoming, let code = d.str("code"), !code.isEmpty, Date().timeIntervalSince(sms.ts) < 600,
              latestCode.map({ $0.ts < sms.ts }) ?? true else { return }
        latestCode = OneTimeCode(sms: sms.id, code: code, from: sms.name ?? sms.addr, ts: sms.ts)
    }

    /// Moves a sync cursor forward past a live item, unless a paged sync of that kind is still running.
    private func advance(_ kind: String, to id: String) {
        guard !syncing.contains(kind), let n = Int64(id),
              let cursor = cursors[kind], cursor.hasPrefix("v2:"), let current = Int64(cursor.dropFirst(3)),
              n > current else { return } // no v2 cursor yet: the next full sync sets it
        cursors[kind] = "v2:\(n)"
    }

    /// A newly paired phone: drop what came from a different one.
    func adopt(_ p: PairedPeer) {
        peer = p
        if dataOwner != p.publicKey {
            messages = [:]; calls = [:]; contacts = []; cursors = [:]; latestCode = nil; tints = [:]; outbox = []; pendingRead = []
            dataOwner = p.publicKey
        }
        save()
    }

    func unpair() {
        cursors = [:]
        syncing = []
        outbox = [] // never send these through a phone paired later
        pendingRead = []
        peer = nil
        link = .unpaired
        status = nil
        PeerStore.save(nil)
    }

    // MARK: persistence

    private struct Saved: Codable {
        var messages: [SMS]; var calls: [Call]; var contacts: [Contact]; var code: OneTimeCode?; var tints: [String: Int]; var cursors: [String: String]
        var owner: Data?
        var outbox: [Outgoing]?
        var read: [String]?
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
        dataOwner = s.owner
        outbox = s.outbox ?? []
        pendingRead = Set(s.read ?? [])
    }

    func save() {
        guard !isDemo else { return }
        let s = Saved(messages: Array(messages.values), calls: Array(calls.values), contacts: contacts, code: latestCode, tints: tints, cursors: cursors, owner: dataOwner,
                      outbox: outbox, read: Array(pendingRead))
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

    static func call(id: String, from: String, addr: String) {
        let n = UNMutableNotificationContent()
        n.title = from
        n.body = String(localized: "Calling your Android phone")
        n.sound = .default
        n.categoryIdentifier = callCategory
        n.userInfo = ["call": id, "addr": addr]
        n.interruptionLevel = .timeSensitive
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "call-\(id)", content: n, trigger: nil))
    }

    static func clear(_ callId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["call-\(callId)"])
    }

    /// The ring alert goes when the call stops; a missed call leaves this one in its place.
    static func missed(id: String, from: String) {
        let n = UNMutableNotificationContent()
        n.title = from
        n.body = String(localized: "Missed call")
        n.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "missed-\(id)", content: n, trigger: nil))
    }

    /// Texts read or deleted (here or on Android) leave Notification Center.
    static func clearTexts(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids.map { "sms-\($0)" })
    }
}

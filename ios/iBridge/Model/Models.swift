import Foundation

/// Items from PROTOCOL.md section 6, as the iPhone stores them.
struct SMS: Codable, Identifiable, Equatable {
    var id: String
    var thread: String
    var addr: String
    var name: String?
    var body: String
    var ts: Date
    var dir: String          // "in" | "out"
    var read: Bool
    var sim: Int
    var state: String        // "ok" | "pending" | "failed"
    var incoming: Bool { dir == "in" }
}

struct Call: Codable, Identifiable, Equatable {
    var id: String
    var addr: String
    var name: String?
    var kind: String         // "in" | "out" | "missed" | "rejected"
    var ts: Date
    var dur: Int
    var sim: Int
    var missed: Bool { kind == "missed" || kind == "rejected" }
}

struct Contact: Codable, Identifiable, Equatable {
    struct Phone: Codable, Equatable { var n: String; var label: String }
    var id: String
    var name: String
    var phones: [Phone]
}

struct OneTimeCode: Codable, Equatable {
    var sms: String
    var code: String
    var from: String
    var ts: Date
}

struct PhoneStatus: Codable, Equatable {
    struct Sim: Codable, Equatable { var sim: Int; var carrier: String; var signal: Int }
    var battery: Int
    var charging: Bool
    var sims: [Sim]
    var perms: [String: Bool]
    var name: String
}

enum LinkState: Equatable {
    case unpaired
    case connecting
    case linked
    case outOfReach(since: Date)
}

/// A conversation row on the Messages tab.
struct ThreadRow: Identifiable, Equatable {
    var id: String
    var addr: String
    var title: String
    var last: SMS
    var unread: Int
}

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String? { self[k] as? String }
    func int(_ k: String) -> Int? { (self[k] as? NSNumber)?.intValue }
    func bool(_ k: String) -> Bool? { (self[k] as? NSNumber)?.boolValue }
    func date(_ k: String) -> Date? { (self[k] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } }
}

extension SMS {
    init?(_ d: [String: Any]) {
        guard let id = d.str("id"), let addr = d.str("addr"), let ts = d.date("ts") else { return nil }
        self.init(id: id, thread: d.str("thread") ?? addr, addr: addr, name: d.str("name"), body: d.str("body") ?? "",
                  ts: ts, dir: d.str("dir") ?? "in", read: d.bool("read") ?? false, sim: d.int("sim") ?? 1, state: d.str("state") ?? "ok")
    }
}

extension Call {
    init?(_ d: [String: Any]) {
        guard let id = d.str("id"), let ts = d.date("ts") else { return nil }
        self.init(id: id, addr: d.str("addr") ?? "", name: d.str("name"), kind: d.str("kind") ?? "in", ts: ts, dur: d.int("dur") ?? 0, sim: d.int("sim") ?? 1)
    }
}

extension Contact {
    init?(_ d: [String: Any]) {
        guard let id = d.str("id") else { return nil }
        let phones = (d["phones"] as? [[String: Any]] ?? []).map { Phone(n: $0.str("n") ?? "", label: $0.str("label") ?? "") }
        self.init(id: id, name: d.str("name") ?? "", phones: phones)
    }
}

extension PhoneStatus {
    init(_ d: [String: Any]) {
        let sims = (d["sims"] as? [[String: Any]] ?? []).map { Sim(sim: $0.int("sim") ?? 1, carrier: $0.str("carrier") ?? "", signal: $0.int("signal") ?? 0) }
        let perms = (d["perms"] as? [String: Any] ?? [:]).compactMapValues { ($0 as? NSNumber)?.boolValue }
        self.init(battery: d.int("battery") ?? -1, charging: d.bool("charging") ?? false, sims: sims, perms: perms, name: d.str("name") ?? "")
    }
}

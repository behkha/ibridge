import CryptoKit
import Foundation

/// iPhone side of the interop check: the app's real Handshake, Framer and Deframer over stdin/stdout.
struct MemoryKey: StaticKey {
    let key = P256.KeyAgreement.PrivateKey()
    var publicKey: P256.KeyAgreement.PublicKey { key.publicKey }
    func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { Crypto.raw(try key.sharedSecretFromKeyAgreement(with: peer)) }
}

func log(_ s: String) { FileHandle.standardError.write(("IOS " + s + "\n").data(using: .utf8)!) }

let args = CommandLine.arguments
if args[1] == "parse" {
    guard case .qr(let key, let token, let name)? = PairingStart.parse(args[2]) else { log("ERROR unparseable"); exit(2) }
    log("parsed key \(key.count) bytes, token \(token.count) bytes, name \(name)"); exit(0)
}
let start: PairingStart = args[1] == "qr" ? PairingStart.parse(args[2])! : .code(args[2])
let h = Handshake(identity: MemoryKey(), myName: "iPhone 15")
var framer = Framer()
var deframer = Deframer()
let out = FileHandle.standardOutput
let input = FileHandle.standardInput

func send(_ packet: Data) {
    for c in framer.split(packet, chunkSize: 20) {
        out.write(Data([UInt8(c.count >> 8), UInt8(c.count & 0xFF)]) + c)
    }
}

func readExactly(_ n: Int) -> Data? {
    var d = Data()
    while d.count < n {
        let part = input.readData(ofLength: n - d.count)
        if part.isEmpty { return nil }
        d += part
    }
    return d
}

func handle(_ events: [Handshake.Event]) {
    for e in events {
        switch e {
        case .send(let p): send(p)
        case .paired(let peer): log("paired with \(peer.name)")
        case .live:
            log("live")
            send(try! h.seal(["t": "sync", "kind": "sms", "cursor": NSNull(), "limit": 200])!)
        case .message(let m):
            log("got \(m["t"] ?? "?")")
            if m["t"] as? String == "page" {
                let items = m["items"] as? [[String: Any]] ?? []
                guard items.count == 40 else { log("ERROR page had \(items.count) items"); exit(2) }
                send(try! h.seal(["t": "ping"])!)
            }
            if m["t"] as? String == "pong" { log("OK"); exit(0) }
        }
    }
}

do {
    handle(try h.startPairing(start))
    while let len = readExactly(2) {
        let n = Int(len[0]) << 8 | Int(len[1])
        guard let chunk = readExactly(n) else { break }
        if let packet = try deframer.feed(chunk) { handle(try h.receive(packet)) }
    }
    log("ERROR closed early")
    exit(2)
} catch {
    log("ERROR \(error)")
    exit(2)
}

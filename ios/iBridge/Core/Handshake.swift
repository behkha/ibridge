import CryptoKit
import Foundation

/// What the iPhone needs to start pairing: from the QR, or from a typed code.
enum PairingStart: Equatable {
    case qr(androidKey: Data, token: Data, name: String)
    case code(String)

    /// Parses `ibridge://pair?v=1&k=…&t=…&n=…` (PROTOCOL.md 4.1).
    static func parse(_ uri: String) -> PairingStart? {
        guard let c = URLComponents(string: uri), c.scheme == "ibridge", c.host == "pair" else { return nil }
        let q = Dictionary((c.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        guard q["v"] == "1", let k = q["k"].flatMap(base64url), let t = q["t"].flatMap(base64url), t.count == 16,
              let key = try? P256.KeyAgreement.PublicKey(compressedRepresentation: k) else { return nil }
        return .qr(androidKey: key.x963Representation, token: t, name: q["n"] ?? "Android")
    }

    /// Normalizes typed input: uppercase, no spaces, Crockford look-alikes mapped. Nil unless 6 valid characters.
    static func normalizeCode(_ s: String) -> String? {
        let mapped = s.uppercased().compactMap { ch -> Character? in
            switch ch {
            case " ", "-": return nil
            case "O": return "0"
            case "I", "L": return "1"
            default: return ch
            }
        }
        guard mapped.count == 6, mapped.allSatisfy({ Proto.codeAlphabet.contains($0) }) else { return nil }
        return String(mapped)
    }

    private static func base64url(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }
}

struct PairedPeer: Codable, Equatable {
    var publicKey: Data
    var name: String
}

/// The iPhone side of PROTOCOL.md sections 4 and 5, for one connection.
/// Feed it packets; it returns packets to send. It never touches Bluetooth.
final class Handshake {
    enum Event {
        case send(Data)
        case paired(PairedPeer)
        case live
        case message([String: Any])
        /// The plaintext of an `AUDIO` record: `stream(1) || seq(2) || opus` (PROTOCOL.md 8).
        case audio(Data)
    }

    private enum Step {
        case idle
        case awaitHello(code: String)
        case codeRounds(CodeState)
        case awaitAccept(peer: PairedPeer, kPair: Data)
        case awaitSession(ei: P256.KeyAgreement.PrivateKey, peer: PairedPeer)
        case live(RecordCipher)
    }

    private final class CodeState {
        let aPub: Data, name: String, bits: Int, ei: P256.KeyAgreement.PrivateKey
        var round = 0
        var myNonce = Data()
        var theirCommit: Data?
        var transcript: [Data] = []
        init(aPub: Data, name: String, bits: Int, ei: P256.KeyAgreement.PrivateKey) {
            self.aPub = aPub; self.name = name; self.bits = bits; self.ei = ei
        }
    }

    private var step: Step = .idle
    private var confirmed = false
    private let identity: StaticKey
    private let myName: String
    private var iPub: Data { identity.publicKey.x963Representation }

    init(identity: StaticKey, myName: String) {
        self.identity = identity
        self.myName = myName
    }

    /// Records can be sealed once keys exist; `.live` is reported only after Android's first record decrypts.
    var isLive: Bool { if case .live = step { return true } else { return false } }

    // MARK: start

    func startPairing(_ start: PairingStart) throws -> [Event] {
        switch start {
        case .qr(let aPub, let token, let name):
            let ei = P256.KeyAgreement.PrivateKey()
            let eiPub = ei.publicKey.x963Representation
            let a = try P256.KeyAgreement.PublicKey(x963Representation: aPub)
            let kPair = Crypto.hkdf(ikm: Crypto.raw(try ei.sharedSecretFromKeyAgreement(with: a)), salt: token,
                                    info: pairInfo(aPub: aPub, eiPub: eiPub), length: 32)
            step = .awaitAccept(peer: PairedPeer(publicKey: aPub, name: name), kPair: kPair)
            return [
                .send(Data([Proto.pairRequest, Proto.modeQR]) + iPub + eiPub + Data(myName.utf8)),
                .send(Data([Proto.pairProof]) + Crypto.hmac(kPair, Data(ascii: "I"))),
            ]
        case .code(let code):
            step = .awaitHello(code: code)
            return [.send(Data([Proto.pairHelloReq]))]
        }
    }

    func startSession(peer: PairedPeer) -> [Event] {
        let ei = P256.KeyAgreement.PrivateKey()
        step = .awaitSession(ei: ei, peer: peer)
        let keyId = Crypto.sha256(iPub).prefix(8)
        return [.send(Data([Proto.sessionHello]) + keyId + ei.publicKey.x963Representation)]
    }

    func seal(_ message: [String: Any]) throws -> Data? {
        guard case .live(var c) = step else { return nil }
        let out = try c.seal(try JSONSerialization.data(withJSONObject: message))
        step = .live(c)
        return out
    }

    /// Seals one `AUDIO` record; nil until the session has keys.
    func sealAudio(_ plain: Data) throws -> Data? {
        guard case .live(var c) = step else { return nil }
        let out = try c.sealAudio(plain)
        step = .live(c)
        return out
    }

    // MARK: receive

    func receive(_ p: Data) throws -> [Event] {
        guard let type = p.first else { throw LinkError.badPacket("empty") }
        let body = Data(p.dropFirst())
        switch (type, step) {
        case (Proto.error, _):
            throw LinkError(code: body.first ?? 6, reason: String(decoding: body.dropFirst(), as: UTF8.self))

        case (Proto.pairHello, .awaitHello(let code)):
            guard body.count >= 65 else { throw LinkError.badPacket("short PAIR_HELLO") }
            let aPub = Data(body.prefix(65))
            _ = try P256.KeyAgreement.PublicKey(x963Representation: aPub)
            let name = String(decoding: body.dropFirst(65), as: UTF8.self)
            let bits = code.reduce(0) { ($0 << 5) | Proto.codeAlphabet.firstIndex(of: $1)! }
            let s = CodeState(aPub: aPub, name: name.isEmpty ? "Android" : name, bits: bits, ei: P256.KeyAgreement.PrivateKey())
            step = .codeRounds(s)
            let eiPub = s.ei.publicKey.x963Representation
            return [.send(Data([Proto.pairRequest, Proto.modeCode]) + iPub + eiPub + Data(myName.utf8)), commitRound(s)]

        // Round r: I commits, A commits and reveals, then I reveals (PROTOCOL.md 4.3). The iPhone checks
        // Android's bits before it reveals anything, so a fake Android learns nothing from a failed try.
        case (Proto.passCommit, .codeRounds(let s)):
            guard body.count == 33, Int(body[body.startIndex]) == s.round, s.theirCommit == nil else { throw LinkError.badPacket("bad PASS_COMMIT") }
            s.theirCommit = Data(body.dropFirst())
            return []

        case (Proto.passReveal, .codeRounds(let s)):
            guard body.count == 17, Int(body[body.startIndex]) == s.round, let theirs = s.theirCommit else { throw LinkError.badPacket("bad PASS_REVEAL") }
            let na = Data(body.dropFirst())
            guard Crypto.equal(theirs, commit(nonce: na, side: "A", s: s, round: s.round)) else { throw LinkError.codeMismatch }
            let reveal = Event.send(Data([Proto.passReveal, UInt8(s.round)]) + s.myNonce)
            s.transcript += [s.myNonce, na]
            s.theirCommit = nil
            s.round += 1
            if s.round < Proto.codeRounds { return [reveal, commitRound(s)] }
            let a = try P256.KeyAgreement.PublicKey(x963Representation: s.aPub)
            let kPair = Crypto.hkdf(ikm: Crypto.raw(try s.ei.sharedSecretFromKeyAgreement(with: a)),
                                    salt: Crypto.sha256(s.transcript.reduce(Data(), +)),
                                    info: pairInfo(aPub: s.aPub, eiPub: s.ei.publicKey.x963Representation), length: 32)
            step = .awaitAccept(peer: PairedPeer(publicKey: s.aPub, name: s.name), kPair: kPair)
            return [reveal, .send(Data([Proto.pairProof]) + Crypto.hmac(kPair, Data(ascii: "I")))]

        case (Proto.pairAccept, .awaitAccept(var peer, let kPair)):
            guard body.count >= 32, Crypto.equal(Data(body.prefix(32)), Crypto.hmac(kPair, Data(ascii: "A"))) else { throw LinkError.badProof }
            let name = String(decoding: body.dropFirst(32), as: UTF8.self)
            if !name.isEmpty { peer.name = name }
            return [.paired(peer)] + startSession(peer: peer)

        case (Proto.sessionAccept, .awaitSession(let ei, let peer)):
            guard body.count == 65 else { throw LinkError.badPacket("bad SESSION_ACCEPT") }
            let eaPub = Data(body)
            let ea = try P256.KeyAgreement.PublicKey(x963Representation: eaPub)
            let a = try P256.KeyAgreement.PublicKey(x963Representation: peer.publicKey)
            let eiPub = ei.publicKey.x963Representation
            let ikm = Crypto.raw(try ei.sharedSecretFromKeyAgreement(with: ea))
                + Crypto.raw(try ei.sharedSecretFromKeyAgreement(with: a))
                + (try identity.agree(ea))
            let salt = Crypto.sha256(Data(ascii: "ibridge-session-v1"), peer.publicKey, iPub, eiPub, eaPub)
            let okm = Crypto.hkdf(ikm: ikm, salt: salt, info: Data(ascii: "ibridge-app-v1"), length: 64)
            var cipher = RecordCipher(sendKey: okm.prefix(32), recvKey: okm.suffix(32))
            let hello: [String: Any] = ["t": "hello", "app": "ios", "ver": 1, "lang": Locale.current.language.languageCode?.identifier ?? "en"]
            let first = try cipher.seal(try JSONSerialization.data(withJSONObject: hello))
            step = .live(cipher)
            confirmed = false
            return [.send(first)]

        case (Proto.data, .live(var c)):
            let plain = try c.open(p)
            step = .live(c)
            // Android's first record proves it derived the same keys; only then is the link live.
            let live: [Event] = confirmed ? [] : [.live]
            confirmed = true
            guard let obj = try JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return live }
            return live + [.message(obj)]

        case (Proto.audio, .live(var c)):
            let plain = try c.openAudio(p)
            step = .live(c)
            return [.audio(plain)]

        default:
            throw LinkError.badPacket("unexpected packet \(type)")
        }
    }

    // MARK: helpers

    private func pairInfo(aPub: Data, eiPub: Data) -> Data { Data(ascii: "ibridge-pair-v1") + aPub + iPub + eiPub }

    private func roundBits(_ bits: Int, _ r: Int) -> UInt8 { UInt8((bits >> (6 * (Proto.codeRounds - 1 - r))) & 0x3F) }

    private func commit(nonce: Data, side: String, s: CodeState, round r: Int) -> Data {
        Crypto.hmac(nonce, Data(ascii: "ibridge-pass-v1"), Data(ascii: side), s.aPub, iPub, s.ei.publicKey.x963Representation,
                    Data([UInt8(r), roundBits(s.bits, r)]))
    }

    private func commitRound(_ s: CodeState) -> Event {
        s.myNonce = Data.random(16)
        return .send(Data([Proto.passCommit, UInt8(s.round)]) + commit(nonce: s.myNonce, side: "I", s: s, round: s.round))
    }
}

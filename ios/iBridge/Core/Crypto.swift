import CryptoKit
import Foundation
import Security

extension Data {
    init(ascii s: String) { self = s.data(using: .ascii)! }

    static func random(_ n: Int) -> Data {
        var d = Data(count: n)
        _ = d.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, n, $0.baseAddress!) }
        return d
    }

    func bigEndian(_ v: UInt64) -> Data { self + Swift.withUnsafeBytes(of: v.bigEndian) { Data($0) } }
}

enum Crypto {
    static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        parts.forEach { h.update(data: $0) }
        return Data(h.finalize())
    }

    static func hmac(_ key: Data, _ parts: Data...) -> Data {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        parts.forEach { h.update(data: $0) }
        return Data(h.finalize())
    }

    static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: length)
            .withUnsafeBytes { Data($0) }
    }

    static func equal(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    static func raw(_ s: SharedSecret) -> Data { s.withUnsafeBytes { Data($0) } }
}

/// The iPhone's static key `I`: Secure Enclave on a device, a Keychain software key on the Simulator.
protocol StaticKey {
    var publicKey: P256.KeyAgreement.PublicKey { get }
    func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data
}

private struct EnclaveKey: StaticKey {
    let key: SecureEnclave.P256.KeyAgreement.PrivateKey
    var publicKey: P256.KeyAgreement.PublicKey { key.publicKey }
    func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { Crypto.raw(try key.sharedSecretFromKeyAgreement(with: peer)) }
}

private struct SoftwareKey: StaticKey {
    let key: P256.KeyAgreement.PrivateKey
    var publicKey: P256.KeyAgreement.PublicKey { key.publicKey }
    func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { Crypto.raw(try key.sharedSecretFromKeyAgreement(with: peer)) }
}

enum Identity {
    private static let account = "ibridge-i-v1"

    static let shared: StaticKey = load() ?? create()

    private static func load() -> StaticKey? {
        guard let blob = Keychain.read(account) else { return nil }
        if blob.first == 1, SecureEnclave.isAvailable,
           let k = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: blob.dropFirst()) {
            return EnclaveKey(key: k)
        }
        if blob.first == 0, let k = try? P256.KeyAgreement.PrivateKey(rawRepresentation: blob.dropFirst()) {
            return SoftwareKey(key: k)
        }
        return nil
    }

    private static func create() -> StaticKey {
        if SecureEnclave.isAvailable, let k = try? SecureEnclave.P256.KeyAgreement.PrivateKey() {
            Keychain.write(account, Data([1]) + k.dataRepresentation)
            return EnclaveKey(key: k)
        }
        let k = P256.KeyAgreement.PrivateKey()
        Keychain.write(account, Data([0]) + k.rawRepresentation)
        return SoftwareKey(key: k)
    }

    static func reset() { Keychain.delete(account) }
}

enum Keychain {
    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.ibridge", kSecAttrAccount as String: account]
    }

    static func read(_ account: String) -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: CFTypeRef?
        return SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }

    static func write(_ account: String, _ data: Data) {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}

/// AES-256-GCM records (PROTOCOL.md 5.1): `DATA` with strict per-direction counters, and `AUDIO` with counters
/// of its own that may skip ahead (a sender drops frames before sealing them), so audio can pass queued `DATA`.
struct RecordCipher {
    let sendKey: SymmetricKey
    let recvKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0
    private var audioSendCounter: UInt64 = 0
    /// The lowest `AUDIO` counter still accepted: one past the last one accepted.
    private var audioRecvNext: UInt64 = 0

    init(sendKey: Data, recvKey: Data) {
        self.sendKey = SymmetricKey(data: sendKey)
        self.recvKey = SymmetricKey(data: recvKey)
    }

    private static func nonce(_ dir: String, _ counter: UInt64) -> AES.GCM.Nonce {
        try! AES.GCM.Nonce(data: (Data(ascii: dir) + Data([0])).bigEndian(counter))
    }

    private static func seal(_ plain: Data, type: UInt8, counter: UInt64, key: SymmetricKey, dir: String) throws -> Data {
        guard counter < (1 << 32) else { throw LinkError.badPacket("rekey needed") }
        let header = Data([type]).bigEndian(counter)
        let box = try AES.GCM.seal(plain, using: key, nonce: nonce(dir, counter), authenticating: header)
        return header + box.ciphertext + box.tag
    }

    /// The record's counter and plaintext. A counter `accept` refuses, or a bad tag, throws.
    private static func open(_ packet: Data, key: SymmetricKey, dir: String, accept: (UInt64) -> Bool) throws -> (UInt64, Data) {
        let p = [UInt8](packet)
        guard p.count >= 1 + 8 + 16 else { throw LinkError.badPacket("short record") }
        let counter = p[1..<9].reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
        guard accept(counter) else { throw LinkError.badPacket("record type \(p[0]) counter \(counter) refused") }
        let box = try AES.GCM.SealedBox(nonce: nonce(dir, counter), ciphertext: Data(p[9..<(p.count - 16)]), tag: Data(p[(p.count - 16)...]))
        return (counter, try AES.GCM.open(box, using: key, authenticating: Data(p[0..<9])))
    }

    mutating func seal(_ plain: Data) throws -> Data {
        let counter = sendCounter
        sendCounter += 1
        return try Self.seal(plain, type: Proto.data, counter: counter, key: sendKey, dir: "i2a")
    }

    /// Any replay, gap or bad tag throws, which ends the session.
    mutating func open(_ packet: Data) throws -> Data {
        let expected = recvCounter
        let (_, plain) = try Self.open(packet, key: recvKey, dir: "a2i") { $0 == expected }
        recvCounter += 1
        return plain
    }

    /// One `AUDIO` record (`0x21 || counter || ciphertext || tag`) with the iPhone's own audio counter.
    mutating func sealAudio(_ plain: Data) throws -> Data {
        let counter = audioSendCounter
        audioSendCounter += 1
        return try Self.seal(plain, type: Proto.audio, counter: counter, key: sendKey, dir: "I2A")
    }

    /// Accepts any counter above the last one accepted. A repeat, an older counter or a bad tag throws, which ends the session.
    mutating func openAudio(_ packet: Data) throws -> Data {
        let next = audioRecvNext
        let (counter, plain) = try Self.open(packet, key: recvKey, dir: "A2I") { $0 >= next }
        audioRecvNext = counter + 1
        return plain
    }
}

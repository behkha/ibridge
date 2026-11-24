import CoreBluetooth
import Foundation

/// Constants from docs/PROTOCOL.md. Keep the two in step.
enum Proto {
    static let service = CBUUID(string: "e38d0001-747f-44a1-b7fc-4a024772eb87")
    static let rx = CBUUID(string: "e38d0002-747f-44a1-b7fc-4a024772eb87")
    static let tx = CBUUID(string: "e38d0003-747f-44a1-b7fc-4a024772eb87")

    static let maxPacket = 65_535

    static let pairHelloReq: UInt8 = 0x01
    static let pairHello: UInt8 = 0x02
    static let pairRequest: UInt8 = 0x03
    static let passCommit: UInt8 = 0x04
    static let passReveal: UInt8 = 0x05
    static let pairProof: UInt8 = 0x06
    static let pairAccept: UInt8 = 0x07
    static let sessionHello: UInt8 = 0x10
    static let sessionAccept: UInt8 = 0x11
    static let data: UInt8 = 0x20
    static let error: UInt8 = 0x7F

    static let modeQR: UInt8 = 0x01
    static let modeCode: UInt8 = 0x02
    static let codeRounds = 5
    static let codeAlphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
}

enum LinkError: Error, Equatable {
    case notPaired, pairingClosed, badProof, tooManyAttempts, busy, badPacket(String), version
    case codeMismatch

    init(code: UInt8, reason: String) {
        switch code {
        case 1: self = .notPaired
        case 2: self = .pairingClosed
        case 3: self = .badProof
        case 4: self = .tooManyAttempts
        case 5: self = .busy
        case 7: self = .version
        default: self = .badPacket(reason)
        }
    }
}

/// Splits packets into GATT-sized chunks: header(1) = FIRST | LAST | SEQ(6 bits).
struct Framer {
    private var seq: UInt8 = 0

    mutating func split(_ packet: Data, chunkSize: Int) -> [Data] {
        let room = max(chunkSize - 1, 1)
        var out: [Data] = []
        var off = 0
        let bytes = [UInt8](packet)
        repeat {
            let n = min(room, bytes.count - off)
            var h = seq & 0x3F
            if off == 0 { h |= 0x80 }
            if off + n == bytes.count { h |= 0x40 }
            out.append(Data([h] + bytes[off..<off + n]))
            seq = (seq + 1) & 0x3F
            off += n
        } while off < bytes.count
        return out
    }
}

/// Reassembles chunks. Throws on any framing violation; the caller drops the connection.
struct Deframer {
    private var buf: Data?
    private var expected: Int = -1

    mutating func feed(_ chunk: Data) throws -> Data? {
        guard let h = chunk.first else { throw LinkError.badPacket("empty chunk") }
        let seq = Int(h & 0x3F)
        if expected >= 0 && seq != expected { throw LinkError.badPacket("chunk gap") }
        expected = (seq + 1) & 0x3F
        if h & 0x80 != 0 {
            if buf != nil { throw LinkError.badPacket("FIRST inside packet") }
            buf = Data()
        }
        guard buf != nil else { throw LinkError.badPacket("chunk without FIRST") }
        buf!.append(chunk.dropFirst())
        if buf!.count > Proto.maxPacket { throw LinkError.badPacket("packet too large") }
        guard h & 0x40 != 0 else { return nil }
        defer { buf = nil }
        return buf
    }
}

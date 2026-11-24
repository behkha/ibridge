package app.ibridge.core

import java.io.ByteArrayOutputStream
import java.util.UUID

/** Constants from docs/PROTOCOL.md. Keep the two in step. */
object Proto {
    val SERVICE: UUID = UUID.fromString("e38d0001-747f-44a1-b7fc-4a024772eb87")
    val RX: UUID = UUID.fromString("e38d0002-747f-44a1-b7fc-4a024772eb87")
    val TX: UUID = UUID.fromString("e38d0003-747f-44a1-b7fc-4a024772eb87")
    val DOORBELL: UUID = UUID.fromString("e38d0004-747f-44a1-b7fc-4a024772eb87")
    val WAKE: UUID = UUID.fromString("e38d0005-747f-44a1-b7fc-4a024772eb87")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAX_PACKET = 65_535

    const val PAIR_HELLO_REQ: Byte = 0x01
    const val PAIR_HELLO: Byte = 0x02
    const val PAIR_REQUEST: Byte = 0x03
    const val PASS_COMMIT: Byte = 0x04
    const val PASS_REVEAL: Byte = 0x05
    const val PAIR_PROOF: Byte = 0x06
    const val PAIR_ACCEPT: Byte = 0x07
    const val SESSION_HELLO: Byte = 0x10
    const val SESSION_ACCEPT: Byte = 0x11
    const val DATA: Byte = 0x20
    const val ERROR: Byte = 0x7F

    const val MODE_QR: Byte = 0x01
    const val MODE_CODE: Byte = 0x02
    const val CODE_ROUNDS = 5
}

enum class ErrorCode(val code: Int) {
    NOT_PAIRED(1), PAIRING_CLOSED(2), BAD_PROOF(3), TOO_MANY_ATTEMPTS(4), BUSY(5), BAD_PACKET(6), VERSION(7)
}

class ProtocolException(val error: ErrorCode, message: String) : Exception(message)

/** Splits packets into GATT-sized chunks: header(1) = FIRST | LAST | SEQ(6 bits). */
class Framer {
    private var seq = 0

    fun split(packet: ByteArray, chunkSize: Int): List<ByteArray> {
        val room = (chunkSize - 1).coerceAtLeast(1)
        val out = ArrayList<ByteArray>()
        var off = 0
        do {
            val n = minOf(room, packet.size - off)
            var h = seq and 0x3F
            if (off == 0) h = h or 0x80
            if (off + n == packet.size) h = h or 0x40
            out += byteArrayOf(h.toByte()) + packet.copyOfRange(off, off + n)
            seq = (seq + 1) and 0x3F
            off += n
        } while (off < packet.size)
        return out
    }
}

/** Reassembles chunks. Throws on any framing violation; the caller drops the connection. */
class Deframer {
    private var buf: ByteArrayOutputStream? = null
    private var expected = -1

    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) throw ProtocolException(ErrorCode.BAD_PACKET, "empty chunk")
        val h = chunk[0].toInt() and 0xFF
        val seq = h and 0x3F
        if (expected >= 0 && seq != expected) throw ProtocolException(ErrorCode.BAD_PACKET, "chunk gap")
        expected = (seq + 1) and 0x3F
        val first = h and 0x80 != 0
        val last = h and 0x40 != 0
        if (first) {
            if (buf != null) throw ProtocolException(ErrorCode.BAD_PACKET, "FIRST inside packet")
            buf = ByteArrayOutputStream()
        }
        val b = buf ?: throw ProtocolException(ErrorCode.BAD_PACKET, "chunk without FIRST")
        b.write(chunk, 1, chunk.size - 1)
        if (b.size() > Proto.MAX_PACKET) throw ProtocolException(ErrorCode.BAD_PACKET, "packet too large")
        if (!last) return null
        buf = null
        return b.toByteArray()
    }
}

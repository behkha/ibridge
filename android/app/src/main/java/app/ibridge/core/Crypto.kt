package app.ibridge.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

val random = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

/** RFC 5869. */
fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmac(salt, ikm)
    val out = ByteArray(length)
    var t = ByteArray(0)
    var off = 0
    var i = 1
    while (off < length) {
        t = hmac(prk, t, info, byteArrayOf(i.toByte()))
        val n = minOf(t.size, length - off)
        System.arraycopy(t, 0, out, off, n)
        off += n
        i++
    }
    return out
}

fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

object P256 {
    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }
    private val p: BigInteger by lazy { (params.curve.field as java.security.spec.ECFieldFp).p }

    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** 65-byte uncompressed X9.63. */
    fun encode(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)
    }

    /** 33-byte compressed form, used in the QR. */
    fun compressed(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(if (w.affineY.testBit(0)) 3 else 2) + fixed32(w.affineX)
    }

    /** Parses and validates an uncompressed point (rejects off-curve keys). */
    fun decode(b: ByteArray): PublicKey {
        if (b.size != 65 || b[0] != 4.toByte()) throw ProtocolException(ErrorCode.BAD_PACKET, "bad key encoding")
        val x = BigInteger(1, b.copyOfRange(1, 33))
        val y = BigInteger(1, b.copyOfRange(33, 65))
        if (x >= p || y >= p) throw ProtocolException(ErrorCode.BAD_PACKET, "key out of range")
        val a = params.curve.a
        val bb = params.curve.b
        val lhs = y.modPow(BigInteger.TWO, p)
        val rhs = x.modPow(BigInteger.valueOf(3), p).add(a.multiply(x)).add(bb).mod(p)
        if (lhs != rhs) throw ProtocolException(ErrorCode.BAD_PACKET, "key not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun ecdh(priv: PrivateKey, peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(priv)
        doPhase(peer, true)
        generateSecret()
    }
}

/** A static P-256 key whose private half may live in hardware. */
interface StaticKey {
    val publicKey: PublicKey
    val encoded: ByteArray get() = P256.encode(publicKey)
    fun agree(peer: PublicKey): ByteArray
}

/** A key held in memory. For tools and checks off the phone. */
class SoftwareKey(private val pair: KeyPair = P256.ephemeral()) : StaticKey {
    override val publicKey: PublicKey get() = pair.public
    override fun agree(peer: PublicKey) = P256.ecdh(pair.private, peer)
}

/** AES-256-GCM records with strict per-direction counters (PROTOCOL.md 5.1). */
class RecordCipher(private val sendKey: ByteArray, private val recvKey: ByteArray) {
    private var sendCounter = 0L
    private var recvCounter = 0L

    private fun nonce(dir: String, counter: Long) =
        ascii(dir) + byteArrayOf(0) + ByteBuffer.allocate(8).putLong(counter).array()

    fun seal(plain: ByteArray): ByteArray {
        if (sendCounter == 1L shl 32) throw ProtocolException(ErrorCode.BAD_PACKET, "rekey needed")
        val counter = sendCounter++
        val header = byteArrayOf(Proto.DATA) + ByteBuffer.allocate(8).putLong(counter).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"), GCMParameterSpec(128, nonce("a2i", counter)))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    /** Returns the plaintext, or throws: any replay, gap or bad tag ends the session. */
    fun open(packet: ByteArray): ByteArray {
        if (packet.size < 1 + 8 + 16) throw ProtocolException(ErrorCode.BAD_PACKET, "short record")
        val counter = ByteBuffer.wrap(packet, 1, 8).long
        if (counter != recvCounter) throw ProtocolException(ErrorCode.BAD_PACKET, "counter $counter, expected $recvCounter")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(recvKey, "AES"), GCMParameterSpec(128, nonce("i2a", counter)))
        c.updateAAD(packet, 0, 9)
        val plain = try {
            c.doFinal(packet, 9, packet.size - 9)
        } catch (e: Exception) {
            throw ProtocolException(ErrorCode.BAD_PACKET, "bad tag")
        }
        recvCounter++
        return plain
    }
}

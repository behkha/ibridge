package app.ibridge.core

import org.json.JSONObject
import java.security.KeyPair
import java.security.PublicKey

/**
 * The Android side of PROTOCOL.md sections 4 and 5, for one connection.
 * Transport-agnostic: feed it whole packets, it answers through [send].
 * Any [ProtocolException] it throws means: send ERROR (if the caller wants) and drop the connection.
 */
/** Where the engine keeps the one paired iPhone. The app uses [Link]; checks use memory. */
interface PeerStore {
    val peer: Peer?
    fun save(peer: Peer)
}

class LinkEngine(
    private val deviceName: String,
    private val identity: StaticKey,
    private val peers: PeerStore,
    private val log: (String) -> Unit,
    private val send: (ByteArray) -> Unit,
    private val onLive: () -> Unit,
    private val onMessage: (JSONObject) -> Unit,
    /** The plaintext of each `AUDIO` record (PROTOCOL.md 8): stream(1) || seq(2) || opus. */
    private val onAudio: (ByteArray) -> Unit = {},
) {
    private sealed interface Step
    private data object Idle : Step
    private class CodeRounds(
        val iPub: ByteArray, val eiPub: PublicKey, val eiRaw: ByteArray, val name: String,
        val bits: Int, var round: Int = 0,
        var theirCommit: ByteArray? = null, var myNonce: ByteArray? = null,
        val transcript: MutableList<ByteArray> = mutableListOf(),
    ) : Step
    private class AwaitProof(val iPub: ByteArray, val name: String, val kPair: ByteArray, val viaCode: Boolean) : Step
    private class AwaitHello(val cipher: RecordCipher) : Step
    private class Live(val cipher: RecordCipher) : Step

    private var step: Step = Idle

    val isLive get() = step is Live

    fun onPacket(p: ByteArray) {
        if (p.isEmpty()) throw ProtocolException(ErrorCode.BAD_PACKET, "empty packet")
        val body = p.copyOfRange(1, p.size)
        when (p[0]) {
            Proto.PAIR_HELLO_REQ -> {
                openWindow()
                send(byteArrayOf(Proto.PAIR_HELLO) + identity.encoded + deviceName.toByteArray())
            }
            Proto.PAIR_REQUEST -> pairRequest(body)
            Proto.PASS_COMMIT -> passCommit(body)
            Proto.PASS_REVEAL -> passReveal(body)
            Proto.PAIR_PROOF -> pairProof(body)
            Proto.SESSION_HELLO -> sessionHello(body)
            Proto.DATA -> data(p)
            Proto.AUDIO -> {
                val live = step as? Live ?: throw ProtocolException(ErrorCode.BAD_PACKET, "AUDIO before session")
                onAudio(live.cipher.openAudio(p))
            }
            Proto.ERROR -> throw ProtocolException(ErrorCode.BAD_PACKET, "peer error ${body.firstOrNull()}")
            else -> throw ProtocolException(ErrorCode.BAD_PACKET, "unknown type ${p[0]}")
        }
    }

    /**
     * Call when the connection ends. A typed-code attempt that ends unfinished after Android revealed
     * a round counts as a failure and replaces the code: otherwise a fake iPhone could learn one round
     * per connection by hanging up.
     */
    fun close() {
        val s = step
        val exposed = (s is CodeRounds && (s.round > 0 || s.myNonce != null)) || (s is AwaitProof && s.viaCode)
        step = Idle
        if (exposed) Pairing.codeFailed()
    }

    /** Encrypts and sends one JSON message. False when no live session exists. */
    fun sendMessage(msg: JSONObject): Boolean {
        val live = step as? Live ?: return false
        val plain = msg.toString().toByteArray()
        // A record over the packet limit would make the iPhone drop the link; skip it instead.
        if (plain.size > Proto.MAX_PACKET - 1 - 8 - 16) { log("dropped ${msg.optString("t")}: ${plain.size} bytes"); return false }
        send(live.cipher.seal(plain))
        return true
    }

    /**
     * Seals one `AUDIO` record, or null when no live session exists. The transport seals at the moment it
     * sends, so frames it drops from its queue never use a counter.
     */
    fun sealAudio(plain: ByteArray): ByteArray? = (step as? Live)?.cipher?.sealAudio(plain)

    // ---- pairing ----

    private fun openWindow(): PairingWindow = Pairing.current()
        ?: if (Pairing.locked) throw ProtocolException(ErrorCode.TOO_MANY_ATTEMPTS, "too many wrong codes")
        else throw ProtocolException(ErrorCode.PAIRING_CLOSED, "pairing closed")

    private fun pairInfo(iPub: ByteArray, eiRaw: ByteArray) = ascii("ibridge-pair-v1") + identity.encoded + iPub + eiRaw

    private fun pairRequest(b: ByteArray) {
        val w = openWindow()
        if (b.size < 1 + 65 + 65) throw ProtocolException(ErrorCode.BAD_PACKET, "short PAIR_REQUEST")
        val mode = b[0]
        val iPub = b.copyOfRange(1, 66)
        P256.decode(iPub)
        val eiRaw = b.copyOfRange(66, 131)
        val ei = P256.decode(eiRaw)
        val name = String(b, 131, b.size - 131).ifBlank { "iPhone" }.take(40)
        step = when (mode) {
            Proto.MODE_QR -> AwaitProof(iPub, name, hkdf(identity.agree(ei), w.token, pairInfo(iPub, eiRaw), 32), viaCode = false)
            Proto.MODE_CODE -> CodeRounds(iPub, ei, eiRaw, name, w.codeBits)
            else -> throw ProtocolException(ErrorCode.BAD_PACKET, "bad mode")
        }
    }

    private fun roundBits(bits: Int, r: Int) = (bits shr (6 * (Proto.CODE_ROUNDS - 1 - r))) and 0x3F

    private fun commit(nonce: ByteArray, side: String, s: CodeRounds, r: Int) = hmac(
        nonce, ascii("ibridge-pass-v1"), ascii(side), identity.encoded, s.iPub, s.eiRaw,
        byteArrayOf(r.toByte(), roundBits(s.bits, r).toByte()),
    )

    /**
     * Round r: I commits, A commits and reveals at once, then I reveals (PROTOCOL.md 4.3).
     * A, which owns the code, reveals first, so a fake Android never sees I's nonce before proving the bits;
     * a fake iPhone that learns a round's bits from A's reveal still fails its own reveal, which replaces the code.
     */
    private fun passCommit(b: ByteArray) {
        val s = step as? CodeRounds ?: throw ProtocolException(ErrorCode.BAD_PACKET, "unexpected PASS_COMMIT")
        if (b.size != 33 || b[0].toInt() != s.round || s.theirCommit != null) throw ProtocolException(ErrorCode.BAD_PACKET, "bad PASS_COMMIT")
        s.theirCommit = b.copyOfRange(1, 33)
        val n = randomBytes(16)
        s.myNonce = n
        send(byteArrayOf(Proto.PASS_COMMIT, s.round.toByte()) + commit(n, "A", s, s.round))
        send(byteArrayOf(Proto.PASS_REVEAL, s.round.toByte()) + n)
    }

    private fun passReveal(b: ByteArray) {
        val s = step as? CodeRounds ?: throw ProtocolException(ErrorCode.BAD_PACKET, "unexpected PASS_REVEAL")
        val theirs = s.theirCommit
        val mine = s.myNonce
        if (b.size != 17 || b[0].toInt() != s.round || theirs == null || mine == null) throw ProtocolException(ErrorCode.BAD_PACKET, "bad PASS_REVEAL")
        val ni = b.copyOfRange(1, 17)
        if (!constantTimeEquals(theirs, commit(ni, "I", s, s.round))) {
            step = Idle
            Pairing.codeFailed()
            throw ProtocolException(ErrorCode.BAD_PROOF, "code mismatch")
        }
        s.transcript += ni
        s.transcript += mine
        s.theirCommit = null
        s.myNonce = null
        s.round++
        if (s.round == Proto.CODE_ROUNDS) {
            val salt = sha256(*s.transcript.toTypedArray())
            step = AwaitProof(s.iPub, s.name, hkdf(identity.agree(s.eiPub), salt, pairInfo(s.iPub, s.eiRaw), 32), viaCode = true)
        }
    }

    private fun pairProof(b: ByteArray) {
        val s = step as? AwaitProof ?: throw ProtocolException(ErrorCode.BAD_PACKET, "unexpected PAIR_PROOF")
        if (b.size != 32 || !constantTimeEquals(b, hmac(s.kPair, ascii("I")))) {
            step = Idle
            if (s.viaCode) Pairing.codeFailed()
            throw ProtocolException(ErrorCode.BAD_PROOF, "bad proof")
        }
        send(byteArrayOf(Proto.PAIR_ACCEPT) + hmac(s.kPair, ascii("A")) + deviceName.toByteArray())
        peers.save(Peer(s.iPub, s.name))
        Pairing.close()
        step = Idle
        log("paired with ${s.name}")
    }

    // ---- session ----

    private fun sessionHello(b: ByteArray) {
        val peer = peers.peer ?: throw ProtocolException(ErrorCode.NOT_PAIRED, "no paired iPhone")
        if (b.size != 8 + 65) throw ProtocolException(ErrorCode.BAD_PACKET, "bad SESSION_HELLO")
        if (!constantTimeEquals(b.copyOfRange(0, 8), peer.keyId)) throw ProtocolException(ErrorCode.NOT_PAIRED, "unknown iPhone")
        val eiRaw = b.copyOfRange(8, 73)
        val ei = P256.decode(eiRaw)
        val iPub = P256.decode(peer.publicKey)
        val ea: KeyPair = P256.ephemeral()
        val eaRaw = P256.encode(ea.public)
        val ikm = P256.ecdh(ea.private, ei) + identity.agree(ei) + P256.ecdh(ea.private, iPub)
        val salt = sha256(ascii("ibridge-session-v1"), identity.encoded, peer.publicKey, eiRaw, eaRaw)
        val okm = hkdf(ikm, salt, ascii("ibridge-app-v1"), 64)
        step = AwaitHello(RecordCipher(sendKey = okm.copyOfRange(32, 64), recvKey = okm.copyOfRange(0, 32)))
        send(byteArrayOf(Proto.SESSION_ACCEPT) + eaRaw)
    }

    private fun data(p: ByteArray) {
        when (val s = step) {
            is AwaitHello -> {
                val msg = JSONObject(String(s.cipher.open(p)))
                if (msg.optString("t") != "hello") throw ProtocolException(ErrorCode.BAD_PACKET, "first message must be hello")
                step = Live(s.cipher)
                onLive()
                onMessage(msg)
            }
            is Live -> onMessage(JSONObject(String(s.cipher.open(p))))
            else -> throw ProtocolException(ErrorCode.BAD_PACKET, "DATA before session")
        }
    }

    companion object {
        const val TAG = "iBridge"
        fun error(e: ErrorCode, reason: String) = byteArrayOf(Proto.ERROR, e.code.toByte()) + reason.toByteArray()
    }
}

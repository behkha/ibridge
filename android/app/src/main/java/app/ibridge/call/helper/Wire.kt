package app.ibridge.call.helper

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * The connection between iBridge and its shell helper: TCP on 127.0.0.1:[PORT], frames of type(1) || len(2) || payload.
 * Both sides compile this file; the helper runs it from the same APK through app_process.
 *
 * Why not a Unix socket: SELinux lets no app connect to a socket the shell serves ("Permission denied" on the Galaxy).
 * Loopback TCP is open to every app, so both ends first prove they hold one secret (iBridge makes the
 * secret, keeps it app-private and hands it to the helper on its command line, which other apps cannot read), and
 * the helper also checks in /proc/net/tcp that the connection belongs to iBridge's uid. The secret alone is not enough
 * for iBridge: Wireless debugging is found by mDNS and Kadb does not authenticate adbd, so an app posing as adbd could
 * read the launch command. iBridge then asks the peer to send a fresh nonce back by `am broadcast` to a receiver that
 * needs DUMP, which only the shell and the system hold ([PROOF_ACTION]).
 */
object Wire {
    const val PORT = 38_217
    /** Bump when the commands or events change: iBridge restarts a helper that answers another version. */
    const val VERSION = 3
    const val PACKAGE = "app.ibridge.android"
    const val SHELL_UID = 2000
    /** The helper's proof that it runs as the shell: this broadcast, carrying iBridge's nonce, reaches only a DUMP holder's sender. */
    const val PROOF_ACTION = "app.ibridge.HELPER_PROOF"
    const val PROOF_RECEIVER = "app.ibridge.service.HelperCommandReceiver"

    /** A JSON command (`c`) or event (`e`). */
    const val JSON: Int = 1
    /** 20 ms of 16 kHz mono s16le: 320 samples, 640 bytes. Up = iBridge → call, down = call → iBridge. */
    const val PCM: Int = 2

    const val RATE = 16_000
    const val FRAME = RATE / 50
    const val FRAME_BYTES = FRAME * 2

    /** HMAC-SHA256(secret, label || nonce), hex. */
    fun proof(secret: String, label: String, nonce: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal((label + nonce).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun nonce(): String = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun same(a: String, b: String) = java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    class Frame(val type: Int, val payload: ByteArray)

    /** Null at the end of the stream. */
    fun read(input: DataInputStream): Frame? {
        val type = try { input.readUnsignedByte() } catch (e: EOFException) { return null }
        val len = input.readUnsignedShort()
        val payload = ByteArray(len)
        input.readFully(payload)
        return Frame(type, payload)
    }

    /** Callers serialize writes on [out]. */
    @Throws(IOException::class)
    fun write(out: DataOutputStream, type: Int, payload: ByteArray) {
        require(payload.size <= 0xFFFF)
        out.writeByte(type)
        out.writeShort(payload.size)
        out.write(payload)
        out.flush()
    }
}

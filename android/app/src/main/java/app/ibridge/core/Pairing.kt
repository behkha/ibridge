package app.ibridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The one paired iPhone (PROTOCOL.md 4.4). */
data class Peer(val publicKey: ByteArray, val name: String) {
    val keyId: ByteArray get() = sha256(publicKey).copyOf(8)
}

/** A 5-minute pairing window: one QR token and one typed code (PROTOCOL.md 4). */
data class PairingWindow(val token: ByteArray, val code: String, val expiresAt: Long, val failures: Int = 0) {
    /** The code as a 30-bit number, first character most significant. */
    val codeBits: Int get() = code.fold(0) { acc, ch -> (acc shl 5) or ALPHABET.indexOf(ch) }

    companion object {
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val LIFETIME_MS = 5 * 60_000L
        const val MAX_FAILURES = 5

        fun newCode(): String = String(CharArray(6) { ALPHABET[random.nextInt(32)] })
    }
}

object Pairing {
    private val _window = MutableStateFlow<PairingWindow?>(null)
    val window: StateFlow<PairingWindow?> = _window.asStateFlow()

    /** Opens a window, or renews it when the current one has expired. */
    fun open() {
        val w = _window.value
        if (w == null || w.expiresAt <= System.currentTimeMillis()) {
            _window.value = PairingWindow(randomBytes(16), PairingWindow.newCode(), System.currentTimeMillis() + PairingWindow.LIFETIME_MS)
        }
    }

    fun close() { _window.value = null }

    /** The live window, or null when pairing is closed. */
    fun current(): PairingWindow? = _window.value?.takeIf { it.expiresAt > System.currentTimeMillis() }

    /** A wrong code: replace the code at once, close after too many tries. */
    fun codeFailed() {
        _window.update { w ->
            w ?: return@update null
            val n = w.failures + 1
            if (n >= PairingWindow.MAX_FAILURES) null else w.copy(code = PairingWindow.newCode(), failures = n)
        }
    }
}

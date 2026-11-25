package app.ibridge.call

import android.util.Log
import app.ibridge.call.helper.Wire
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * iBridge's end of the helper connection ([Wire]). Talks only to a peer that proves it holds [secret], so another app
 * listening on the port first gets nothing. One instance per connection; [onClosed] fires once when it ends.
 */
class HelperClient(
    private val secret: String,
    private val onEvent: (JSONObject) -> Unit,
    private val onPcm: (ByteArray) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val socket = Socket()
    private lateinit var out: DataOutputStream
    private lateinit var input: DataInputStream
    @Volatile var open = false
        private set

    /** Throws when no helper listens or the peer does not prove the secret. */
    fun connect() {
        try { handshake() } catch (e: Exception) { runCatching { socket.close() }; throw e }
        open = true
        Thread({ readLoop() }, "ibridge-helper").apply { isDaemon = true; start() }
    }

    private fun handshake() {
        socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), Wire.PORT), 1_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 3_000
        out = DataOutputStream(socket.getOutputStream().buffered())
        val input = DataInputStream(socket.getInputStream().buffered())
        val mine = Wire.nonce()
        Wire.write(out, Wire.JSON, JSONObject().put("c", "auth").put("n", mine).toString().toByteArray())
        val reply = Wire.read(input)?.takeIf { it.type == Wire.JSON }?.let { JSONObject(String(it.payload)) }
        if (reply == null || !Wire.same(reply.optString("p"), Wire.proof(secret, "helper", mine))) {
            throw SecurityException("the peer on port ${Wire.PORT} is not iBridge's helper")
        }
        Wire.write(out, Wire.JSON, JSONObject().put("c", "proof").put("p", Wire.proof(secret, "app", reply.optString("n"))).toString().toByteArray())
        socket.soTimeout = 0
        this.input = input
    }

    private fun readLoop() {
        try {
            while (true) {
                val f = Wire.read(input) ?: break
                when (f.type) {
                    Wire.JSON -> onEvent(JSONObject(String(f.payload)))
                    Wire.PCM -> onPcm(f.payload)
                }
            }
        } catch (e: Exception) {
            if (open) Log.i(TAG, "helper socket: ${e.javaClass.simpleName}")
        }
        close()
    }

    fun command(c: JSONObject): Boolean = write(Wire.JSON, c.toString().toByteArray())

    fun pcm(frame: ByteArray): Boolean = write(Wire.PCM, frame)

    private fun write(type: Int, payload: ByteArray): Boolean {
        if (!open) return false
        return try { synchronized(out) { Wire.write(out, type, payload) }; true } catch (e: Exception) { close(); false }
    }

    fun close() {
        synchronized(this) { if (!open) { runCatching { socket.close() }; return }; open = false }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
        onClosed()
    }

    companion object { const val TAG = "iBridge" }
}

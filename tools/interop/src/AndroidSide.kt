import app.ibridge.core.Deframer
import app.ibridge.core.Framer
import app.ibridge.core.LinkEngine
import app.ibridge.core.P256
import app.ibridge.core.Pairing
import app.ibridge.core.Peer
import app.ibridge.core.PeerStore
import app.ibridge.core.ProtocolException
import app.ibridge.core.SoftwareKey
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/** Android side of the interop check. Chunks travel as u16 length + bytes on stdin/stdout, at a 23-byte MTU. */
fun main(args: Array<String>) {
    val windowFile = File(args[0])
    val identity = SoftwareKey()
    val peers = object : PeerStore {
        override var peer: Peer? = null
        override fun save(peer: Peer) { this.peer = peer; System.err.println("ANDROID paired with ${peer.name}") }
    }
    Pairing.open()
    val w = Pairing.current()!!
    val b64 = Base64.getUrlEncoder().withoutPadding()
    val uri = "ibridge://pair?v=1&k=${b64.encodeToString(P256.compressed(identity.publicKey))}&t=${b64.encodeToString(w.token)}&n=Pixel%208a"
    windowFile.writeText(JSONObject().put("uri", uri).put("code", w.code).toString())

    val input = DataInputStream(System.`in`.buffered())
    val output = DataOutputStream(System.out.buffered())
    val framer = Framer()
    val deframer = Deframer()
    fun send(packet: ByteArray) {
        framer.split(packet, 20).forEach { output.writeShort(it.size); output.write(it) }
        output.flush()
    }
    lateinit var engine: LinkEngine
    engine = LinkEngine("Pixel 8a", identity, peers, { System.err.println("ANDROID $it") }, ::send,
        onLive = { System.err.println("ANDROID live") },
        onMessage = { m ->
            when (m.optString("t")) {
                "hello" -> engine.sendMessage(JSONObject().put("t", "status").put("battery", 82).put("name", "Pixel 8a"))
                "ping" -> engine.sendMessage(JSONObject().put("t", "pong").put("ts", 1))
                "sync" -> engine.sendMessage(
                    JSONObject().put("t", "page").put("kind", "sms").put("more", false).put("cursor", "9|0")
                        .put("items", List(40) { JSONObject().put("id", "$it").put("addr", "+98912").put("body", "سلام ".repeat(30)).put("ts", 1) })
                )
            }
        })
    try {
        while (true) {
            val n = input.readUnsignedShort()
            val chunk = ByteArray(n).also { input.readFully(it) }
            deframer.feed(chunk)?.let { engine.onPacket(it) }
        }
    } catch (e: EOFException) {
        engine.close()
        if (peers.peer != null) exitProcess(0)
        // Not paired: the attempt must have replaced the code (PROTOCOL.md 4.3).
        val replaced = Pairing.current()?.code != w.code || Pairing.locked
        System.err.println("ANDROID hung up unpaired, code replaced: $replaced")
        exitProcess(if (replaced) 3 else 4)
    } catch (e: ProtocolException) {
        System.err.println("ANDROID ERROR ${e.error} ${e.message}")
        send(LinkEngine.error(e.error, e.message ?: ""))
        exitProcess(3)
    }
}

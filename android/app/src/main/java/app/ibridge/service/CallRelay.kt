package app.ibridge.service

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import app.ibridge.call.AudioState
import app.ibridge.call.CallAudio
import app.ibridge.call.Relay
import app.ibridge.data.Phone
import org.json.JSONObject

/**
 * Calls relayed to the iPhone (PROTOCOL.md 8): `call.answer` / `call.dial` / `call.hangup` / `call.dtmf` from the
 * iPhone, `call.state` and `call.end` back, the sound as `AUDIO` records, and the debug `bench`.
 * Everything here runs on the GATT worker ([Gatt.post]); helper events are posted onto it.
 */
class CallRelay(
    private val context: Context,
    private val gatt: Gatt,
    private val phone: Phone,
    private val push: (JSONObject) -> Boolean,
    private val onAudioState: () -> Unit,
) : CallAudio.Listener {

    private class Relayed(
        val id: String, val stream: Int, val dir: String, val addr: String, val name: String?, val sim: Int, val req: String?,
    ) {
        var state: String? = null
        var relay: Relay? = null
        val since = System.currentTimeMillis()
    }

    private var call: Relayed? = null
    private var nextStream = 1
    /** Bumped on every link drop and relink, so a 20 s hang-up timer knows whether it is stale. */
    private var linkEpoch = 0

    /** A call is relayed: pages stay small so audio is never stuck behind one. */
    val active get() = call != null

    // ---- from the iPhone ----

    /** [ringing] is BridgeService's ringing call (event id, number) and its `call.ring`; true when this took the call. */
    fun answer(m: JSONObject, ringing: Pair<String, String>?, ring: JSONObject?): Boolean {
        val id = m.optString("call")
        when {
            ringing?.first != id || ring == null -> { push(end(id, "ended")); return false }
            CallAudio.state != AudioState.READY || call != null -> { push(end(id, "failed")); return false }
        }
        val c = Relayed(id, stream(), "in", ringing!!.second, ring!!.optString("name").ifEmpty { null }, ring.optInt("sim", 1), null)
        call = c
        CallAudio.command(JSONObject().put("c", "answer"))
        CallAudio.command(JSONObject().put("c", "relay").put("on", true))
        Log.i(TAG, "call ${c.id}: answering for the iPhone (stream ${c.stream})")
        gatt.post(delayMs = 10_000) { if (call === c && c.state == null) { Log.w(TAG, "call ${c.id}: not active 10 s after answer"); finish(c, "ended") } }
        return true
    }

    fun dial(m: JSONObject) {
        val req = m.optString("req")
        val to = m.optString("to")
        val id = "c${System.currentTimeMillis()}"
        if (to.isBlank() || CallAudio.state != AudioState.READY || call != null) { push(end(id, "failed", req)); return }
        val sim = m.optInt("sim", 0).takeIf { it > 0 }
        val handle = accountFor(sim)
        val slot = sim ?: phone.slotForSubscription(android.telephony.SubscriptionManager.getDefaultVoiceSubscriptionId())
        val c = Relayed(id, stream(), "out", to, phone.nameFor(to), slot, req)
        call = c
        val sent = CallAudio.command(JSONObject().put("c", "dial").put("to", to)
            .put("comp", handle?.componentName?.flattenToString() ?: "").put("id", handle?.id ?: ""))
            && CallAudio.command(JSONObject().put("c", "relay").put("on", true))
        Log.i(TAG, "call ${c.id}: dialing for the iPhone (stream ${c.stream}, sim ${sim ?: "default"})")
        if (!sent) { finish(c, "failed"); return }
        gatt.post(delayMs = 15_000) { if (call === c && c.state == null) { Log.w(TAG, "call ${c.id}: never started"); CallAudio.command(JSONObject().put("c", "hangup")); finish(c, "failed") } }
    }

    fun hangup(m: JSONObject) {
        val c = call
        if (c == null || c.id != m.optString("call")) { push(end(m.optString("call"), "ended")); return }
        Log.i(TAG, "call ${c.id}: hang up from the iPhone")
        CallAudio.command(JSONObject().put("c", "hangup"))
        // The helper's idle state finishes it; if the call is already gone, finish now.
        gatt.post(delayMs = 3_000) { if (call === c) finish(c, "ended") }
    }

    fun dtmf(m: JSONObject) {
        val c = call ?: return
        val d = m.optString("digit")
        if (c.id == m.optString("call") && d.length == 1 && d[0] in "0123456789*#") CallAudio.command(JSONObject().put("c", "dtmf").put("d", d))
    }

    /** `call.state` again after `hello` while a relayed call lasts. */
    fun onHello() {
        linkEpoch++
        val c = call ?: return
        if (c.state != null) push(stateMsg(c))
    }

    /** The link dropped: a relayed call nobody can hear ends after 20 s (PROTOCOL.md 8). */
    fun onLinkDown() {
        val epoch = ++linkEpoch
        val c = call ?: return
        Log.i(TAG, "call ${c.id}: link lost; ending the call in 20 s unless the iPhone is back")
        gatt.post(delayMs = 20_000) {
            if (call === c && linkEpoch == epoch && gatt.engine?.isLive != true) {
                Log.w(TAG, "call ${c.id}: iPhone still away; hanging up")
                CallAudio.command(JSONObject().put("c", "hangup"))
                gatt.post(delayMs = 3_000) { if (call === c) finish(c, "ended") }
            }
        }
    }

    /** One `AUDIO` plaintext from the iPhone. */
    fun onAudio(plain: ByteArray) {
        if (plain.size < 3) return
        val stream = plain[0].toInt() and 0xFF
        if (stream == 0) { benchReceived(plain); return }
        val c = call ?: return
        if (stream != c.stream) return
        c.relay?.onUplink(plain.copyOfRange(3, plain.size))
    }

    // ---- from the helper ----

    override fun onAudioState(state: AudioState) = gatt.post { onAudioState() }

    override fun onDownlink(pcm: ByteArray) { call?.relay?.onDownlink(pcm) }

    override fun onHelperEvent(e: JSONObject) = gatt.post {
        when (e.optString("e")) {
            "state" -> onState(e.optInt("ring"), e.optInt("fg"), e.optInt("bg"))
            "relay" -> {
                val c = call
                if (e.optBoolean("on") && c != null && c.relay == null) {
                    c.relay = Relay(c.stream) { plain -> gatt.post { gatt.sendAudio(plain) } }.also { it.start() }
                    Log.i(TAG, "call ${c.id}: audio flowing")
                } else if (!e.optBoolean("on")) {
                    c?.relay?.stop(); c?.relay = null
                    e.optString("err").takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "call ${c?.id}: audio failed: $it") }
                }
            }
            "stats" -> call?.let { c ->
                val r = c.relay
                Log.i(TAG, "relay ${c.id}: sent ${r?.sent?.getAndSet(0)} received ${r?.received?.getAndSet(0)} " +
                    "dropped ${gatt.audioDropped - droppedBefore} late ${r?.late?.getAndSet(0)} link queue ${gatt.audioQueued} | " +
                    "helper down ${e.optInt("down")} up ${e.optInt("up")} silent ${e.optInt("silent")} upQueue ${e.optInt("upQueue")} upDropped ${e.optInt("upDropped")}")
                droppedBefore = gatt.audioDropped
            }
            "error" -> {
                Log.w(TAG, "helper error: ${e.optString("what")}")
                val c = call
                if (c != null && c.state == null && e.optString("what").startsWith("dial")) finish(c, "failed")
            }
        }
    }

    private var droppedBefore = 0L

    /** Precise call states from the helper: 1 active, 2 holding, 3 dialing, 4 alerting, 5 incoming, 6 waiting. */
    private fun onState(ring: Int, fg: Int, bg: Int) {
        val c = call ?: return
        val state = when {
            fg == 3 -> "dialing"
            fg == 4 -> "alerting"
            fg == 1 -> "active"
            fg == 2 || bg == 2 -> "held"
            else -> null
        }
        if (state == null) {
            if (c.state != null && ring !in 1..6) finish(c, "ended") // the call is gone
            return
        }
        if (state == c.state) return
        c.state = state
        push(stateMsg(c))
    }

    private fun finish(c: Relayed, how: String) {
        if (call !== c) return
        call = null
        c.relay?.stop()
        CallAudio.command(JSONObject().put("c", "relay").put("on", false))
        push(end(c.id, how, c.req))
        Log.i(TAG, "call ${c.id}: $how after ${(System.currentTimeMillis() - c.since) / 1000} s")
    }

    private fun stateMsg(c: Relayed) = JSONObject().put("t", "call.state").put("call", c.id).put("state", c.state)
        .put("dir", c.dir).put("addr", c.addr).putOpt("name", c.name).put("sim", c.sim).put("stream", c.stream).putOpt("req", c.req)

    private fun end(id: String, how: String, req: String? = null) =
        JSONObject().put("t", "call.end").put("call", id).put("how", how).putOpt("req", req?.ifEmpty { null })

    private fun stream(): Int = nextStream.also { nextStream = if (it >= 255) 1 else it + 1 }

    /** The phone account of SIM [slot], or null for the phone's default. */
    @SuppressLint("MissingPermission")
    private fun accountFor(slot: Int?): android.telecom.PhoneAccountHandle? {
        val sub = slot?.let { phone.subForSlot(it) } ?: return null
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            context.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.firstOrNull { tm.getSubscriptionId(it) == sub }
        } catch (e: Exception) { null }
    }

    // ---- bench (debug, PROTOCOL.md 6) ----

    private var benchExpect = 0
    private var benchGot = 0
    private var benchFirstAt = 0L

    fun bench(m: JSONObject) {
        val n = m.optInt("n").coerceIn(0, 10_000)
        val size = m.optInt("size").coerceIn(1, 4_000)
        benchExpect = n; benchGot = 0; benchFirstAt = 0
        var left = n
        var seq = 0
        val start = System.currentTimeMillis()
        gatt.audioSource = source@{
            if (left == 0) {
                gatt.audioSource = null
                val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
                Log.i(TAG, "bench: sent $n × $size B in $ms ms = ${n * size * 8 / ms} kbit/s (A → I, payload only)")
                return@source null
            }
            left--
            ByteArray(3 + size).also { it[0] = 0; it[1] = (seq shr 8).toByte(); it[2] = seq.toByte(); seq = (seq + 1) and 0xFFFF }
        }
        Log.i(TAG, "bench: sending $n × $size B")
        gatt.kick()
    }

    private fun benchReceived(plain: ByteArray) {
        if (benchExpect == 0) return
        val now = System.currentTimeMillis()
        if (benchGot == 0) benchFirstAt = now
        benchGot++
        if (benchGot < benchExpect) return
        val ms = now - benchFirstAt
        Log.i(TAG, "bench: received $benchGot × ${plain.size - 3} B in $ms ms = ${if (ms > 0) benchGot * (plain.size - 3) * 8 / ms else 0} kbit/s (I → A, payload only)")
        push(JSONObject().put("t", "bench.done").put("n", benchGot).put("ms", ms))
        benchExpect = 0
    }

    companion object { const val TAG = "iBridge" }
}

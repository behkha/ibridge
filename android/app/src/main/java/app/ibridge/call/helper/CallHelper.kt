package app.ibridge.call.helper

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.sin
import kotlin.system.exitProcess

/**
 * iBridge's call-audio helper. Runs as the shell user (uid 2000) through app_process, started over Wireless debugging:
 *
 *   CLASSPATH=<apk> app_process /system/bin --nice-name=ibridge_call app.ibridge.call.helper.CallHelper <app uid> <build> <secret>
 *
 * The shell holds what an app cannot: CALL_AUDIO_INTERCEPTION (hear and talk into a cellular call), MODIFY_PHONE_STATE
 * and MODIFY_AUDIO_ROUTING. It serves 127.0.0.1:[Wire.PORT] to iBridge only (its uid, and proof of the secret), answers, places and ends calls, reports the
 * precise call state, and while relaying keeps the Galaxy out of the call (mic muted, MODE_CALL_REDIRECT, voice volume
 * at its minimum), restoring all three when the relay stops, iBridge's socket closes, the call ends or this process dies.
 * Proven in tools/call-audio-probe (docs/notes/call-audio-spike.md). Call audio is never written anywhere.
 */
object CallHelper {
    private const val TAG = "iBridge"
    private const val SHELL_PKG = "com.android.shell"
    private const val MODE_NORMAL = 0
    private const val MODE_CALL_REDIRECT = 5
    private const val CALL_REDIRECT_PSTN = 1
    private const val SOURCE_VOICE_DOWNLINK = 3
    private const val USAGE_CALL_ASSISTANT = 17
    private const val STREAM_VOICE_CALL = 0
    /** Uplink frames waiting for the call's clock; older ones are dropped (the iPhone side buffers the jitter). */
    private const val UP_QUEUE = 3

    private var appUid = -1
    private var build = ""
    private var secret = ""
    private lateinit var audio: Any
    private lateinit var telecom: Any
    private val token: IBinder = Binder()

    @JvmStatic
    fun main(args: Array<String>) {
        appUid = args.getOrNull(0)?.toIntOrNull() ?: run { log("usage: CallHelper <app uid> <build> <secret>"); exitProcess(2) }
        build = args.getOrNull(1) ?: ""
        secret = args.getOrNull(2)?.takeIf { it.length >= 32 } ?: run { log("no secret given; quitting"); exitProcess(2) }
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e(TAG, "helper: crash in ${t.name}", e)
            Relay.stop("crash")
            exitProcess(1)
        }
        Runtime.getRuntime().addShutdownHook(Thread { Relay.stop("exit") })
        audio = service("audio", "android.media.IAudioService")
        telecom = service("telecom", "com.android.internal.telecom.ITelecomService")
        if (!packagesForUid(appUid).contains(Wire.PACKAGE)) { log("uid $appUid is not ${Wire.PACKAGE}; quitting"); exitProcess(2) }
        val server = try { ServerSocket(Wire.PORT, 4, InetAddress.getByName("127.0.0.1")) } catch (e: Exception) {
            log("port ${Wire.PORT} taken (${e.message}): another helper runs; quitting"); exitProcess(3)
        }
        log("started v${Wire.VERSION} build $build, pid ${android.os.Process.myPid()}, interceptable=${interceptable()}")
        grantSecureSettings()
        Thread(::pollCallState, "ibridge-state").apply { isDaemon = true; start() }
        while (true) {
            val s = server.accept()
            val uid = uidOf(s)
            if (uid != appUid) {
                log("refused a client with uid $uid")
                runCatching { s.close() }
                continue
            }
            Client(s).start()
        }
    }

    // ---- iBridge's connection ----

    @Volatile private var client: Client? = null

    /** The uid that owns the client end of [s], from /proc/net/tcp6 and tcp (the shell may read them; apps may not). */
    private fun uidOf(s: Socket): Int {
        val port = "%04X".format(s.port)
        val server = "%04X".format(Wire.PORT)
        for (f in listOf("/proc/net/tcp6", "/proc/net/tcp")) {
            runCatching {
                File(f).readLines().drop(1).forEach { line ->
                    val c = line.trim().split(Regex("\\s+"))
                    if (c.size > 7 && c[1].endsWith(":$port") && c[2].endsWith(":$server")) return c[7].toInt()
                }
            }
        }
        return -1
    }

    private class Client(val socket: Socket) : Thread("ibridge-client") {
        private val out = DataOutputStream(socket.getOutputStream().buffered())
        @Volatile private var open = true

        /** Both ends show they hold the secret: iBridge first asks with a nonce, then answers the helper's nonce. */
        private fun authenticate(input: DataInputStream): Boolean {
            socket.soTimeout = 5_000
            val ask = Wire.read(input)?.takeIf { it.type == Wire.JSON }?.let { JSONObject(String(it.payload)) } ?: return false
            if (ask.optString("c") != "auth") return false
            val mine = Wire.nonce()
            send(Wire.JSON, JSONObject().put("e", "auth").put("n", mine).put("p", Wire.proof(secret, "helper", ask.optString("n"))).toString().toByteArray())
            val answer = Wire.read(input)?.takeIf { it.type == Wire.JSON }?.let { JSONObject(String(it.payload)) } ?: return false
            socket.soTimeout = 0
            return answer.optString("c") == "proof" && Wire.same(answer.optString("p"), Wire.proof(secret, "app", mine))
        }

        override fun run() {
            try {
                socket.tcpNoDelay = true
                val input = DataInputStream(socket.getInputStream().buffered())
                if (!authenticate(input)) { log("refused a client without the secret"); open = false; runCatching { socket.close() }; return }
                client?.close("replaced")
                client = this
                log("iBridge connected")
                while (true) {
                    val f = Wire.read(input) ?: break
                    when (f.type) {
                        Wire.JSON -> command(JSONObject(String(f.payload)))
                        Wire.PCM -> Relay.up(f.payload)
                    }
                }
            } catch (e: Exception) {
                if (open) log("client read: ${e.javaClass.simpleName} ${e.message}")
            }
            close("closed")
        }

        fun send(type: Int, payload: ByteArray) {
            if (!open) return
            try { synchronized(out) { Wire.write(out, type, payload) } } catch (e: Exception) { close("write failed") }
        }

        fun close(why: String) {
            if (!open) return
            open = false
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
            if (client === this) {
                client = null
                log("iBridge disconnected ($why)")
                Relay.stop("iBridge disconnected")
            }
        }
    }

    private fun event(e: JSONObject) { client?.send(Wire.JSON, e.toString().toByteArray()) }

    private fun error(what: String) { log("error: $what"); event(JSONObject().put("e", "error").put("what", what)) }

    private fun command(c: JSONObject) {
        when (val name = c.optString("c")) {
            "hello" -> {
                event(JSONObject().put("e", "hello").put("ver", Wire.VERSION).put("build", build)
                    .put("interceptable", interceptable() == true).put("pid", android.os.Process.myPid()))
                lastState = null // the next poll sends the state to the new client
            }
            "quit" -> { log("quit requested"); Relay.stop("quit"); exitProcess(0) }
            "answer" -> if (invoke(telecom, "acceptRingingCall") == null) error("answer failed")
            "hangup" -> { val r = invoke(telecom, "endCall"); log("endCall: $r") }
            "silence" -> invoke(telecom, "silenceRinger")
            "dial" -> dial(c.optString("to"), c.optString("comp"), c.optString("id"))
            "relay" -> if (c.optBoolean("on")) Relay.arm() else Relay.stop("asked")
            "dtmf" -> Relay.dtmf(c.optString("d").firstOrNull() ?: ' ')
            "check" -> check()
            else -> log("unknown command $name")
        }
    }

    private fun dial(to: String, comp: String, id: String) {
        if (to.isBlank()) { error("dial: no number"); return }
        val extras = Bundle()
        ComponentName.unflattenFromString(comp)?.takeIf { id.isNotEmpty() }?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle(it, id))
        }
        if (invoke(telecom, "placeCall", Uri.fromParts("tel", to, null), extras) == null) error("dial failed")
        else log("placeCall sent")
    }

    /** Opens and closes both call-audio paths without a call (they open; the record reads silence). */
    private fun check() {
        if (Relay.running) return
        val e = JSONObject().put("e", "check")
        try { downlinkRecord().apply { startRecording(); e.put("read", read(ShortArray(Wire.FRAME), 0, Wire.FRAME)); stop(); release() }; e.put("record", true) }
        catch (t: Throwable) { e.put("record", false).put("recordErr", cause(t)) }
        try { uplinkTrack().apply { play(); write(ShortArray(Wire.FRAME), 0, Wire.FRAME); stop(); release() }; e.put("track", true) }
        catch (t: Throwable) { e.put("track", false).put("trackErr", cause(t)) }
        log("check: $e")
        event(e)
    }

    // ---- call state ----

    /** ring, foreground, background precise call states (PreciseCallState: 0 idle, 1 active, 2 holding, 3 dialing, 4 alerting, 5 incoming, 6 waiting, 7 disconnected, 8 disconnecting). */
    @Volatile private var lastState: Triple<Int, Int, Int>? = null

    /** Every 200 ms while a call exists, every second otherwise (one cheap Binder call). */
    private fun pollCallState() {
        while (true) {
            val inCall = invoke(telecom, "isInCall") as? Boolean ?: true
            val st = if (inCall) preciseState() else Triple(0, 0, 0)
            if (st != lastState) {
                lastState = st
                log("call state ring=${st.first} fg=${st.second} bg=${st.third}")
                event(JSONObject().put("e", "state").put("ring", st.first).put("fg", st.second).put("bg", st.third))
                Relay.onState(st)
            }
            SystemClock.sleep(if (inCall) 200 else 1000)
        }
    }

    private val stateLine = Regex("Ringing call state: (\\d+), Foreground call state: (\\d+), Background call state: (\\d+)")

    /** `dumpsys telephony.registry` has one mPreciseCallState per phone (SIM); the busiest value of each wins. */
    private fun preciseState(): Triple<Int, Int, Int> {
        var r = 0; var f = 0; var b = 0
        fun live(v: Int) = v in 1..6
        try {
            val p = ProcessBuilder("dumpsys", "telephony.registry").redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                lines.filter { "mPreciseCallState=" in it }.forEach { line ->
                    stateLine.find(line)?.destructured?.let { (a, c, d) ->
                        if (!live(r)) r = a.toInt().takeIf(::live) ?: r
                        if (!live(f)) f = c.toInt().takeIf(::live) ?: f
                        if (!live(b)) b = d.toInt().takeIf(::live) ?: b
                    }
                }
            }
            p.destroy()
        } catch (e: Exception) { log("dumpsys: ${e.message}") }
        return Triple(r, f, b)
    }

    // ---- relay ----

    private object Relay {
        @Volatile var armed = false
        @Volatile var running = false
        private var record: AudioRecord? = null
        private var track: AudioTrack? = null
        private var micWas: Boolean? = null
        private var modeTaken = false
        private var volWas = -1
        private val upQueue = ArrayDeque<ByteArray>()
        private var dtmfLeft = 0
        private var dtmfLow = 0.0
        private var dtmfHigh = 0.0
        private var dtmfPhase = 0L
        // once-a-second stats
        private var down = 0; private var up = 0; private var silent = 0; private var upDropped = 0

        /** Starts at once when a call is dialing, alerting, active or held; otherwise when one is. */
        @Synchronized fun arm() {
            if (running) return
            armed = true
            log("relay armed")
            lastState?.let { onState(it) }
        }

        @Synchronized fun onState(st: Triple<Int, Int, Int>) {
            val (ring, fg, bg) = st
            val call = fg in setOf(1, 3, 4) || bg == 2
            if (armed && !running && call) start()
            else if (running && !call && ring == 0 && fg != 2) stop("call ended")
        }

        private fun start() {
            armed = false
            micWas = isTrue(invoke(audio, "isMicrophoneMuted"))
            volWas = (invoke(audio, "getStreamVolume", STREAM_VOICE_CALL) as? Int) ?: -1
            // Mode and volume first: changing them stalls the downlink read once (up to 300 ms).
            setMicMute(true)
            setMode(MODE_CALL_REDIRECT)
            setVolume((invoke(audio, "getStreamMinVolume", STREAM_VOICE_CALL) as? Int) ?: 1)
            val t0 = SystemClock.elapsedRealtime()
            val r = try { downlinkRecord() } catch (e: Throwable) { error("record: ${cause(e)}"); null }
            val t = try { uplinkTrack() } catch (e: Throwable) { error("track: ${cause(e)}"); null }
            if (r == null || t == null) { r?.release(); t?.release(); restore(); event(JSONObject().put("e", "relay").put("on", false).put("err", "open failed")); return }
            record = r; track = t
            synchronized(upQueue) { upQueue.clear() }
            running = true
            Thread({ downLoop(r) }, "ibridge-down").start()
            Thread({ upLoop(t) }, "ibridge-up").start()
            Thread(::statsLoop, "ibridge-stats").apply { isDaemon = true; start() }
            log("relay on (opened in ${SystemClock.elapsedRealtime() - t0} ms; mic was muted=$micWas, voice volume was $volWas)")
            event(JSONObject().put("e", "relay").put("on", true))
        }

        @Synchronized fun stop(why: String) {
            armed = false
            if (!running) { restore(); return }
            running = false
            record?.let { runCatching { it.stop() }; it.release() }
            track?.let { runCatching { it.stop() }; it.release() }
            record = null; track = null
            restore()
            log("relay off ($why)")
            event(JSONObject().put("e", "relay").put("on", false))
        }

        /** Telecom may unmute or take the mode back when a call changes state; take them again (checked every second). */
        @Synchronized private fun enforce() {
            if (!running) return
            if (!isTrue(invoke(audio, "isMicrophoneMuted"))) { log("mic was unmuted; muting again"); setMicMute(true) }
            val mode = invoke(audio, "getMode") as? Int
            if (mode != null && mode != MODE_CALL_REDIRECT) { log("mode is $mode; taking REDIRECT again"); setMode(MODE_CALL_REDIRECT) }
        }

        private fun restore() {
            micWas?.let { setMicMute(it) }; micWas = null
            if (modeTaken) { invoke(audio, "setMode", MODE_NORMAL); modeTaken = false } // drops our request; Telecom's mode returns
            if (volWas >= 0) { invoke(audio, "setStreamVolume", STREAM_VOICE_CALL, volWas, 0); volWas = -1 }
        }

        private fun setMicMute(on: Boolean) = invoke(audio, "setMicrophoneMute", on)
        private fun setMode(m: Int) { invoke(audio, "setMode", m); modeTaken = true }
        private fun setVolume(i: Int) = invoke(audio, "setStreamVolume", STREAM_VOICE_CALL, i, 0)

        fun up(pcm: ByteArray) {
            if (!running || pcm.size != Wire.FRAME_BYTES) return
            synchronized(upQueue) {
                upQueue.addLast(pcm)
                while (upQueue.size > UP_QUEUE) { upQueue.removeFirst(); upDropped++ }
            }
        }

        private val dtmfTable = mapOf(
            '1' to (697 to 1209), '2' to (697 to 1336), '3' to (697 to 1477),
            '4' to (770 to 1209), '5' to (770 to 1336), '6' to (770 to 1477),
            '7' to (852 to 1209), '8' to (852 to 1336), '9' to (852 to 1477),
            '*' to (941 to 1209), '0' to (941 to 1336), '#' to (941 to 1477),
        )

        /** Mixes a 160 ms tone into the uplink. */
        fun dtmf(d: Char) {
            val (lo, hi) = dtmfTable[d] ?: return
            synchronized(upQueue) { dtmfLow = lo.toDouble(); dtmfHigh = hi.toDouble(); dtmfPhase = 0; dtmfLeft = Wire.RATE * 160 / 1000 }
            log("dtmf tone")
        }

        private fun downLoop(r: AudioRecord) {
            val buf = ShortArray(Wire.FRAME)
            val bytes = ByteBuffer.allocate(Wire.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            try {
                r.startRecording()
                while (running) {
                    var got = 0
                    while (got < Wire.FRAME && running) {
                        val n = r.read(buf, got, Wire.FRAME - got)
                        if (n < 0) { error("downlink read $n"); return }
                        got += n
                    }
                    bytes.clear(); bytes.asShortBuffer().put(buf)
                    client?.send(Wire.PCM, bytes.array().copyOf())
                    down++
                }
            } catch (e: Throwable) { if (running) error("downlink: ${cause(e)}") }
        }

        /** Paced by the call's clock: each blocking write takes the next frame from iBridge, or silence. */
        private fun upLoop(t: AudioTrack) {
            val pcm = ShortArray(Wire.FRAME)
            try {
                t.play()
                while (running) {
                    val frame = synchronized(upQueue) { upQueue.pollFirst() }
                    if (frame != null) { ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm); up++ }
                    else { pcm.fill(0); silent++ }
                    mixDtmf(pcm)
                    val w = t.write(pcm, 0, Wire.FRAME)
                    if (w < 0) { error("uplink write $w"); return }
                }
            } catch (e: Throwable) { if (running) error("uplink: ${cause(e)}") }
        }

        private fun mixDtmf(pcm: ShortArray) = synchronized(upQueue) {
            if (dtmfLeft <= 0) return
            val n = minOf(dtmfLeft, pcm.size)
            for (i in 0 until n) {
                val t = (dtmfPhase + i).toDouble() / Wire.RATE
                val v = pcm[i] + 6000 * (sin(2 * PI * dtmfLow * t) + sin(2 * PI * dtmfHigh * t))
                pcm[i] = v.toInt().coerceIn(-32768, 32767).toShort()
            }
            dtmfPhase += n; dtmfLeft -= n
        }

        private fun statsLoop() {
            while (running) {
                SystemClock.sleep(1000)
                if (!running) return
                enforce()
                val q = synchronized(upQueue) { upQueue.size }
                val line = "relay down=$down up=$up silent=$silent upDropped=$upDropped upQueue=$q underruns=${track?.underrunCount ?: -1}"
                Log.i(TAG, "helper: $line")
                event(JSONObject().put("e", "stats").put("down", down).put("up", up).put("silent", silent).put("upDropped", upDropped).put("upQueue", q))
                down = 0; up = 0; silent = 0; upDropped = 0
            }
        }
    }

    // ---- audio paths (what AudioManager's hidden getCallDownlinkExtractionAudioRecord / getCallUplinkInjectionAudioTrack build) ----

    private fun format(mask: Int) = AudioFormat.Builder().setSampleRate(Wire.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(mask).build()

    private fun downlinkRecord(): AudioRecord {
        val a = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType).invoke(a, SOURCE_VOICE_DOWNLINK)
        val b = AudioRecord.Builder().setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(Wire.FRAME_BYTES * 4)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(b, a.build())
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType).invoke(b, CALL_REDIRECT_PSTN)
        val r = b.build()
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("record not initialized") }
        return r
    }

    private fun uplinkTrack(): AudioTrack {
        val a = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        AudioAttributes.Builder::class.java.getMethod("setSystemUsage", Int::class.javaPrimitiveType).invoke(a, USAGE_CALL_ASSISTANT)
        val b = AudioTrack.Builder().setAudioAttributes(a.build()).setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(Wire.FRAME_BYTES * 4).setTransferMode(AudioTrack.MODE_STREAM)
        AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", Int::class.javaPrimitiveType).invoke(b, CALL_REDIRECT_PSTN)
        val t = b.build()
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); throw IllegalStateException("track not initialized") }
        return t
    }

    // ---- system services ----

    private fun service(name: String, iface: String): Any {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
    }

    private fun interceptable() = invoke(audio, "isPstnCallAudioInterceptable") as? Boolean

    private fun packagesForUid(uid: Int): List<String> = try {
        val pm = service("package", "android.content.pm.IPackageManager")
        @Suppress("UNCHECKED_CAST")
        (pm.javaClass.getMethod("getPackagesForUid", Int::class.javaPrimitiveType).invoke(pm, uid) as Array<String>?)?.toList().orEmpty()
    } catch (e: Throwable) { log("getPackagesForUid: ${cause(e)}"); emptyList() }

    /** Lets iBridge turn Wireless debugging on and off by itself (Settings.Global adb_wifi_enabled). */
    private fun grantSecureSettings() {
        try {
            val p = ProcessBuilder("pm", "grant", Wire.PACKAGE, "android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            log("WRITE_SECURE_SETTINGS granted (exit ${p.waitFor()}${if (out.isNotEmpty()) ", $out" else ""})")
        } catch (e: Exception) { log("pm grant: ${e.message}") }
    }

    /** True for a Boolean true only. */
    private fun isTrue(v: Any?) = v == true

    /**
     * Calls a hidden Binder method by name, filling its parameters by type: the [given] values in order where their
     * types fit, then the shell's package for the first String (the calling package), null for later Strings
     * (attribution tags), our token for an IBinder, 0 for an int (user 0). Returns the result, Unit for void, or null
     * when the call failed. Signatures differ between Android versions; the shape, not the arity, is what holds.
     */
    private fun invoke(target: Any, name: String, vararg given: Any): Any? {
        val methods = target.javaClass.methods.filter { it.name == name }.sortedBy { it.parameterTypes.size }
        for (m in methods) {
            val args = fill(m, given) ?: continue
            return try { m.invoke(target, *args) ?: Unit } catch (e: Throwable) { log("$name refused: ${cause(e)}"); null }
        }
        log("$name: no matching method")
        return null
    }

    private fun fill(m: Method, given: Array<out Any>): Array<Any?>? {
        val p = m.parameterTypes
        val args = arrayOfNulls<Any>(p.size)
        var next = 0
        var pkgUsed = false
        for (i in p.indices) {
            val g = given.getOrNull(next)
            val fits = g != null && (p[i].isInstance(g) || (p[i] == Int::class.javaPrimitiveType && g is Int) || (p[i] == Boolean::class.javaPrimitiveType && g is Boolean))
            args[i] = when {
                fits -> { next++; g }
                p[i] == String::class.java -> if (pkgUsed) null else SHELL_PKG.also { pkgUsed = true }
                p[i] == IBinder::class.java -> token
                p[i] == Int::class.javaPrimitiveType -> 0
                p[i] == Boolean::class.javaPrimitiveType -> false
                p[i] == Long::class.javaPrimitiveType -> 0L
                p[i].isPrimitive -> return null
                else -> null
            }
        }
        return if (next == given.size) args else null
    }

    private fun cause(t: Throwable): String {
        var c = t
        while (c.cause != null) c = c.cause!!
        return c.toString()
    }

    private fun log(s: String) = Log.i(TAG, "helper: $s")
}

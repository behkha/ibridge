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
import java.util.concurrent.atomic.AtomicInteger
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
 * It relays only the call iBridge answered or placed: `answer` and `dial` are refused while the Galaxy has another call,
 * and the relay stops when a second call takes the Galaxy.
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
    private const val UP_QUEUE = 8
    /** Frames the uplink waits for after running dry: absorbs Bluetooth bursts. Tunable (`tune`). */
    @Volatile private var upPrime = 2
    /** Frames the uplink keeps in hand: when the queue held more than this beyond the frame due for 1 s, the extra go. */
    @Volatile private var upSpare = 1
    /**
     * Samples the uplink track may hold ahead of the call. One frame (20 ms) measured 72 ms from write to the call (the
     * path itself adds ~50 ms), two frames 98 ms; half a frame starved it (underruns every write). Tunable (`tune`).
     */
    @Volatile private var trackFrames = Wire.FRAME

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
            } catch (e: Throwable) {
                if (open) Log.w(TAG, "helper: client read failed", e)
            }
            close("closed")
        }

        fun send(type: Int, payload: ByteArray) {
            if (!open) return
            try { synchronized(out) { Wire.write(out, type, payload) } } catch (e: Exception) { if (open) Log.w(TAG, "helper: write failed", e); close("write failed") }
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
            "prove" -> prove(c.optString("n"))
            "answer" -> answer()
            "hangup" -> { val r = invoke(telecom, "endCall"); log("endCall: $r") }
            "silence" -> invoke(telecom, "silenceRinger")
            "dial" -> dial(c.optString("to"), c.optString("comp"), c.optString("id"))
            "relay" -> if (!c.optBoolean("on")) Relay.stop("asked") // `answer` and `dial` arm it
            "dtmf" -> Relay.dtmf(c.optString("d").firstOrNull() ?: ' ')
            "tune" -> Relay.tune(c)
            "check" -> { lastState = null; check() } // iBridge accepted this connection: the next poll sends it the state
            else -> log("unknown command $name")
        }
    }

    /**
     * Sends iBridge's [nonce] back through a broadcast only a DUMP holder may send (Wire.PROOF_ACTION): iBridge takes
     * this peer for its helper only once it arrives. Off the client thread: `am` takes a moment to start.
     */
    private fun prove(nonce: String) {
        if (!Regex("[0-9a-f]{32}").matches(nonce)) { log("proof: bad nonce"); return }
        Thread({
            val t0 = SystemClock.elapsedRealtime()
            try {
                val p = ProcessBuilder("am", "broadcast", "-a", Wire.PROOF_ACTION, "-f", "0x10000000", // FLAG_RECEIVER_FOREGROUND
                    "-n", "${Wire.PACKAGE}/${Wire.PROOF_RECEIVER}", "--es", "n", nonce).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText()
                log("proof sent (exit ${p.waitFor()}, ${SystemClock.elapsedRealtime() - t0} ms)")
            } catch (e: Exception) { log("proof: ${e.message}") }
        }, "ibridge-proof").apply { isDaemon = true; start() }
    }

    /** Any call on the Galaxy besides one that only rings (PreciseCallState 1..6 in the foreground or background). */
    private fun otherCall(st: Triple<Int, Int, Int>) = st.second in 1..6 || st.third in 1..6

    /** Answers the ringing call for iBridge and arms the relay; refused while the Galaxy has another call (a waiting call included). */
    private fun answer() {
        val st = preciseState()
        if (otherCall(st) || st.first == 6) { error("answer refused: another call (ring=${st.first} fg=${st.second} bg=${st.third})"); return }
        if (invoke(telecom, "acceptRingingCall") == null) { error("answer failed"); return }
        Relay.arm()
    }

    /** Places a call for iBridge and arms the relay; refused while the Galaxy has any call. */
    private fun dial(to: String, comp: String, id: String) {
        if (to.isBlank()) { error("dial: no number"); return }
        val st = preciseState()
        if (otherCall(st) || st.first in 1..6) { error("dial refused: another call (ring=${st.first} fg=${st.second} bg=${st.third})"); return }
        val extras = Bundle()
        ComponentName.unflattenFromString(comp)?.takeIf { id.isNotEmpty() }?.let {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle(it, id))
        }
        if (invoke(telecom, "placeCall", Uri.fromParts("tel", to, null), extras) == null) { error("dial failed"); return }
        log("placeCall sent")
        Relay.arm()
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
        /** Bumped by every start: loops of an earlier relay see it and quit, even after a quick stop and start. */
        @Volatile private var gen = 0
        private var record: AudioRecord? = null
        private var track: AudioTrack? = null
        private var loops: List<Thread> = emptyList()
        private var micWas: Boolean? = null
        private var modeTaken = false
        private var volWas = -1
        private val upQueue = ArrayDeque<ByteArray>()
        private var dtmfLeft = 0
        private var dtmfLow = 0.0
        private var dtmfHigh = 0.0
        private var dtmfPhase = 0L
        // once-a-second stats, counted on the audio threads
        private val down = AtomicInteger(); private val up = AtomicInteger(); private val silent = AtomicInteger(); private val upDropped = AtomicInteger()
        /** Uplink queue at each take, and ms of sound the track held ahead of the call after each write (min/avg/max). */
        private val upDepth = Spread(); private val trackMs = Spread()
        private var underrunsBefore = 0

        /** Debug: new uplink buffer sizes, applied at once (`{"c":"tune","trackFrames":n,"upPrime":n,"upSpare":n}`). */
        @Synchronized fun tune(c: JSONObject) {
            if (c.has("upPrime")) upPrime = c.optInt("upPrime").coerceIn(1, 6)
            if (c.has("upSpare")) upSpare = c.optInt("upSpare").coerceIn(0, 6)
            if (c.has("trackFrames")) trackFrames = c.optInt("trackFrames").coerceIn(Wire.FRAME / 2, Wire.FRAME * 8)
            val got = track?.let { runCatching { it.setBufferSizeInFrames(trackFrames) }.getOrNull() }
            log("tune: upPrime $upPrime upSpare $upSpare trackFrames $trackFrames (track took ${got ?: "-"})")
        }

        /** Starts at once when a call is dialing, alerting, active or held; otherwise when one is. */
        @Synchronized fun arm() {
            if (running) return
            armed = true
            log("relay armed")
            lastState?.let { onState(it) }
        }

        /**
         * The relayed call is the one dialing, alerting or active in the foreground with nothing in the background. A held or
         * background call (the user answered a waiting call on the Galaxy) ends the relay, and so does the relayed call
         * ending, even with another call still ringing.
         */
        @Synchronized fun onState(st: Triple<Int, Int, Int>) {
            val (_, fg, bg) = st
            val ours = fg in setOf(1, 3, 4) && bg !in 1..6
            if (armed && !running && ours) start()
            else if (running && !ours) stop(if (bg in 1..6) "another call" else "call ended")
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
            underrunsBefore = 0
            synchronized(upQueue) { upQueue.clear() }
            running = true
            val g = ++gen
            loops = listOf(Thread({ downLoop(r, g) }, "ibridge-down"), Thread({ upLoop(t, g) }, "ibridge-up"))
            loops.forEach { it.start() }
            Thread({ statsLoop(g) }, "ibridge-stats").apply { isDaemon = true; start() }
            log("relay on (opened in ${SystemClock.elapsedRealtime() - t0} ms; mic was muted=$micWas, voice volume was $volWas)")
            event(JSONObject().put("e", "relay").put("on", true).put("volWas", volWas))
        }

        /** [err] tells iBridge the audio path broke (it ends the call on the iPhone as failed). */
        @Synchronized fun stop(why: String, err: String? = null) {
            armed = false
            if (!running) { restore(); return }
            running = false
            // stop() unblocks a read or write in progress; the loops are let go (at most 500 ms each) before release.
            record?.let { runCatching { it.stop() } }
            track?.let { runCatching { it.stop() } }
            loops.filter { it !== Thread.currentThread() }.forEach { it.join(500) }
            loops = emptyList()
            record?.release(); track?.release()
            record = null; track = null
            restore()
            log("relay off ($why)")
            event(JSONObject().put("e", "relay").put("on", false).putOpt("err", err))
        }

        /** A loop of relay [g] broke: the Galaxy gets its call back. Ignored once that relay is over. */
        @Synchronized private fun fail(g: Int, what: String) {
            if (g != gen || !running) return
            log("error: $what")
            stop(what, err = what)
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
                while (upQueue.size > UP_QUEUE) { upQueue.removeFirst(); upDropped.incrementAndGet() }
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

        private fun live(g: Int) = running && gen == g

        private fun downLoop(r: AudioRecord, g: Int) {
            urgent()
            val buf = ShortArray(Wire.FRAME)
            val bytes = ByteBuffer.allocate(Wire.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            try {
                r.startRecording()
                while (live(g)) {
                    var got = 0
                    while (got < Wire.FRAME && live(g)) {
                        val n = r.read(buf, got, Wire.FRAME - got)
                        if (n < 0) { if (live(g)) fail(g, "downlink read $n"); return }
                        got += n
                    }
                    bytes.clear(); bytes.asShortBuffer().put(buf)
                    client?.send(Wire.PCM, bytes.array().copyOf())
                    down.incrementAndGet()
                }
            } catch (e: Throwable) { if (live(g)) fail(g, "downlink: ${cause(e)}") }
        }

        /**
         * Paced by the call's clock: each blocking write takes the next frame from iBridge, or silence. Frames come over
         * Bluetooth in bursts, so this is a small jitter buffer: after running dry it waits for [upPrime] frames, and
         * when the queue held more than [upSpare] frames beyond the one due for 1 s (clock drift, a burst after a stall)
         * it drops that many, each at a quiet frame or within half a second. After running dry it trims nothing for 10 s.
         * The track's own buffer is cut to [trackFrames], so the sound written waits there no longer than that.
         */
        private fun upLoop(t: AudioTrack, g: Int) {
            urgent()
            val pcm = ShortArray(Wire.FRAME)
            var primed = false
            var lowest = Int.MAX_VALUE
            var n = 0
            var excess = 0
            var excessWait = 0
            var holdOff = 0 // frames left before trimming may start again after running dry
            var written = 0L
            val ts = android.media.AudioTimestamp()
            try {
                log("uplink track: ${t.setBufferSizeInFrames(trackFrames)} of ${t.bufferCapacityInFrames} frames, latency ${trackLatency(t)} ms")
                t.play()
                while (live(g)) {
                    val frame = synchronized(upQueue) {
                        val size = upQueue.size
                        if (size == 0) { if (primed) holdOff = 500; primed = false; excess = 0 } else if (size >= upPrime) primed = true
                        if (holdOff > 0) holdOff--
                        lowest = minOf(lowest, size)
                        upDepth.add(size)
                        if (++n >= 50) {
                            if (excess == 0 && holdOff == 0 && lowest > upSpare + 1) { excess = lowest - upSpare - 1; excessWait = 0 }
                            n = 0; lowest = Int.MAX_VALUE
                        }
                        if (excess > 0 && upQueue.size > upSpare + 1 && (quiet(upQueue.first()) || ++excessWait > 25)) {
                            upQueue.removeFirst(); upDropped.incrementAndGet(); excess--; excessWait = 0
                        }
                        if (primed) upQueue.pollFirst() else null
                    }
                    if (frame != null) { ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm); up.incrementAndGet() }
                    else { pcm.fill(0); silent.incrementAndGet() }
                    mixDtmf(pcm)
                    val w = t.write(pcm, 0, Wire.FRAME)
                    if (w < 0) { if (live(g)) fail(g, "uplink write $w"); return }
                    written += w
                    // Sound written but not yet played out at the call: the track's whole buffer, HAL included.
                    val played = if (t.getTimestamp(ts)) ts.framePosition + (System.nanoTime() - ts.nanoTime) * Wire.RATE / 1_000_000_000L
                        else t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                    trackMs.add(((written - played) * 1000 / Wire.RATE).toInt())
                }
            } catch (e: Throwable) { if (live(g)) fail(g, "uplink: ${cause(e)}") }
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

        private fun statsLoop(g: Int) {
            while (live(g)) {
                SystemClock.sleep(1000)
                if (!live(g)) return
                enforce()
                val d = down.getAndSet(0); val u = up.getAndSet(0); val s = silent.getAndSet(0); val x = upDropped.getAndSet(0)
                val q = upDepth.take(); val tm = trackMs.take()
                val runs = (track?.underrunCount ?: 0).let { total -> (total - underrunsBefore).also { underrunsBefore = total } }
                Log.i(TAG, "helper: relay down=$d up=$u silent=$s upDropped=$x upQueue=$q trackMs=$tm underruns=$runs")
                event(JSONObject().put("e", "stats").put("down", d).put("up", u).put("silent", s).put("upDropped", x).put("upQueue", q)
                    .put("track", tm).put("underruns", runs))
            }
        }
    }

    /** min/avg/max of the values since the last [take], for the once-a-second stats. */
    private class Spread {
        private var n = 0; private var sum = 0L; private var min = Int.MAX_VALUE; private var max = Int.MIN_VALUE
        @Synchronized fun add(v: Int) { n++; sum += v; if (v < min) min = v; if (v > max) max = v }
        @Synchronized fun take(): String = (if (n == 0) "-" else "$min/${sum / n}/$max").also { n = 0; sum = 0; min = Int.MAX_VALUE; max = Int.MIN_VALUE }
    }

    /** Under -40 dBFS: a frame that can be dropped unheard. */
    private fun quiet(pcm: ByteArray): Boolean {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0L
        while (b.hasRemaining()) { val v = b.get().toLong(); sum += v * v }
        return sum / Wire.FRAME < 107_374 // (0.01 × 32768)²
    }

    /** The audio threads ask for real-time priority; a shell process may be refused it (logged once per relay). */
    private fun urgent() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        catch (e: Exception) { log("${Thread.currentThread().name}: no urgent audio priority (${e.message})") }
    }

    /** AudioTrack's hidden getLatency(): the whole output path in ms, or -1. */
    private fun trackLatency(t: AudioTrack): Int = runCatching { AudioTrack::class.java.getMethod("getLatency").invoke(t) as Int }.getOrDefault(-1)

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

package app.ibridge.call

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Process
import android.util.Log
import app.ibridge.call.helper.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** `status.audio` (PROTOCOL.md 8). */
enum class AudioState(val wire: String) { SETUP("setup"), READY("ready"), OFF("off"), UNSUPPORTED("unsupported") }

/** Why calls stay on this phone, for the home screen's warning rows. */
enum class AudioBlock { NONE, NEEDS_WIFI, AUTO_BLOCKER, UNSUPPORTED }

enum class PairStep { IDLE, WAITING, TRYING, WRONG, DONE }

data class AudioUi(
    val state: AudioState = AudioState.SETUP,
    val block: AudioBlock = AudioBlock.NONE,
    val pair: PairStep = PairStep.IDLE,
    /** Live while the setup sheet is open. */
    val developerOptions: Boolean = false,
    val wireless: Boolean = false,
    val pairingScreen: Boolean = false,
)

/**
 * Keeps the shell helper running and connected (PROTOCOL.md 8 "Availability"). Starts it over Wireless debugging when
 * it is down (service start, boot, Wi-Fi back, helper gone), turning Wireless debugging on only for that and back
 * off if it was off; reconnects every 6 days so Android's 7-day key expiry never revokes the pairing.
 * All setup work runs on one background thread.
 */
object CallAudio {
    private const val TAG = "iBridge"
    private val RETRY_S = longArrayOf(10, 30, 120, 600)
    private const val REFRESH_MS = 6 * 86_400_000L

    interface Listener {
        fun onAudioState(state: AudioState)
        fun onHelperEvent(e: JSONObject)
        fun onDownlink(pcm: ByteArray)
    }

    private val _ui = MutableStateFlow(AudioUi())
    val ui: StateFlow<AudioUi> = _ui.asStateFlow()
    val state get() = _ui.value.state

    @Volatile var listener: Listener? = null
    private lateinit var ctx: Context
    lateinit var adb: Adb
        private set
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "ibridge-audio") }
    @Volatile private var helper: HelperClient? = null
    @Volatile private var hello: JSONObject? = null
    private var wifi = false
    /** Last start attempt failed because Wireless debugging would not turn on. */
    private var refused = false
    private var retry: ScheduledFuture<*>? = null

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        adb = Adb(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = worker.execute { wifi = true; ensure("Wi-Fi connected") }
                override fun onLost(network: Network) = worker.execute { wifi = false; publish() }
            },
        )
        worker.scheduleWithFixedDelay({ ensure("periodic") }, 6, 6, TimeUnit.HOURS)
        // A helper started some other way (over USB, or a start that came up late) is picked up within 30 s.
        worker.scheduleWithFixedDelay({ if (helper?.open != true && connect()) publish() }, 30, 30, TimeUnit.SECONDS)
    }

    /** Service start, boot, or anything that may have let the helper start now. */
    fun kick(reason: String) = worker.execute { ensure(reason) }

    fun command(c: JSONObject): Boolean {
        val ok = helper?.takeIf { hello != null }?.command(c) == true
        if (ok && c.optString("c") == "relay") relayVolume = if (c.optBoolean("on")) voiceVolume() else -1
        return ok
    }

    /** The voice-call volume before the helper turned it down, while a relay runs; -1 otherwise. */
    @Volatile private var relayVolume = -1

    private fun voiceVolume(): Int = runCatching {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
    }.getOrDefault(-1)

    /**
     * The helper died mid-relay without its own restore (SIGKILL, crash): give the Galaxy its microphone and earpiece back,
     * or its next calls would be silent. The audio mode needs nothing: Android drops a dead process's mode request.
     */
    private fun restoreAfterHelperDeath() {
        val was = relayVolume.also { relayVolume = -1 }
        if (was < 0) return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isMicrophoneMute = false
            am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, was, 0)
            Log.w(TAG, "audio: helper gone mid-relay; microphone and voice volume $was restored here")
        }.onFailure { Log.w(TAG, "audio: restore after helper death failed: ${it.message}") }
    }
    fun uplink(pcm: ByteArray) { helper?.pcm(pcm) }

    // ---- pairing (the setup sheet and its notification) ----

    private val watch = Executors.newSingleThreadScheduledExecutor { Thread(it, "ibridge-setup") }
    private var watching: ScheduledFuture<*>? = null
    private var stopPairingWatch: (() -> Unit)? = null

    /**
     * The setup sheet opened: tick its steps as Developer options, Wireless debugging and the pairing screen come on,
     * and post the code notification until pairing succeeds.
     */
    fun beginSetup() = watch.execute {
        if (watching != null) return@execute
        Log.i(TAG, "audio: setup sheet open")
        if (!adb.paired) setPair(PairStep.WAITING)
        stopPairingWatch = adb.watchPairing { on -> _ui.update { it.copy(pairingScreen = on) } }
        watching = watch.scheduleWithFixedDelay({
            _ui.update { it.copy(developerOptions = adb.developerOptionsOn, wireless = adb.wirelessOn) }
        }, 0, 1, TimeUnit.SECONDS)
    }

    fun endSetup() = watch.execute {
        watching?.cancel(false); watching = null
        stopPairingWatch?.invoke(); stopPairingWatch = null
        _ui.update { it.copy(pairingScreen = false) }
        if (_ui.value.pair != PairStep.DONE) setPair(PairStep.IDLE)
    }

    private fun setPair(step: PairStep) {
        _ui.update { it.copy(pair = step) }
        PairingNotice.show(ctx, step)
    }

    /** The code typed into the notification. */
    fun pair(code: String) = worker.execute {
        setPair(PairStep.TRYING)
        try {
            adb.pair(code)
            setPair(PairStep.DONE)
            ensure("paired")
        } catch (e: Throwable) {
            Log.w(TAG, "adb pairing failed: ${e.javaClass.simpleName} ${e.message}")
            setPair(PairStep.WRONG)
        }
    }

    // ---- keeping the helper up ----

    private fun ensure(reason: String) {
        if (helper?.open == true && hello != null) {
            if (adb.paired && System.currentTimeMillis() - adb.lastUse > REFRESH_MS && wifi) refreshKey()
            publish(); return
        }
        if (connect()) { failures = 0; publish(); return }
        if (!adb.paired) { publish(); return }
        if (!wifi) { Log.i(TAG, "audio: helper down, no Wi-Fi to start it ($reason)"); publish(); return }
        if (launch(reason)) failures = 0 else scheduleRetry()
        publish()
    }

    /** Failed starts in a row; retries back off 10 s, 30 s, 2 min, then every 10 min while Wi-Fi is on. */
    private var failures = 0

    private fun scheduleRetry() {
        val delay = RETRY_S[minOf(failures, RETRY_S.size - 1)]
        failures++
        retry?.cancel(false)
        retry = worker.schedule({ ensure("retry $failures") }, delay, TimeUnit.SECONDS)
        Log.i(TAG, "audio: next start attempt in $delay s")
    }

    /** Connects to a running helper and checks its version. False when none runs (or it was outdated and quit). */
    private fun connect(): Boolean {
        hello = null
        val c = HelperClient(secret(), ::onEvent, { listener?.onDownlink(it) }, ::onHelperClosed)
        try { c.connect() } catch (e: Exception) {
            if (e is SecurityException) Log.w(TAG, "audio: ${e.message}")
            return false
        }
        helper = c
        val got = java.util.concurrent.CountDownLatch(1)
        helloLatch = got
        c.command(JSONObject().put("c", "hello"))
        got.await(3, TimeUnit.SECONDS)
        val h = hello
        if (h == null) { Log.w(TAG, "audio: helper did not answer hello"); c.close(); return false }
        // Another build of the same helper protocol keeps working until iBridge can start the new one.
        val canRestart = adb.paired && wifi
        if (h.optInt("ver") != Wire.VERSION || (h.optString("build") != build() && canRestart)) {
            Log.i(TAG, "audio: helper is outdated (v${h.optInt("ver")} ${h.optString("build")}); restarting it")
            c.command(JSONObject().put("c", "quit"))
            c.close()
            Thread.sleep(500)
            return false
        }
        Log.i(TAG, "audio: helper connected, pid ${h.optInt("pid")}, interceptable=${h.optBoolean("interceptable")}")
        c.command(JSONObject().put("c", "check"))
        return true
    }

    @Volatile private var helloLatch: java.util.concurrent.CountDownLatch? = null

    private fun onEvent(e: JSONObject) {
        if (e.optString("e") == "hello") { hello = e; helloLatch?.countDown(); return }
        if (e.optString("e") == "check") { Log.i(TAG, "audio: call paths outside a call: $e"); return }
        listener?.onHelperEvent(e)
    }

    private fun onHelperClosed() = worker.execute {
        if (helper?.open == true) return@execute
        helper = null
        hello = null
        Log.i(TAG, "audio: helper connection closed")
        restoreAfterHelperDeath()
        publish()
        retry?.cancel(false)
        retry = worker.schedule({ ensure("helper gone") }, 3, TimeUnit.SECONDS)
    }

    /** Shared with the helper on its command line; app-private, kept across updates (no backup). */
    private fun secret(): String {
        val prefs = ctx.getSharedPreferences("helper", Context.MODE_PRIVATE)
        return prefs.getString("secret", null) ?: Wire.nonce().plus(Wire.nonce()).also { prefs.edit().putString("secret", it).apply() }
    }

    private fun build() = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()

    /** The one shell command that (re)starts the helper from this APK, detached from the ADB session. */
    fun launchCommand(): String {
        val apk = ctx.applicationInfo.sourceDir
        return "pkill -f '^ibridge_call'; CLASSPATH=$apk setsid nohup app_process /system/bin --nice-name=ibridge_call " +
            "app.ibridge.call.helper.CallHelper ${Process.myUid()} ${build()} ${secret()} </dev/null >/dev/null 2>&1 &"
    }

    /** Wireless debugging on (if it is off), run [command], back off. False when it could not run. */
    private fun withWireless(what: String, command: String): Boolean {
        val wasOn = adb.wirelessOn
        if (!wasOn && !adb.setWireless(true)) {
            refused = true
            Log.w(TAG, "audio: Wireless debugging did not turn on ($what); autoBlocker=${adb.autoBlocker} canSwitch=${adb.canSwitch}")
            return false
        }
        refused = false
        return try {
            adb.shell(command)
            Log.i(TAG, "audio: $what over Wireless debugging")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "audio: $what failed: ${e.javaClass.simpleName} ${e.message}")
            if (e is SSLException || e.cause is SSLException) adb.forget() // the phone no longer trusts our key
            false
        } finally {
            if (!wasOn) adb.setWireless(false)
        }
    }

    private fun launch(reason: String): Boolean {
        Log.i(TAG, "audio: starting the helper ($reason)")
        if (!withWireless("helper started", launchCommand())) return false
        repeat(10) {
            Thread.sleep(300)
            if (connect()) {
                // Right after pairing, Wireless debugging is on because the user turned it on; iBridge keeps it off
                // from now on and turns it on only for a few seconds when it must start the helper again.
                if (reason == "paired") Log.i(TAG, "audio: Wireless debugging off after pairing: ${adb.setWireless(false)} (canSwitch=${adb.canSwitch})")
                return true
            }
        }
        Log.w(TAG, "audio: helper did not come up")
        return false
    }

    private fun refreshKey() { withWireless("key refreshed", "true") }

    private fun publish() {
        val h = hello?.takeIf { helper?.open == true }
        val state = when {
            h != null -> if (h.optBoolean("interceptable")) AudioState.READY else AudioState.UNSUPPORTED
            !adb.paired -> AudioState.SETUP
            else -> AudioState.OFF
        }
        val block = when (state) {
            AudioState.UNSUPPORTED -> AudioBlock.UNSUPPORTED
            AudioState.OFF -> when {
                adb.autoBlocker == true || (refused && wifi) -> AudioBlock.AUTO_BLOCKER
                !wifi -> AudioBlock.NEEDS_WIFI
                else -> AudioBlock.NONE // starting, or a start that failed for a passing reason: the next trigger retries
            }
            else -> AudioBlock.NONE
        }
        val before = _ui.value.state
        _ui.update { it.copy(state = state, block = block) }
        if (state != before) {
            Log.i(TAG, "audio: ${before.wire} → ${state.wire}")
            listener?.onAudioState(state)
        }
    }
}

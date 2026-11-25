package app.ibridge.call

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * iBridge's own ADB client for this phone's Wireless debugging (Kadb, Apache-2.0): pair once with the code the
 * user types, then connect and run one shell command to start the call helper. Blocking; call off the main thread.
 *
 * The RSA key Android trusts after pairing lives in app-private no-backup storage. Android forgets a key not used
 * for 7 days (`adb_allowed_connection_time`), so [lastUse] lets the caller connect again before that.
 */
class Adb(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "adb").apply { mkdirs() }
    private val certFile = File(dir, "cert.der")
    private val keyFile = File(dir, "key.der")
    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** True once a pairing succeeded. Unpairing on the phone is noticed only when a connect is refused. */
    val paired get() = prefs.getBoolean("paired", false)
    val lastUse get() = prefs.getLong("last_use", 0)

    /** The phone refused our key (unpaired in Settings, or expired): setup starts over. */
    fun forget() { prefs.edit().putBoolean("paired", false).apply(); Log.i(TAG, "adb: pairing forgotten") }

    private fun loadKey() {
        if (certFile.exists() && keyFile.exists()) {
            runCatching { KadbCert.set(certFile.readBytes(), keyFile.readBytes()); return }
                .onFailure { Log.w(TAG, "adb key unreadable, making a new one: ${it.javaClass.simpleName}") }
        }
        val (cert, key) = KadbCert.get(cn = "iBridge", ou = "iBridge", o = "iBridge", l = "-", st = "-", c = "-",
            notAfter = System.currentTimeMillis() + 30 * 365L * 86_400_000)
        certFile.writeBytes(cert)
        keyFile.writeBytes(key)
        prefs.edit().putBoolean("paired", false).apply() // a new key is not paired with anything
    }

    // ---- Wireless debugging switch ----

    val wirelessOn get() = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    val developerOptionsOn get() = Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val canSwitch get() = context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Samsung Auto Blocker (One UI 6+). Null when the phone does not tell this app. */
    val autoBlocker: Boolean? get() = try {
        Settings.Secure.getInt(context.contentResolver, "rampart_main_switch_enabled").let { it == 1 }
    } catch (e: Exception) { null }

    /** Turns Wireless debugging on or off. False when Android did not take it (Auto Blocker, no Wi-Fi, no permission). */
    fun setWireless(on: Boolean): Boolean {
        if (!canSwitch) return wirelessOn == on
        try { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", if (on) 1 else 0) } catch (e: SecurityException) { return false }
        repeat(15) { if (wirelessOn == on) return true; Thread.sleep(200) }
        return wirelessOn == on
    }

    // ---- pairing and running ----

    /** Pairs with the code shown under "Pair device with pairing code". Throws on a wrong code or no pairing screen. */
    fun pair(code: String) {
        loadKey()
        val port = discover(PAIRING, 8_000) ?: throw IllegalStateException("no pairing service: is “Pair device with pairing code” open?")
        runBlocking { Kadb.pair(LOCALHOST, port, code.trim(), "iBridge") }
        prefs.edit().putBoolean("paired", true).putLong("last_use", System.currentTimeMillis()).apply()
        Log.i(TAG, "adb: paired")
    }

    /** Connects over Wireless debugging (which must be on) and runs [command]; returns its output. */
    fun shell(command: String): String {
        loadKey()
        // mDNS can still hold the port of an earlier Wireless debugging session: try every port seen for this phone.
        // Right after Wireless debugging turns on, the new port is announced a moment later: look again a few times.
        val refused = HashSet<Int>()
        var last: Exception? = null
        repeat(4) { round ->
            if (round > 0) Thread.sleep(2_000)
            for (port in discoverAll(CONNECT, 8_000, settleMs = 1_500)) {
                if (!refused.add(port)) continue
                try {
                    Kadb.create(LOCALHOST, port, connectTimeout = 5_000, socketTimeout = 15_000).use { k ->
                        val r = k.shell(command)
                        prefs.edit().putLong("last_use", System.currentTimeMillis()).apply()
                        return r.allOutput
                    }
                } catch (e: java.net.ConnectException) {
                    Log.i(TAG, "adb: port $port refused (stale mDNS record)"); last = e
                }
            }
        }
        throw last ?: IllegalStateException("no Wireless debugging service")
    }

    /**
     * Finds this phone's own adbd service of [type] by mDNS (other phones on the same Wi-Fi advertise too) and
     * returns its port. Null after [timeoutMs].
     */
    private fun discover(type: String, timeoutMs: Long): Int? = discoverAll(type, timeoutMs, settleMs = 0).firstOrNull()

    /** Every port of this phone's [type] service seen until [settleMs] after the first one (or [timeoutMs] without one), last resolved first. */
    private fun discoverAll(type: String, timeoutMs: Long, settleMs: Long): List<Int> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val own = ownAddresses()
        val found = CountDownLatch(1)
        val ports = java.util.LinkedHashSet<Int>()
        var port: Int? = null
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
        val pending = ArrayDeque<NsdServiceInfo>()
        lateinit var resolveNext: () -> Unit
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { resolving.set(false); resolveNext() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                if (hosts.any { it in own }) { synchronized(ports) { ports.add(info.port) }; port = info.port; found.countDown() }
                resolving.set(false); resolveNext()
            }
        }
        resolveNext = {
            synchronized(pending) {
                if ((settleMs > 0 || port == null) && !resolving.get()) pending.removeFirstOrNull()?.let {
                    resolving.set(true)
                    @Suppress("DEPRECATION") nsd.resolveService(it, resolver)
                }
            }
        }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(pending) { pending.addLast(info) }; resolveNext() }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            if (found.await(timeoutMs, TimeUnit.MILLISECONDS) && settleMs > 0) Thread.sleep(settleMs)
        } finally { runCatching { nsd.stopServiceDiscovery(discovery) } }
        return synchronized(ports) { ports.toList().asReversed() }
    }

    /**
     * Watches for this phone's pairing service, which exists only while "Pair device with pairing code" is open.
     * Returns the function that stops watching.
     */
    fun watchPairing(onVisible: (Boolean) -> Unit): () -> Unit {
        val nsd = context.getSystemService(NsdManager::class.java)
        val own = ownAddresses()
        val seen = HashSet<String>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        lateinit var next: () -> Unit
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) { synchronized(queue) { resolving = false }; next() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val hosts = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
                if (hosts.any { it in own || it.isLoopbackAddress }) synchronized(seen) { seen += info.serviceName; onVisible(true) }
                synchronized(queue) { resolving = false }; next()
            }
        }
        next = {
            synchronized(queue) {
                if (!resolving) queue.removeFirstOrNull()?.let { resolving = true; @Suppress("DEPRECATION") nsd.resolveService(it, resolver) }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { synchronized(queue) { queue.addLast(info) }; next() }
            override fun onServiceLost(info: NsdServiceInfo) { synchronized(seen) { if (seen.remove(info.serviceName)) onVisible(seen.isNotEmpty()) } }
        }
        nsd.discoverServices(PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
        return { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    private fun ownAddresses(): Set<InetAddress> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }.toSet()

    companion object {
        const val TAG = "iBridge"
        const val LOCALHOST = "127.0.0.1"
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
    }
}

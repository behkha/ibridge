package app.ibridge.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import app.ibridge.core.Deframer
import app.ibridge.core.ErrorCode
import app.ibridge.core.Framer
import app.ibridge.core.LinkEngine
import app.ibridge.core.Proto
import app.ibridge.core.ProtocolException
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GATT peripheral (PROTOCOL.md sections 1-2). All protocol work runs on one thread through [post],
 * so the engine and the outbound queue never see concurrent calls.
 *
 * The GATT server sees every LE link the phone has (watches, earbuds). A central only takes the one
 * iBridge slot when it subscribes to TX or writes RX, and loses it if no session is live 20 s later.
 */
@SuppressLint("MissingPermission")
class Gatt(
    private val context: Context,
    private val newEngine: (send: (ByteArray) -> Unit, onLive: () -> Unit) -> LinkEngine,
    private val onDisconnected: () -> Unit,
) {
    /** Audio priority: a relayed call's frames pass through here every 20 ms. */
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO); r.run() }, "ibridge-gatt")
    }

    /** Runs [block] on the worker. A throw is logged and drops the link; it never kills the app. */
    fun post(delayMs: Long = 0, block: () -> Unit) {
        val safe = Runnable {
            try { block() } catch (t: Throwable) {
                Log.e(TAG, "worker task failed", t)
                device?.let { drop(it) }
            }
        }
        if (delayMs == 0L) worker.execute(safe) else worker.schedule(safe, delayMs, TimeUnit.MILLISECONDS)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var tx: BluetoothGattCharacteristic

    private var device: BluetoothDevice? = null
    private var subscribed = false
    private var mtu = 23
    private var deframer = Deframer()
    private var framer = Framer()
    /** Whole packets waiting; split into chunks only when they start, so an `AUDIO` packet can go first (PROTOCOL.md 2). */
    private val outbox = ArrayDeque<ByteArray>()
    /** Plain `AUDIO` frames waiting, at most [AUDIO_QUEUE]; sealed when they start, so dropped ones use no counter. */
    private val audioOut = ArrayDeque<ByteArray>()
    /** When each of [audioOut] was queued (nanoTime), for [audioWait]. */
    private val audioAt = ArrayDeque<Long>()
    /** Time an `AUDIO` frame waited here before its notification, and from the notification to the stack taking it. */
    val audioWait = app.ibridge.call.Lag()
    val audioSent = app.ibridge.call.Lag()
    private var audioNotifiedAt = 0L
    /** A GATT client on the iPhone's link, held only to ask for a short connection interval during a call ([setFastLink]). */
    private var linkClient: BluetoothGatt? = null
    private var fastLink = false
    /** The link's connection interval in ms, from the stack (0 until it reports one). */
    @Volatile var intervalMs = 0.0
        private set
    /** Chunks of the packet being sent. */
    private val chunks = ArrayDeque<ByteArray>()
    private var inFlight = false
    /** Pulled when no audio frame waits, for the debug `bench`: lets it send as fast as flow control allows. */
    var audioSource: (() -> ByteArray?)? = null
    var audioDropped = 0L
        private set
    val audioQueued get() = audioOut.size
    var engine: LinkEngine? = null
        private set

    private var advertising = false
    private var lowLatency = false
    /** When Android last started or lost the iPhone: advertising is fast right after, so a suspended iPhone finds it. */
    private var unlinkedSince = System.currentTimeMillis()
    private var advertisingMode = -1

    private fun wantedMode(): Int {
        val away = System.currentTimeMillis() - unlinkedSince
        return when {
            lowLatency || away < 2 * 60_000 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY // ~100 ms
            away < 12 * 60_000 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED // ~250 ms
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER // ~1 s
        }
    }

    /** Re-checks the advertising mode at the 2- and 12-minute marks after a disconnect. */
    private fun scheduleModeChecks() {
        for (at in longArrayOf(2 * 60_000L + 500, 12 * 60_000L + 500)) post(delayMs = at) {
            if (advertising && wantedMode() != advertisingMode) { stopAdvertising(); advertise() }
        }
    }

    fun start() = post {
        if (server != null) return@post
        val adapter = manager.adapter ?: return@post
        if (!adapter.isEnabled) return@post
        val s = manager.openGattServer(context, callback) ?: return@post
        val service = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            Proto.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        tx = BluetoothGattCharacteristic(Proto.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        // Encrypted-only: the first subscribe makes iOS bond with this phone (one system prompt on each side).
        // Bonded, iOS resolves Android's rotating address and reconnects by itself, even while iBridge is suspended.
        tx.addDescriptor(BluetoothGattDescriptor(Proto.CCCD, BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        s.addService(service) // onServiceAdded releases a link the iPhone kept from an earlier run
        server = s
        unlinkedSince = System.currentTimeMillis()
        advertise()
        scheduleModeChecks()
    }

    fun stop() = post {
        stopAdvertising()
        closeLinkClient()
        device?.let { server?.cancelConnection(it) }
        server?.close()
        server = null
        resetConnection()
    }

    /** Low latency while the pairing sheet is open, low power otherwise. */
    fun setFastAdvertising(fast: Boolean) = post {
        if (fast == lowLatency) return@post
        lowLatency = fast
        if (advertising) { stopAdvertising(); advertise() }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed $errorCode")
            post { advertising = false }
        }
    }

    private fun advertise() {
        if (advertising || engine?.isLive == true || server == null) return
        val adv = manager.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(wantedMode().also { advertisingMode = it })
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(Proto.SERVICE)).build()
        adv.startAdvertising(settings, data, advertiseCallback)
        advertising = true
    }

    private fun stopAdvertising() {
        if (!advertising) return
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    /** Queues one packet for the connected iPhone. Call on [worker]. */
    fun sendPacket(packet: ByteArray) {
        if (device == null) return
        outbox.add(packet)
        pump()
    }

    /** Queues one `AUDIO` plaintext ahead of other packets; keeps the newest [AUDIO_QUEUE]. Call on [worker]. */
    fun sendAudio(plain: ByteArray) {
        if (device == null || engine?.isLive != true) return
        audioOut.add(plain); audioAt.add(System.nanoTime())
        while (audioOut.size > AUDIO_QUEUE) { audioOut.removeFirst(); audioAt.removeFirst(); audioDropped++ }
        pump()
    }

    /** Wakes the pump after [audioSource] got something to send. */
    fun kick() = pump()

    private fun nextPacket(): ByteArray? {
        val e = engine
        val queued = audioOut.poll()?.also { audioWait.add(audioAt.poll()!!) }
        val audio = queued ?: audioSource?.invoke()
        if (audio != null && e != null) e.sealAudio(audio)?.let { if (queued != null) audioNotifiedAt = System.nanoTime(); return it }
        return outbox.poll()
    }

    private fun pump() {
        if (inFlight || !subscribed) return
        val d = device ?: return
        if (chunks.isEmpty()) nextPacket()?.let { chunks.addAll(framer.split(it, mtu - 3)) }
        val chunk = chunks.poll() ?: return
        inFlight = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(d, tx, false, chunk) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = chunk
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(d, tx, false) == true
        }
        if (!ok) {
            Log.w(TAG, "notify failed, dropping link")
            drop(d)
        }
    }

    /** Gives the slot to [d] if it is free. False when another central holds it. */
    private fun claim(d: BluetoothDevice): Boolean {
        if (device == d) return true
        if (device != null) return false
        device = d
        engine = newEngine({ p -> sendPacket(p) }, {
            post { stopAdvertising(); prefs.edit().putString("iphone", d.address).apply() }
        })
        Log.i(TAG, "central claimed the link")
        post(delayMs = 20_000) { if (device == d && engine?.isLive != true) { Log.w(TAG, "no session in 20 s"); drop(d) } }
        return true
    }

    private fun resetConnection() {
        closeLinkClient()
        engine?.close()
        device = null
        subscribed = false
        mtu = 23
        deframer = Deframer()
        framer = Framer()
        outbox.clear()
        audioOut.clear()
        audioAt.clear()
        audioNotifiedAt = 0
        chunks.clear()
        audioSource = null
        inFlight = false
        engine = null
    }

    private fun drop(d: BluetoothDevice) {
        closeLinkClient() // first: while it holds the link, cancelConnection would not end it
        server?.cancelConnection(d)
        resetConnection()
        onDisconnected()
        unlinkedSince = System.currentTimeMillis()
        advertise()
        scheduleModeChecks()
    }

    private fun fail(d: BluetoothDevice, e: ProtocolException) {
        Log.w(TAG, "protocol: ${e.error} ${e.message}")
        // Best effort: queue the ERROR, then drop once it has had a moment to go out.
        sendPacket(LinkEngine.error(e.error, e.message ?: ""))
        post(delayMs = 300) { if (device == d) drop(d) }
    }

    /**
     * A relayed call asks for the shortest connection interval the iPhone grants (iOS defaults to 30 ms, which holds each
     * `AUDIO` frame up to 30 ms and makes them arrive in bursts). Only a GATT client can ask, so one is opened on the
     * iPhone's link and kept until the link ends: closing it would leave no app holding the link, and the stack would
     * drop it a second later (the same rule [releaseLeftoverLink] uses). Off asks for the balanced interval again.
     */
    fun setFastLink(on: Boolean) = post {
        fastLink = on
        val d = device ?: return@post
        val c = linkClient
        if (c != null) { priority(c); return@post }
        if (!on) return@post
        linkClient = d.connectGatt(context, false, linkCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun priority(c: BluetoothGatt) {
        val ok = c.requestConnectionPriority(if (fastLink) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        Log.i(TAG, "link: asked for a ${if (fastLink) "short" else "balanced"} connection interval ($ok)")
    }

    private fun closeLinkClient() {
        linkClient?.let { it.disconnect(); it.close() }
        linkClient = null
    }

    private val linkCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = post {
            if (g !== linkClient) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) priority(g) else { g.close(); linkClient = null }
        }
    }

    /** The iPhone that last held a live session, so a later run can find it on a leftover link. */
    private val prefs = context.getSharedPreferences("gatt", Context.MODE_PRIVATE)

    /**
     * A previous run may have died with the iPhone still connected. The iPhone opened that radio link, and a
     * suspended iPhone app is woken neither by Service Changed nor by anything sent over the link, so it would
     * sit on a dead session. A GATT server cannot drop a link it did not open, but a GATT client can: open and
     * release a client connection on it, and with no app holding the link the stack drops it after 1 s
     * (PROTOCOL.md section 1). The iPhone wakes on the disconnect and its pending connect reaches this run.
     * Only the link of the iPhone that last held a live session is touched, never a watch's or a computer's.
     */
    private fun releaseLeftoverLink() {
        val last = prefs.getString("iphone", null) ?: return
        fun leftover() = manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).filter { it.address == last && it != device }
        if (leftover().isEmpty()) return
        // An awake iPhone reconnects to this run by itself within a second or two; give it that time so a fresh
        // link is not released. A suspended one cannot, and loses only these 2 s.
        post(delayMs = 2_000) { leftover().forEach { release(it) } }
    }

    private fun release(d: BluetoothDevice) {
        val cb = object : BluetoothGattCallback() {
            private val done = AtomicBoolean(false)
            fun finish(g: BluetoothGatt, what: String) {
                if (!done.compareAndSet(false, true)) return
                Log.i(TAG, "leftover link: $what")
                g.disconnect()
                g.close()
            }

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) =
                finish(g, if (newState == BluetoothProfile.STATE_CONNECTED) "held, now released" else "already gone ($status)")
        }
        Log.i(TAG, "leftover link: the iPhone is still on a link from an earlier run")
        val g = d.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return
        post(delayMs = 15_000) { cb.finish(g, "timed out") }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = post {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == Proto.SERVICE) releaseLeftoverLink()
        }

        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && d == device) {
                Log.i(TAG, "central disconnected")
                resetConnection()
                onDisconnected()
                unlinkedSince = System.currentTimeMillis()
                advertise()
                scheduleModeChecks()
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, newMtu: Int) = post {
            if (d == device) mtu = newMtu
        }

        override fun onDescriptorWriteRequest(
            d: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = post {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            if (descriptor.uuid == Proto.CCCD && descriptor.characteristic.uuid == Proto.TX) {
                val on = value != null && value.isNotEmpty() && (value[0].toInt() and 1) == 1
                if (!on) { if (d == device) subscribed = false; return@post }
                // On a radio link that stayed up, Android never saw a disconnect, so a subscribe from the holder of a
                // live session means the iPhone started over: drop the old session's state. A repeat subscribe during
                // the handshake is normal (iOS restores a bonded subscription, then the app subscribes) and is ignored.
                if (d == device && engine?.isLive == true) { Log.i(TAG, "central subscribed again; fresh session"); resetConnection() }
                if (!claim(d)) { server?.cancelConnection(d); return@post } // one iPhone at a time
                subscribed = true
                pump()
            }
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = post {
            val v = if (subscribed && d == device) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicWriteRequest(
            d: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = post {
            if (preparedWrite || characteristic.uuid != Proto.RX || !claim(d)) {
                if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return@post
            }
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val e = engine ?: return@post
            try {
                deframer.feed(value ?: ByteArray(0))?.let { e.onPacket(it) }
            } catch (x: ProtocolException) {
                fail(d, x)
            } catch (x: Exception) {
                fail(d, ProtocolException(ErrorCode.BAD_PACKET, x.javaClass.simpleName))
            }
        }

        /** Hidden in the SDK but called by the stack on every link (an override by name): the interval in 1.25 ms units. */
        @Suppress("unused")
        fun onConnectionUpdated(d: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) = post {
            if (d != device) return@post
            intervalMs = interval * 1.25
            Log.i(TAG, "link: connection interval $intervalMs ms, latency $latency, timeout ${timeout * 10} ms (status $status)")
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) = post {
            if (audioNotifiedAt != 0L) { audioSent.add(audioNotifiedAt); audioNotifiedAt = 0 }
            inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
            pump()
        }
    }

    companion object {
        const val TAG = "iBridge"
        /** PROTOCOL.md 8: a sender that falls behind keeps at most 3 frames and drops the oldest. */
        const val AUDIO_QUEUE = 3
    }
}

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
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "ibridge-gatt") }

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
    private val outbox = ArrayDeque<ByteArray>()
    private var inFlight = false
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
        s.addService(service) // onServiceAdded rings the doorbell of an iPhone left from an earlier run
        server = s
        unlinkedSince = System.currentTimeMillis()
        advertise()
        scheduleModeChecks()
    }

    fun stop() = post {
        stopAdvertising()
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
        framer.split(packet, mtu - 3).forEach { outbox.add(it) }
        pump()
    }

    private fun pump() {
        if (inFlight || !subscribed) return
        val d = device ?: return
        val chunk = outbox.poll() ?: return
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
        engine = newEngine({ p -> sendPacket(p) }, { post { stopAdvertising() } })
        Log.i(TAG, "central claimed the link")
        post(delayMs = 20_000) { if (device == d && engine?.isLive != true) { Log.w(TAG, "no session in 20 s"); drop(d) } }
        return true
    }

    private fun resetConnection() {
        engine?.close()
        device = null
        subscribed = false
        mtu = 23
        deframer = Deframer()
        framer = Framer()
        outbox.clear()
        inFlight = false
        engine = null
    }

    private fun drop(d: BluetoothDevice) {
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
     * A previous run may have died with the iPhone still connected. The iPhone opened that radio link, so
     * Android cannot drop it, and a suspended iPhone app is not woken by Service Changed. Ring its doorbell
     * (PROTOCOL.md section 1): a write to a characteristic the iPhone hosts wakes iBridge there, and it
     * reconnects to this run. Devices without a doorbell (watches, earbuds) are left alone.
     */
    private fun ringDoorbells() {
        manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
            .filter { it.bondState == BluetoothDevice.BOND_BONDED && it != device }
            .forEach { ring(it) }
    }

    private fun ring(d: BluetoothDevice) {
        val cb = object : BluetoothGattCallback() {
            private var done = false
            private fun finish(g: BluetoothGatt, what: String) {
                if (done) return
                done = true
                Log.i(TAG, "doorbell: $what")
                g.disconnect() // releases only this app's use of the link; the iPhone's own connection stays
                g.close()
            }

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else finish(g, "link gone before ringing ($status)")
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val wake = g.getService(Proto.DOORBELL)?.getCharacteristic(Proto.WAKE)
                    ?: return finish(g, "no doorbell on that device")
                val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(wake, byteArrayOf(1), type) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION") run { wake.writeType = type; wake.value = byteArrayOf(1); g.writeCharacteristic(wake) }
                }
                if (!ok) finish(g, "write not started")
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
                finish(g, if (status == BluetoothGatt.GATT_SUCCESS) "rang" else "write failed ($status)")
        }
        Log.i(TAG, "doorbell: ringing a device left on the link from an earlier run")
        val g = d.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return
        post(delayMs = 15_000) { g.close() } // never hold the link if the iPhone does not answer
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = post {
            if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == Proto.SERVICE) ringDoorbells()
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

        override fun onNotificationSent(d: BluetoothDevice, status: Int) = post {
            inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
            pump()
        }
    }

    companion object { const val TAG = "iBridge" }
}

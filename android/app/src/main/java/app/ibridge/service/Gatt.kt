package app.ibridge.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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

/**
 * GATT peripheral (PROTOCOL.md sections 1-2). All protocol work runs on one thread, [worker],
 * so the engine and the outbound queue never see concurrent calls.
 */
@SuppressLint("MissingPermission")
class Gatt(
    private val context: Context,
    private val newEngine: (send: (ByteArray) -> Unit) -> LinkEngine,
    private val onDisconnected: () -> Unit,
) {
    val worker = Executors.newSingleThreadExecutor { Thread(it, "ibridge-gatt") }

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

    fun start() = worker.execute {
        if (server != null) return@execute
        val adapter = manager.adapter ?: return@execute
        if (!adapter.isEnabled) return@execute
        val s = manager.openGattServer(context, callback) ?: return@execute
        val service = BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            Proto.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        tx = BluetoothGattCharacteristic(Proto.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(Proto.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        s.addService(service)
        server = s
        advertise()
    }

    fun stop() = worker.execute {
        stopAdvertising()
        device?.let { server?.cancelConnection(it) }
        server?.close()
        server = null
        resetConnection()
    }

    /** Low latency while the pairing sheet is open, low power otherwise. */
    fun setFastAdvertising(fast: Boolean) = worker.execute {
        if (fast == lowLatency) return@execute
        lowLatency = fast
        if (advertising) { stopAdvertising(); advertise() }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed $errorCode")
            worker.execute { advertising = false }
        }
    }

    private fun advertise() {
        if (advertising || device != null || server == null) return
        val adv = manager.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (lowLatency) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
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

    private fun resetConnection() {
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
        advertise()
    }

    private fun fail(d: BluetoothDevice, e: ProtocolException) {
        Log.w(TAG, "protocol: ${e.error} ${e.message}")
        // Best effort: queue the ERROR, then drop once it has had a moment to go out.
        sendPacket(LinkEngine.error(e.error, e.message ?: ""))
        worker.execute { Thread.sleep(150); if (device == d) drop(d) }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) = worker.execute {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (device != null && device != d) {
                    // One iPhone at a time (PROTOCOL.md 1): refuse the second central.
                    server?.cancelConnection(d)
                    return@execute
                }
                device = d
                stopAdvertising()
                engine = newEngine { p -> sendPacket(p) }
                Log.i(TAG, "central connected")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED && d == device) {
                Log.i(TAG, "central disconnected")
                resetConnection()
                onDisconnected()
                advertise()
            }
        }

        override fun onMtuChanged(d: BluetoothDevice, newMtu: Int) = worker.execute {
            if (d == device) mtu = newMtu
        }

        override fun onDescriptorWriteRequest(
            d: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = worker.execute {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            if (d == device && descriptor.uuid == Proto.CCCD) {
                subscribed = value != null && value.isNotEmpty() && (value[0].toInt() and 1) == 1
                pump()
            }
        }

        override fun onDescriptorReadRequest(d: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = worker.execute {
            val v = if (subscribed && d == device) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicWriteRequest(
            d: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = worker.execute {
            if (preparedWrite || characteristic.uuid != Proto.RX || d != device) {
                if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return@execute
            }
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            val e = engine ?: return@execute
            try {
                deframer.feed(value ?: ByteArray(0))?.let { e.onPacket(it) }
            } catch (x: ProtocolException) {
                fail(d, x)
            } catch (x: Exception) {
                fail(d, ProtocolException(ErrorCode.BAD_PACKET, x.javaClass.simpleName))
            }
        }

        override fun onNotificationSent(d: BluetoothDevice, status: Int) = worker.execute {
            inFlight = false
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "notification status $status")
            pump()
        }
    }

    companion object { const val TAG = "iBridge" }
}

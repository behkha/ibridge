package app.ibridge.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.CallLog
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import app.ibridge.MainActivity
import app.ibridge.R
import app.ibridge.core.Link
import app.ibridge.core.LinkEngine
import app.ibridge.core.Pairing
import app.ibridge.data.Phone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Foreground service of type connected-device: keeps the BLE link and relays the phone's events. */
class BridgeService : Service() {
    private lateinit var phone: Phone
    private lateinit var gatt: Gatt
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val observerThread = HandlerThread("ibridge-observers").apply { start() }
    private val observerHandler = Handler(observerThread.looper)

    /** Highest `_id` already pushed live, so the observers only send new rows. */
    private var lastSmsId = 0L
    private val pendingSms = mutableSetOf<Long>()
    private var lastCallId = 0L
    private var lastStatusAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Link.init(this)
        phone = Phone(this)
        startInForeground()
        gatt = Gatt(this, ::engineFor) { Link.setConnected(false) }
        gatt.start()
        registerObservers()
        registerReceiver(stateReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        scope.launch { calls.collect { gatt.post { onCallEvent(it) } } }
        scope.launch { Pairing.window.collect { gatt.setFastAdvertising(it != null) } }
        Link.setSignal(phone.signalLevel())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        gatt.start()
        registerObservers() // permissions may have been granted since onCreate
        if (intent?.action == ACTION_UNLINK) gatt.post {
            push(JSONObject().put("t", "unpair"))
            Link.setPeer(null)
            gatt.stop(); gatt.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        contentResolver.unregisterContentObserver(smsObserver)
        contentResolver.unregisterContentObserver(callObserver)
        unregisterReceiver(stateReceiver)
        gatt.stop()
        observerThread.quitSafely()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    }

    // ---- link ----

    private val peerStore = object : app.ibridge.core.PeerStore {
        override val peer get() = Link.peer
        override fun save(peer: app.ibridge.core.Peer) = Link.setPeer(peer)
    }

    private fun engineFor(send: (ByteArray) -> Unit, linkLive: () -> Unit) = LinkEngine(
        deviceName = deviceName(this),
        identity = app.ibridge.core.Identity,
        peers = peerStore,
        log = { Log.i(TAG, it) },
        send = send,
        onLive = { Link.setConnected(true); linkLive() },
        onMessage = ::onMessage,
    )

    private fun push(msg: JSONObject): Boolean = gatt.engine?.sendMessage(msg) == true

    /** Runs on the GATT worker thread. */
    private fun onMessage(m: JSONObject) {
        when (m.optString("t")) {
            "hello" -> sendStatus(force = true)
            "ping" -> push(JSONObject().put("t", "pong").put("ts", System.currentTimeMillis()))
            "sync" -> sync(m)
            "send" -> sendText(m)
            "call.decline" -> declineCall(m.optString("call"))
            "unpair" -> {
                Link.setPeer(null)
                gatt.stop(); gatt.start()
            }
            else -> Log.d(TAG, "ignored ${m.optString("t")}")
        }
    }

    private fun perms() = JSONObject()
        .put("sms", phone.has(Manifest.permission.READ_SMS) && phone.has(Manifest.permission.SEND_SMS))
        .put("calls", phone.has(Manifest.permission.READ_CALL_LOG) && phone.has(Manifest.permission.READ_PHONE_STATE))
        .put("contacts", phone.has(Manifest.permission.READ_CONTACTS))
        .put("notifications", phone.has(Manifest.permission.POST_NOTIFICATIONS))
        .put("battery", phone.ignoresBatteryOptimizations())

    private fun sendStatus(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatusAt < 30_000) return
        if (push(phone.status(deviceName(this), perms()))) lastStatusAt = now
        Link.setSignal(phone.signalLevel())
    }

    private fun sync(m: JSONObject) {
        val kind = m.optString("kind")
        val limit = m.optInt("limit", 200).coerceIn(1, 200)
        val cursor = if (m.isNull("cursor")) null else m.optString("cursor")
        val (afterId, since) = cursor?.split('|')?.let { (it[0].toLongOrNull() ?: 0) to (it.getOrNull(1)?.toLongOrNull() ?: 0) }
            ?: (0L to System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        val items = when (kind) {
            "sms" -> phone.smsAfter(afterId, since, limit + 1)
            "calls" -> phone.callsAfter(afterId, since, limit + 1)
            "contacts" -> phone.contactsAfter(afterId, limit + 1)
            else -> return
        }
        val page = items.take(limit)
        val last = page.lastOrNull()?.getString("id") ?: afterId.toString()
        push(
            JSONObject().put("t", "page").put("kind", kind).put("items", JSONArray(page))
                .put("cursor", "$last|$since").put("more", items.size > limit)
        )
    }

    private fun sendText(m: JSONObject) {
        val req = m.optString("req")
        val to = m.optString("to")
        val body = m.optString("body")
        if (to.isBlank() || body.isBlank() || !phone.has(Manifest.permission.SEND_SMS)) {
            push(JSONObject().put("t", "sent").put("req", req).put("ok", false).put("err", "not allowed"))
            return
        }
        val base = getSystemService(SmsManager::class.java)
        val sms = phone.subForSlot(m.optInt("sim", 0).takeIf { it > 0 })?.let { base.createForSubscriptionId(it) } ?: base
        val parts = sms.divideMessage(body)
        val action = "app.ibridge.SENT.$req"
        val sent = PendingIntent.getBroadcast(this, req.hashCode(), Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        var remaining = parts.size
        var ok = true
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ok = ok && resultCode == android.app.Activity.RESULT_OK
                if (--remaining > 0) return
                unregisterReceiver(this)
                gatt.post {
                    push(JSONObject().put("t", "sent").put("req", req).put("ok", ok).apply { if (!ok) put("err", "carrier refused") })
                }
            }
        }, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        sms.sendMultipartTextMessage(to, null, parts, ArrayList(List(parts.size) { sent }), null)
    }

    @SuppressLint("MissingPermission")
    private fun declineCall(callId: String) {
        if (!phone.has(Manifest.permission.ANSWER_PHONE_CALLS)) return
        @Suppress("DEPRECATION")
        val ended = getSystemService(TelecomManager::class.java).endCall()
        if (ended) {
            push(JSONObject().put("t", "call.end").put("call", callId).put("how", "declined"))
            if (ringing?.first == callId) ringing = null // the IDLE broadcast that follows is not a missed call
        }
    }

    // ---- live events ----

    private val smsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) = gatt.post {
            // Texts whose sending was still pending: push again once their state settles.
            phone.smsByIds(pendingSms).forEach { item ->
                if (item.getString("state") == "pending") return@forEach
                pendingSms.remove(item.getString("id").toLong())
                push(JSONObject(item.toString()).put("t", "sms"))
            }
            val rows = phone.smsAfter(lastSmsId, 0, 50)
            rows.forEach { item ->
                val id = item.getString("id").toLong()
                lastSmsId = maxOf(lastSmsId, id)
                if (item.getString("state") == "pending") pendingSms += id
                if (!push(JSONObject(item.toString()).put("t", "sms"))) return@forEach
                Link.countSent(text = true)
                item.optString("code").takeIf { it.isNotEmpty() }?.let { code ->
                    push(
                        JSONObject().put("t", "otp").put("sms", item.getString("id")).put("code", code)
                            .put("from", item.optString("name", item.getString("addr"))).put("ts", item.getLong("ts"))
                    )
                }
            }
        }
    }

    private val callObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) = gatt.post {
            phone.callsAfter(lastCallId, 0, 50).forEach { item ->
                lastCallId = maxOf(lastCallId, item.getString("id").toLong())
                if (push(JSONObject(item.toString()).put("t", "call"))) Link.countSent(call = true)
            }
        }
    }

    private var smsWatched = false
    private var callsWatched = false

    /** Idempotent. Starts from the newest row so only texts and calls after this moment go out live. */
    private fun registerObservers() = gatt.post {
        if (!smsWatched && phone.has(Manifest.permission.READ_SMS)) {
            lastSmsId = phone.latestSmsId()
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
            smsWatched = true
        }
        if (!callsWatched && phone.has(Manifest.permission.READ_CALL_LOG)) {
            lastCallId = phone.latestCallId()
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver)
            callsWatched = true
        }
    }

    /** Ringing state from [CallReceiver]: one `call.ring`, then one `call.end`. */
    private var ringing: Pair<String, String>? = null

    private fun onCallEvent(e: CallEvent) {
        when (e.state) {
            "RINGING" -> {
                val number = e.number ?: return // the first broadcast has no number; the second one does
                if (ringing?.second == number) return
                val id = "c${System.currentTimeMillis()}"
                ringing = id to number
                push(
                    JSONObject().put("t", "call.ring").put("call", id).put("addr", number)
                        .putOpt("name", phone.nameFor(number)).put("sim", e.subId?.let { phone.slotForSubscription(it) } ?: 1)
                        .put("ts", System.currentTimeMillis())
                )
            }
            "OFFHOOK" -> ringing?.let { (id, _) -> push(JSONObject().put("t", "call.end").put("call", id).put("how", "answered")); ringing = null }
            "IDLE" -> ringing?.let { (id, _) -> push(JSONObject().put("t", "call.end").put("call", id).put("how", "missed")); ringing = null }
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)) {
                    BluetoothAdapter.STATE_ON -> gatt.start()
                    BluetoothAdapter.STATE_TURNING_OFF -> { gatt.stop(); Link.setConnected(false) }
                }
                else -> gatt.post { sendStatus() }
            }
        }
    }

    data class CallEvent(val state: String, val number: String?, val subId: Int? = null)

    companion object {
        const val TAG = "iBridge"
        const val CHANNEL = "link"
        const val ACTION_UNLINK = "app.ibridge.UNLINK"

        /** The name the user sees in Settings ("Galaxy S21 Ultra"), not the model code ("SM-G998B"). */
        fun deviceName(context: Context): String =
            android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val calls = MutableSharedFlow<CallEvent>(extraBufferCapacity = 8)

        fun canStart(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context, action: String? = null) {
            if (!canStart(context)) return
            context.startForegroundService(Intent(context, BridgeService::class.java).setAction(action))
        }
    }
}

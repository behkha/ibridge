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

    /** Every text's read bit and type at the last look; the SMS observer diffs against it. */
    private var smsStates: HashMap<Long, Int>? = null
    /** Highest call-log `_id` already pushed live, so the observer only sends new rows. */
    private var lastCallId = 0L
    private var lastStatusAt = 0L
    private var lastPerms = ""

    /** What changed while the iPhone was away (PROTOCOL.md 6, "While the link is down"). Kept across restarts. */
    private val away by lazy { getSharedPreferences("away", MODE_PRIVATE) }

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
        gatt.post { sendStatus() } // sent at once when perms changed
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
        contentResolver.unregisterContentObserver(contactsObserver)
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

    /** Sends one message to the iPhone. Logs its type and id only, never its content. */
    private fun push(msg: JSONObject): Boolean {
        val ok = gatt.engine?.sendMessage(msg) == true
        Log.i(TAG, "→ ${msg.optString("t")} ${msg.optString("id", msg.optString("call", msg.optString("kind")))}${if (ok) "" else " (not sent: no live link)"}")
        return ok
    }

    /** Runs on the GATT worker thread. */
    private fun onMessage(m: JSONObject) {
        Log.i(TAG, "← ${m.optString("t")} ${m.optString("kind", m.optString("call"))}")
        when (m.optString("t")) {
            "hello" -> { sendStatus(force = true); catchUp() }
            "read" -> {
                val ids = m.optJSONArray("ids")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).toLongOrNull() } }.orEmpty()
                Log.i(TAG, "marked ${phone.markRead(ids)} of ${ids.size} texts read")
            }
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

    /** At most every 30 s, but at once when forced or when a permission changed. */
    private fun sendStatus(force: Boolean = false) {
        Link.setSignal(phone.signalLevel())
        if (gatt.engine?.isLive != true) return
        val now = System.currentTimeMillis()
        val perms = perms()
        if (!force && now - lastStatusAt < 30_000 && perms.toString() == lastPerms) return
        if (push(phone.status(deviceName(this), perms))) { lastStatusAt = now; lastPerms = perms.toString() }
    }

    /** Right after `status`: what changed while the iPhone was away. New texts and calls come by cursor sync. */
    private fun catchUp() {
        ringMsg?.let { push(it) }
        val state = awayIds("state")
        if (state.isNotEmpty() && phone.smsByIds(state).all { push(JSONObject(it.toString()).put("t", "sms")) }) clearAway("state")
        for ((key, type) in listOf("read" to "sms.read", "gone" to "sms.gone")) {
            val ids = awayIds(key)
            if (ids.isNotEmpty() && pushIds(type, ids)) clearAway(key)
        }
        if (away.getBoolean("contacts", false) && push(JSONObject().put("t", "contacts.changed"))) away.edit().putBoolean("contacts", false).apply()
    }

    private fun awayIds(key: String): List<Long> = away.getStringSet(key, emptySet())!!.mapNotNull { it.toLongOrNull() }
    /** Only for a paired iPhone: with none, there is no one to catch up. */
    private fun keepAway(key: String, ids: Collection<Long>) {
        if (ids.isEmpty() || Link.peer == null) return
        away.edit().putStringSet(key, HashSet(away.getStringSet(key, emptySet())!!).apply { ids.forEach { add(it.toString()) } }).apply()
    }
    private fun clearAway(key: String) = away.edit().remove(key).apply()

    /** `sms.read` / `sms.gone` in messages of at most 500 ids. False when any part could not be sent. */
    private fun pushIds(type: String, ids: Collection<Long>): Boolean =
        ids.isEmpty() || ids.sorted().chunked(500).map { chunk -> push(JSONObject().put("t", type).put("ids", JSONArray(chunk.map(Long::toString)))) }.all { it }

    /**
     * One page of history (PROTOCOL.md 6, "Sync and cursors"). Full history, oldest first.
     * Pages stop at [limit] items or about 40 KB of JSON, whichever comes first, so a record never
     * nears the 64 KB packet limit. Cursors are "v2:<last id>"; anything else starts over from the
     * beginning (older iPhones held 30-day cursors), and the iPhone de-duplicates by id.
     */
    private fun sync(m: JSONObject) {
        val kind = m.optString("kind")
        val limit = m.optInt("limit", 200).coerceIn(1, 200)
        val cursor = if (m.isNull("cursor")) null else m.optString("cursor")
        val afterId = cursor?.takeIf { it.startsWith("v2:") }?.removePrefix("v2:")?.toLongOrNull() ?: 0L
        val items = when (kind) {
            "sms" -> phone.smsAfter(afterId, 0, limit + 1)
            "calls" -> phone.callsAfter(afterId, 0, limit + 1)
            "contacts" -> phone.contactsAfter(afterId, limit + 1)
            else -> return
        }
        val page = ArrayList<JSONObject>()
        var bytes = 0
        for (item in items.take(limit)) {
            val size = item.toString().toByteArray().size
            if (page.isNotEmpty() && bytes + size > PAGE_BYTES) break
            page += item
            bytes += size
        }
        val last = page.lastOrNull()?.getString("id") ?: afterId.toString()
        push(
            JSONObject().put("t", "page").put("kind", kind).put("items", JSONArray(page))
                .put("cursor", "v2:$last").put("more", items.size > page.size)
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
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ok = ok && resultCode == android.app.Activity.RESULT_OK
                if (--remaining > 0) return
                unregisterReceiver(this)
                gatt.post {
                    push(JSONObject().put("t", "sent").put("req", req).put("ok", ok).apply { if (!ok) put("err", "carrier refused") })
                }
            }
        }
        registerReceiver(receiver, IntentFilter(action), RECEIVER_NOT_EXPORTED)
        try {
            sms.sendMultipartTextMessage(to, null, parts, ArrayList(List(parts.size) { sent }), null)
        } catch (e: Exception) { // e.g. a number the radio rejects: answer anyway, or the iPhone waits forever
            Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
            unregisterReceiver(receiver)
            push(JSONObject().put("t", "sent").put("req", req).put("ok", false).put("err", "not sent"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun declineCall(callId: String) {
        if (!phone.has(Manifest.permission.ANSWER_PHONE_CALLS)) return
        @Suppress("DEPRECATION")
        val ended = getSystemService(TelecomManager::class.java).endCall()
        if (ended) {
            push(JSONObject().put("t", "call.end").put("call", callId).put("how", "declined"))
            if (ringing?.first == callId) { ringing = null; ringMsg = null } // the IDLE broadcast that follows is not a missed call
        }
    }

    // ---- live events ----

    private var smsScan = 0

    /** One look 300 ms after the text store goes quiet: an incoming text fires several changes. */
    private val smsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) = gatt.post {
            val n = ++smsScan
            gatt.post(delayMs = 300) { if (n == smsScan) scanSms() }
        }
    }

    /** Diffs the text store against the last look: new texts, read ones, re-stated ones (pending → ok / failed), deleted ones. */
    private fun scanSms() {
        val now = phone.smsStates() ?: return // no permission, or the read failed: look again on the next change
        val before = smsStates ?: run { smsStates = now; return }
        smsStates = now
        val added = now.keys.filter { it !in before }
        val restated = now.filter { (id, v) -> before[id]?.let { it shr 1 != v shr 1 } == true }.keys
        val read = now.filter { (id, v) -> before[id]?.let { it and 1 == 0 && v and 1 == 1 } == true }.keys
        val gone = before.keys.filter { it !in now }
        phone.smsByIds(added).forEach(::pushNewSms)
        keepAway("state", phone.smsByIds(restated).filterNot { push(JSONObject(it.toString()).put("t", "sms")) }.map { it.getString("id").toLong() })
        if (!pushIds("sms.read", read)) keepAway("read", read)
        if (!pushIds("sms.gone", gone)) keepAway("gone", gone)
    }

    private fun pushNewSms(item: JSONObject) {
        if (!push(JSONObject(item.toString()).put("t", "sms"))) {
            if (Link.peer != null && item.optString("dir") == "in") Link.countWaiting()
            return
        }
        Link.countSent(text = true)
        item.optString("code").takeIf { it.isNotEmpty() }?.let { code ->
            push(
                JSONObject().put("t", "otp").put("sms", item.getString("id")).put("code", code)
                    .put("from", item.optString("name", item.getString("addr"))).put("ts", item.getLong("ts"))
            )
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

    private var contactsCheckDue = false

    /** Contacts sync accounts touch the store often: look at most every 10 s, and only tell the iPhone on a real change. */
    private val contactsObserver = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) = gatt.post {
            if (contactsCheckDue) return@post
            contactsCheckDue = true
            gatt.post(delayMs = 10_000) { contactsCheckDue = false; checkContacts() }
        }
    }

    private fun checkContacts() {
        if (!phone.has(Manifest.permission.READ_CONTACTS)) return
        val sig = phone.contactsSignature()
        if (sig == away.getInt("contacts_sig", 0)) return
        phone.forgetNames() // a number that had no name may have one now
        val sent = push(JSONObject().put("t", "contacts.changed"))
        away.edit().putInt("contacts_sig", sig).putBoolean("contacts", !sent).apply()
    }

    private var smsWatched = false
    private var callsWatched = false
    private var contactsWatched = false

    /** Idempotent. Starts from the newest row so only texts and calls after this moment go out live. */
    private fun registerObservers() = gatt.post {
        if (!smsWatched && phone.has(Manifest.permission.READ_SMS)) {
            smsStates = phone.smsStates()
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsObserver)
            smsWatched = true
        }
        if (!callsWatched && phone.has(Manifest.permission.READ_CALL_LOG)) {
            lastCallId = phone.latestCallId()
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callObserver)
            callsWatched = true
        }
        if (!contactsWatched && phone.has(Manifest.permission.READ_CONTACTS)) {
            contentResolver.registerContentObserver(android.provider.ContactsContract.Contacts.CONTENT_URI, true, contactsObserver)
            contactsWatched = true
            checkContacts() // contacts may have changed while the service was not running
        }
    }

    /** Ringing state from [CallReceiver]: one `call.ring`, then one `call.end`. */
    private var ringing: Pair<String, String>? = null
    /** The `call.ring` of the call still ringing, sent again after `hello` (the iPhone may have relinked mid-ring). */
    private var ringMsg: JSONObject? = null

    private fun onCallEvent(e: CallEvent) {
        when (e.state) {
            "RINGING" -> {
                val number = e.number ?: return // the first broadcast has no number; the second one does
                if (ringing?.second == number) return
                val id = "c${System.currentTimeMillis()}"
                ringing = id to number
                ringMsg = JSONObject().put("t", "call.ring").put("call", id).put("addr", number)
                    .putOpt("name", phone.nameFor(number)).put("sim", e.subId?.let { phone.slotForSubscription(it) } ?: 1)
                    .put("ts", System.currentTimeMillis())
                push(ringMsg!!)
            }
            "OFFHOOK" -> ringing?.let { (id, _) -> push(JSONObject().put("t", "call.end").put("call", id).put("how", "answered")); ringing = null; ringMsg = null }
            "IDLE" -> ringing?.let { (id, _) -> push(JSONObject().put("t", "call.end").put("call", id).put("how", "missed")); ringing = null; ringMsg = null }
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
        const val PAGE_BYTES = 40_000

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

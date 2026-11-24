package app.ibridge.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone as PhoneKind
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Reads the phone's texts, calls, contacts and status as PROTOCOL.md section 6 items. */
class Phone(private val context: Context) {
    private val resolver = context.contentResolver

    /**
     * Query with a limit passed as Bundle args. The call log provider uses strict SQL grammar
     * (Android 11+) and throws on "LIMIT" inside the sort order.
     */
    private fun query(uri: Uri, cols: Array<String>, where: String?, args: Array<String>?, order: String, limit: Int): Cursor? =
        resolver.query(uri, cols, Bundle().apply {
            if (where != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }, null)

    fun has(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // ---- names ----

    private val names = ConcurrentHashMap<String, String>()

    fun nameFor(addr: String): String? {
        if (addr.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return null
        names[addr]?.let { return it.ifEmpty { null } }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(addr))
        val name = runCatching {
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        names[addr] = name ?: ""
        return name
    }

    fun forgetNames() = names.clear()

    // ---- SIM slots ----

    @Volatile private var subsCache: Pair<Long, List<android.telephony.SubscriptionInfo>>? = null

    /** The active SIMs, cached for 10 s: a sync asks once per text, and each ask is a system call. */
    @SuppressLint("MissingPermission")
    private fun subscriptions(): List<android.telephony.SubscriptionInfo> {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val now = System.currentTimeMillis()
        subsCache?.let { (at, list) -> if (now - at < 10_000) return list }
        val list = context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        subsCache = now to list
        return list
    }

    private fun slotForSub(subId: Int): Int = subscriptions().firstOrNull { it.subscriptionId == subId }?.simSlotIndex?.plus(1) ?: 1

    fun slotForSubscription(subId: Int): Int = slotForSub(subId)

    fun subForSlot(slot: Int?): Int? = subscriptions().firstOrNull { it.simSlotIndex + 1 == slot }?.subscriptionId

    // ---- texts ----

    private val smsCols = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
    )

    private fun smsItem(c: Cursor): JSONObject {
        val addr = c.getString(2).orEmpty()
        val type = c.getInt(5)
        val body = c.getString(3).orEmpty()
        return JSONObject()
            .put("id", c.getLong(0).toString())
            .put("thread", c.getLong(1).toString())
            .put("addr", addr)
            .putOpt("name", nameFor(addr))
            .put("body", body)
            .putOpt("code", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) findCode(body) else null)
            .put("ts", c.getLong(4))
            .put("dir", if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out")
            .put("read", c.getInt(6) == 1)
            .put("sim", slotForSub(c.getInt(7)))
            .put("state", when (type) {
                Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
                Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> "pending"
                else -> "ok"
            })
    }

    /** Texts with `_id` above [afterId], oldest first. */
    fun smsAfter(afterId: Long, sinceTs: Long, limit: Int): List<JSONObject> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        return query(
            Telephony.Sms.CONTENT_URI, smsCols,
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(afterId.toString(), sinceTs.toString()),
            "${Telephony.Sms._ID} ASC", limit,
        )?.use { c -> buildList { while (c.moveToNext()) add(smsItem(c)) } }.orEmpty()
    }

    /** Re-reads specific texts, oldest first: new rows, and rows whose state changed. */
    fun smsByIds(ids: Collection<Long>): List<JSONObject> {
        if (ids.isEmpty() || !has(Manifest.permission.READ_SMS)) return emptyList()
        return ids.sorted().chunked(500).flatMap { chunk ->
            query(
                Telephony.Sms.CONTENT_URI, smsCols, "${Telephony.Sms._ID} IN (${chunk.joinToString(",")})", null,
                "${Telephony.Sms._ID} ASC", chunk.size,
            )?.use { c -> buildList { while (c.moveToNext()) add(smsItem(c)) } }.orEmpty()
        }
    }

    /**
     * Every text as `_id` → read bit | type shl 1. The SMS observer diffs two of these to find new,
     * read, re-stated (pending → sent / failed) and deleted texts. Ids and two ints only: cheap even at 10k rows.
     */
    fun smsStates(): HashMap<Long, Int>? {
        if (!has(Manifest.permission.READ_SMS)) return null
        // Null, never empty or partial, when the read fails: that would look like every text was deleted.
        return runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.READ, Telephony.Sms.TYPE),
                "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}", null, null,
            )?.use { c ->
                HashMap<Long, Int>(c.count * 2).apply { while (c.moveToNext()) put(c.getLong(0), c.getInt(1).coerceIn(0, 1) or (c.getInt(2) shl 1)) }
            }
        }.getOrNull()
    }

    /**
     * Marks texts read in Android's text store. Android ignores the write (0 rows) unless iBridge is the
     * default SMS app or holds the WRITE_SMS app-op (PROTOCOL.md "Read state").
     */
    fun markRead(ids: Collection<Long>): Int {
        if (ids.isEmpty() || !has(Manifest.permission.READ_SMS)) return 0
        val values = android.content.ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        return ids.chunked(500).sumOf { chunk ->
            runCatching {
                resolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms._ID} IN (${chunk.joinToString(",")}) AND ${Telephony.Sms.READ} = 0", null)
            }.getOrDefault(0)
        }
    }

    // ---- calls ----

    private val callCols = arrayOf(
        CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
        CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID,
    )

    private fun callItem(c: Cursor): JSONObject {
        val addr = c.getString(1).orEmpty()
        val kind = when (c.getInt(3)) {
            CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "in"
            CallLog.Calls.OUTGOING_TYPE -> "out"
            CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
            else -> "missed"
        }
        val account = c.getString(6)
        val sim = subscriptions().firstOrNull { account != null && (account == it.subscriptionId.toString()) }
            ?.simSlotIndex?.plus(1) ?: 1
        return JSONObject()
            .put("id", c.getLong(0).toString())
            .put("addr", addr)
            .putOpt("name", c.getString(2)?.ifBlank { null } ?: nameFor(addr))
            .put("kind", kind)
            .put("ts", c.getLong(4))
            .put("dur", c.getLong(5))
            .put("sim", sim)
    }

    fun callsAfter(afterId: Long, sinceTs: Long, limit: Int): List<JSONObject> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptyList()
        return query(
            CallLog.Calls.CONTENT_URI, callCols,
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(afterId.toString(), sinceTs.toString()),
            "${CallLog.Calls._ID} ASC", limit,
        )?.use { c -> buildList { while (c.moveToNext()) add(callItem(c)) } }.orEmpty()
    }

    /** Every call-log `_id`, to find deleted rows; null when the read fails (never taken for "all deleted"). */
    fun callIds(): HashSet<Long>? {
        if (!has(Manifest.permission.READ_CALL_LOG)) return null
        return runCatching {
            resolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID), null, null, null)?.use { c ->
                HashSet<Long>(c.count * 2).apply { while (c.moveToNext()) add(c.getLong(0)) }
            }
        }.getOrNull()
    }

    fun latestCallId(): Long = maxId(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID, Manifest.permission.READ_CALL_LOG)

    // ---- contacts ----

    /** Contacts with a phone number, by contact id, starting after [afterId]. */
    fun contactsAfter(afterId: Long, limit: Int): List<JSONObject> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val byId = LinkedHashMap<Long, JSONObject>()
        resolver.query(
            PhoneKind.CONTENT_URI, arrayOf(PhoneKind.CONTACT_ID, PhoneKind.DISPLAY_NAME, PhoneKind.NUMBER, PhoneKind.TYPE, PhoneKind.LABEL),
            "${PhoneKind.CONTACT_ID} > ?", arrayOf(afterId.toString()), "${PhoneKind.CONTACT_ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id !in byId && byId.size == limit) break
                val item = byId.getOrPut(id) {
                    JSONObject().put("id", id.toString()).put("name", c.getString(1).orEmpty()).put("phones", JSONArray())
                }
                val label = PhoneKind.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                item.getJSONArray("phones").put(JSONObject().put("n", c.getString(2).orEmpty()).put("label", label))
            }
        }
        return byId.values.toList()
    }

    /** A hash of every contact's name and numbers: changes when a contact is added, edited or deleted. */
    fun contactsSignature(): Int {
        if (!has(Manifest.permission.READ_CONTACTS)) return 0
        var h = 1
        runCatching {
            resolver.query(
                PhoneKind.CONTENT_URI, arrayOf(PhoneKind.CONTACT_ID, PhoneKind.DISPLAY_NAME, PhoneKind.NUMBER, PhoneKind.TYPE, PhoneKind.LABEL),
                null, null, "${PhoneKind.CONTACT_ID} ASC, ${PhoneKind.NUMBER} ASC",
            )?.use { c ->
                while (c.moveToNext()) for (i in 0 until 5) h = 31 * h + (c.getString(i)?.hashCode() ?: 0)
            }
        }
        return h
    }

    // ---- status ----

    @SuppressLint("MissingPermission")
    fun signalLevel(): Int {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return -1
        return context.getSystemService(TelephonyManager::class.java).signalStrength?.level ?: -1
    }

    @SuppressLint("MissingPermission")
    fun status(name: String, perms: JSONObject): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sims = JSONArray()
        subscriptions().forEach { s ->
            sims.put(
                JSONObject()
                    .put("sim", s.simSlotIndex + 1)
                    .put("carrier", s.carrierName?.toString().orEmpty())
                    .put("signal", tm.createForSubscriptionId(s.subscriptionId).signalStrength?.level ?: 0)
            )
        }
        return JSONObject().put("t", "status").put("battery", level).put("charging", plugged)
            .put("sims", sims).put("perms", perms).put("name", name)
    }

    fun ignoresBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun maxId(uri: Uri, col: String, perm: String): Long {
        if (!has(perm)) return 0
        return query(uri, arrayOf(col), null, null, "$col DESC", 1)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0
        } ?: 0
    }

    companion object {
        private val CODE_WORDS = Regex("(code|کد|رمز|pin|otp|password|passcode|verification)", RegexOption.IGNORE_CASE)
        private val DIGITS = Regex("(?<![0-9])[0-9]{4,8}(?![0-9])")

        /** PROTOCOL.md "One-time codes": Persian and Arabic digits are mapped to ASCII first. */
        fun findCode(body: String): String? {
            val ascii = buildString {
                body.forEach { ch ->
                    append(
                        when (ch) {
                            in '۰'..'۹' -> '0' + (ch - '۰')
                            in '٠'..'٩' -> '0' + (ch - '٠')
                            else -> ch
                        }
                    )
                }
            }
            if (!CODE_WORDS.containsMatchIn(ascii)) return null
            val runs = DIGITS.findAll(ascii).map { it.value }.toList()
            return runs.singleOrNull()
        }
    }
}

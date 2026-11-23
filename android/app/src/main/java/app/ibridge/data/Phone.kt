package app.ibridge.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.BatteryManager
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

    @SuppressLint("MissingPermission")
    private fun subscriptions() = if (has(Manifest.permission.READ_PHONE_STATE))
        context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty() else emptyList()

    private fun slotForSub(subId: Int): Int = subscriptions().firstOrNull { it.subscriptionId == subId }?.simSlotIndex?.plus(1) ?: 1

    fun subForSlot(slot: Int?): Int? = subscriptions().firstOrNull { it.simSlotIndex + 1 == slot }?.subscriptionId

    // ---- texts ----

    private val smsCols = arrayOf(
        Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
        Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
    )

    private fun smsItem(c: Cursor): JSONObject {
        val addr = c.getString(2).orEmpty()
        val type = c.getInt(5)
        return JSONObject()
            .put("id", c.getLong(0).toString())
            .put("thread", c.getLong(1).toString())
            .put("addr", addr)
            .putOpt("name", nameFor(addr))
            .put("body", c.getString(3).orEmpty())
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
        return resolver.query(
            Telephony.Sms.CONTENT_URI, smsCols,
            "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_DRAFT}",
            arrayOf(afterId.toString(), sinceTs.toString()),
            "${Telephony.Sms._ID} ASC LIMIT $limit",
        )?.use { c -> buildList { while (c.moveToNext()) add(smsItem(c)) } }.orEmpty()
    }

    fun latestSmsId(): Long = maxId(Telephony.Sms.CONTENT_URI, Telephony.Sms._ID, Manifest.permission.READ_SMS)

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
        val sim = subscriptions().firstOrNull { account != null && (account == it.subscriptionId.toString() || account == it.iccId) }
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
        return resolver.query(
            CallLog.Calls.CONTENT_URI, callCols,
            "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} >= ?",
            arrayOf(afterId.toString(), sinceTs.toString()),
            "${CallLog.Calls._ID} ASC LIMIT $limit",
        )?.use { c -> buildList { while (c.moveToNext()) add(callItem(c)) } }.orEmpty()
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
        return resolver.query(uri, arrayOf(col), null, null, "$col DESC LIMIT 1")?.use {
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

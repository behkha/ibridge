package app.ibridge.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import app.ibridge.R

/** One checklist row (E-Checklist). The same rows become warnings on the home screen (F-Status). */
enum class Item(
    val permissions: List<String>,
    @StringRes val title: Int,
    @StringRes val note: Int,
    @StringRes val denied: Int,
    @StringRes val warnTitle: Int,
    @StringRes val warnNote: Int,
    @StringRes val action: Int,
) {
    TEXTS(
        listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS),
        R.string.item_texts, R.string.item_texts_note, R.string.item_texts_denied,
        R.string.warn_texts, R.string.warn_texts_note, R.string.action_allow,
    ),
    CALLS(
        listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS),
        R.string.item_calls, R.string.item_calls_note, R.string.item_calls_denied,
        R.string.warn_calls, R.string.warn_calls_note, R.string.action_allow,
    ),
    CONTACTS(
        listOf(Manifest.permission.READ_CONTACTS),
        R.string.item_contacts, R.string.item_contacts_note, R.string.item_contacts_denied,
        R.string.warn_contacts, R.string.warn_contacts_note, R.string.action_allow,
    ),
    NEARBY(
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT),
        R.string.item_nearby, R.string.item_nearby_note, R.string.item_nearby_denied,
        R.string.warn_nearby, R.string.warn_nearby_note, R.string.action_allow,
    ),
    NOTIFICATIONS(
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
        R.string.item_notifications, R.string.item_notifications_note, R.string.item_notifications_denied,
        R.string.warn_notifications, R.string.warn_notifications_note, R.string.action_allow,
    ),
    BATTERY(
        emptyList(),
        R.string.item_battery, R.string.item_battery_note, R.string.item_battery_note,
        R.string.warn_battery, R.string.warn_battery_note, R.string.action_open,
    ),
}

enum class RowState { DONE, TODO, DENIED }

data class Row(val item: Item, val state: RowState, val askAgain: Boolean)

object Setup {
    private fun prefs(c: Context) = c.getSharedPreferences("setup", Context.MODE_PRIVATE)

    fun markAsked(c: Context, item: Item) = prefs(c).edit().putBoolean("asked_${item.name}", true).apply()

    private fun granted(c: Context, item: Item) = when (item) {
        Item.BATTERY -> c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
        else -> item.permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    fun rows(a: Activity): List<Row> = Item.entries.map { item ->
        when {
            granted(a, item) -> Row(item, RowState.DONE, false)
            item != Item.BATTERY && prefs(a).getBoolean("asked_${item.name}", false) ->
                Row(item, RowState.DENIED, askAgain = item.permissions.any { a.shouldShowRequestPermissionRationale(it) })
            else -> Row(item, RowState.TODO, false)
        }
    }
}

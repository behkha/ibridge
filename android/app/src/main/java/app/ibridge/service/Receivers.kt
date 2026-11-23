package app.ibridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Restarts the link after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BridgeService.start(context)
        }
    }
}

/** PHONE_STATE carries the caller's number only to apps holding READ_CALL_LOG. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val s = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> "RINGING"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "OFFHOOK"
            else -> "IDLE"
        }
        val sub = intent.getIntExtra(android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
        BridgeService.calls.tryEmit(BridgeService.CallEvent(s, number, sub))
    }
}

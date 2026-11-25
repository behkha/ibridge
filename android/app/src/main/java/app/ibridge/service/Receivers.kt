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

/**
 * For whoever holds adb on this phone: `am broadcast -a app.ibridge.HELPER_COMMAND -n app.ibridge.android/app.ibridge.service.HelperCommandReceiver`
 * prints the shell command that starts the call helper (it carries the helper's secret), so the helper can be started
 * over USB too; `-a app.ibridge.OPUS_CHECK` runs one Opus round trip and prints what came out. The helper itself sends
 * `-a app.ibridge.HELPER_PROOF --es n <nonce>` to prove it runs as the shell (Wire). `-a app.ibridge.AUDIO_TUNE` sets call-audio
 * buffers live for latency tests (PROTOCOL.md 6): `--ei trackFrames|upPrime|upSpare N` (helper), `--ez link true|false`
 * (connection interval), `--ei jitterStart|jitterSpare|ioMs N` (the iPhone, as `audio.tune`).
 * Only a sender holding DUMP (the shell, the system) can reach this receiver.
 */
class HelperCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == app.ibridge.call.helper.Wire.PROOF_ACTION) { app.ibridge.call.CallAudio.proved(intent.getStringExtra("n")); return }
        if (intent.action == "app.ibridge.OPUS_CHECK") { resultData = app.ibridge.call.opusRoundTrip(); return }
        if (intent.action == "app.ibridge.AUDIO_TUNE") { resultData = tune(intent); return }
        app.ibridge.call.CallAudio.init(context)
        resultData = app.ibridge.call.CallAudio.launchCommand()
    }

    private fun tune(intent: Intent): String {
        val helper = org.json.JSONObject().put("c", "tune")
        val phone = org.json.JSONObject().put("t", "audio.tune")
        for (k in listOf("trackFrames", "upPrime", "upSpare")) if (intent.hasExtra(k)) helper.put(k, intent.getIntExtra(k, 0))
        for (k in listOf("jitterStart", "jitterSpare", "ioMs")) if (intent.hasExtra(k)) phone.put(k, intent.getIntExtra(k, 0))
        val relay = CallRelay.current
        val sent = mutableListOf<String>()
        if (helper.length() > 1 && app.ibridge.call.CallAudio.command(helper)) sent += "helper"
        if (phone.length() > 1 && relay != null) { relay.tunePhone(phone); sent += "iPhone" }
        if (intent.hasExtra("link") && relay != null) { relay.tuneLink(intent.getBooleanExtra("link", false)); sent += "link" }
        return "tuned: ${sent.ifEmpty { listOf("nothing") }.joinToString()}"
    }
}

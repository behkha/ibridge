package app.ibridge.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import app.ibridge.R

/**
 * The Wireless debugging pairing code is typed into this notification's reply field, over Settings: leaving Settings
 * would close the pairing dialog and its code (Shizuku's way).
 */
object PairingNotice {
    private const val CHANNEL = "pairing"
    private const val ID = 2
    const val KEY_CODE = "code"
    const val ACTION = "app.ibridge.PAIR_CODE"

    fun show(context: Context, step: PairStep) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (step == PairStep.IDLE || step == PairStep.DONE) { nm.cancel(ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val wrong = step == PairStep.WRONG
        val title = context.getString(if (wrong) R.string.pair_code_wrong else R.string.pair_code_title)
        val text = context.getString(if (wrong) R.string.pair_code_wrong_text else R.string.pair_code_text)
        val reply = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION).setClass(context, PairCodeReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // RemoteInput fills the intent in
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pair_code_title)).build()
        val action = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_link), context.getString(R.string.pair_code_title), reply)
            .addRemoteInput(input).build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_link)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(action)
            .setOnlyAlertOnce(!wrong)
            .setOngoing(true)
            .build()
        nm.notify(ID, n)
    }
}

/** Receives the code typed into [PairingNotice]. Not exported: only the notification's own PendingIntent reaches it. */
class PairCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotice.KEY_CODE)?.toString()
            ?.filter { it.isDigit() } ?: return
        CallAudio.init(context)
        if (code.length != 6) { PairingNotice.show(context, PairStep.WRONG); return }
        CallAudio.pair(code)
    }
}

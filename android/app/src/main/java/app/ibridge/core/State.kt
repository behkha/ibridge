package app.ibridge.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/** Everything the screens show. Written by the service, read by the UI. Same process. */
data class LinkUi(
    val peer: Peer? = null,
    val connected: Boolean = false,
    val lastSentAt: Long = 0,
    val textsToday: Int = 0,
    val callsToday: Int = 0,
    val signal: Int = -1,
)

object Link {
    private val _ui = MutableStateFlow(LinkUi())
    val ui: StateFlow<LinkUi> = _ui.asStateFlow()

    private lateinit var appContext: Context
    private val prefs get() = appContext.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun init(context: Context) {
        appContext = context.applicationContext
        val pub = prefs.getString("peer_pub", null)
        val peer = pub?.let { Peer(Base64.decode(it, Base64.NO_WRAP), prefs.getString("peer_name", "iPhone")!!) }
        val today = LocalDate.now().toString() == prefs.getString("day", "")
        _ui.value = LinkUi(
            peer = peer,
            lastSentAt = prefs.getLong("last_sent", 0),
            textsToday = if (today) prefs.getInt("texts", 0) else 0,
            callsToday = if (today) prefs.getInt("calls", 0) else 0,
        )
    }

    val peer: Peer? get() = _ui.value.peer

    fun setPeer(peer: Peer?) {
        prefs.edit().apply {
            if (peer == null) remove("peer_pub").remove("peer_name")
            else putString("peer_pub", Base64.encodeToString(peer.publicKey, Base64.NO_WRAP)).putString("peer_name", peer.name)
        }.apply()
        _ui.update { it.copy(peer = peer, connected = if (peer == null) false else it.connected) }
    }

    fun setConnected(connected: Boolean) = _ui.update { it.copy(connected = connected) }

    fun setSignal(level: Int) = _ui.update { it.copy(signal = level) }

    /** Called after each message reaches the iPhone. */
    fun countSent(text: Boolean = false, call: Boolean = false) {
        val day = LocalDate.now().toString()
        val sameDay = prefs.getString("day", "") == day
        val now = System.currentTimeMillis()
        _ui.update {
            it.copy(
                lastSentAt = now,
                textsToday = (if (sameDay) it.textsToday else 0) + if (text) 1 else 0,
                callsToday = (if (sameDay) it.callsToday else 0) + if (call) 1 else 0,
            )
        }
        val s = _ui.value
        prefs.edit().putString("day", day).putLong("last_sent", now)
            .putInt("texts", s.textsToday).putInt("calls", s.callsToday).apply()
    }
}

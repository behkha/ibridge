package app.ibridge.ui

import android.text.format.DateUtils
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import app.ibridge.R
import app.ibridge.core.Identity
import app.ibridge.core.LinkUi
import app.ibridge.core.P256
import app.ibridge.core.PairingWindow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.net.URLEncoder

// ---------------------------------------------------------------- E-Checklist

@Composable
fun ChecklistScreen(rows: List<Row>, onAction: (Row) -> Unit, onPair: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.setup_title), fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = C.ink)
            Text(stringResource(R.string.setup_lede), fontSize = 15.sp, lineHeight = 22.sp, color = C.body)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                val note = stringResource(if (r.state == RowState.DENIED) r.item.denied else r.item.note)
                val action = when {
                    r.state == RowState.DENIED && !r.askAgain -> stringResource(R.string.action_settings)
                    else -> stringResource(r.item.action)
                }
                SetupRow(stringResource(r.item.title), note, r.state, action) { onAction(r) }
            }
        }
        Button(
            onClick = onPair,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = Color.White),
        ) { Text(stringResource(R.string.show_pairing_code), fontSize = 17.sp, fontWeight = FontWeight.Medium) }
    }
}

// ---------------------------------------------------------------- E-QR

fun pairingUri(w: PairingWindow, name: String): String {
    val b64 = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    val k = Base64.encodeToString(P256.compressed(Identity.publicKey), b64)
    val t = Base64.encodeToString(w.token, b64)
    return "ibridge://pair?v=1&k=$k&t=$t&n=${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
        )
    }
    // Always black on white, whatever the theme (approved rule).
    Canvas(modifier.background(Color.White)) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(window: PairingWindow?, deviceName: String, onRenew: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    LaunchedEffect(window?.expiresAt, now) { if (window != null && window.expiresAt <= now) onRenew() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(36.dp, 4.dp).background(C.handle, RoundedCornerShape(2.dp))) },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pair_title), fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = C.ink)
                Text(stringResource(R.string.pair_lede), fontSize = 15.sp, lineHeight = 22.sp, color = C.body)
            }
            if (window == null) {
                Text(stringResource(R.string.pair_locked), fontSize = 15.sp, color = C.warnInk)
            } else {
                val desc = stringResource(R.string.pair_qr_description)
                Box(
                    Modifier.border(1.dp, C.border, RoundedCornerShape(18.dp)).background(Color.White, RoundedCornerShape(18.dp)).padding(14.dp)
                        .semantics { contentDescription = desc }
                ) { QrCode(pairingUri(window, deviceName), Modifier.size(228.dp)) }
                val left = ((window.expiresAt - now).coerceAtLeast(0) / 1000).toInt()
                Text(stringResource(R.string.pair_waiting, left / 60, left % 60), fontSize = 15.sp, color = C.body)
                Row(
                    Modifier.fillMaxWidth().background(C.grey, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.pair_cant_scan), Modifier.weight(1f), fontSize = 14.sp, color = C.body)
                    // The code reads left to right even in right-to-left languages.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            window.code.substring(0, 3) + " " + window.code.substring(3),
                            style = TextStyle(textDirection = TextDirection.Ltr),
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = C.ink,
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.pair_back), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.accent)
            }
        }
    }
}

// ---------------------------------------------------------------- F-Status

@Composable
fun HomeScreen(
    ui: LinkUi,
    warnings: List<Row>,
    onWarning: (Row) -> Unit,
    onPermissions: () -> Unit,
    onPairAnother: () -> Unit,
    onUnlink: () -> Unit,
) {
    val peerName = ui.peer?.name ?: ""
    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), Modifier.padding(horizontal = 8.dp), fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium, color = C.ink)

        val linked = ui.connected
        val cardBg = if (linked) C.linkedBg else C.grey
        val cardInk = if (linked) C.linkedInk else C.ink
        Column(
            Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinkGlyph(if (linked) C.linked else C.greyFg, linked)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (linked) R.string.home_linked else R.string.home_waiting, peerName),
                    fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, color = cardInk,
                )
                val detail = if (ui.lastSentAt > 0)
                    stringResource(R.string.home_last_sent, DateUtils.getRelativeTimeSpanString(ui.lastSentAt).toString().replaceFirstChar { it.lowercase() })
                else stringResource(R.string.home_encrypted)
                Text(detail, fontSize = 15.sp, lineHeight = 22.sp, color = cardInk)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val signal = when (ui.signal) {
                    4, 3 -> R.string.signal_strong
                    2 -> R.string.signal_good
                    1 -> R.string.signal_weak
                    0 -> R.string.signal_none
                    else -> R.string.signal_unknown
                }
                Stat(ui.textsToday.toString(), stringResource(R.string.stat_texts), cardInk, Modifier.weight(1f))
                Stat(ui.callsToday.toString(), stringResource(R.string.stat_calls), cardInk, Modifier.weight(1f))
                Stat(stringResource(signal), stringResource(R.string.stat_signal), cardInk, Modifier.weight(1f))
            }
        }

        warnings.forEach { r ->
            val action = if (r.state == RowState.DENIED && !r.askAgain) stringResource(R.string.action_settings) else stringResource(r.item.action)
            SetupRow(stringResource(r.item.warnTitle), stringResource(r.item.warnNote), RowState.DENIED, action) { onWarning(r) }
        }

        Column(Modifier.fillMaxWidth().background(C.card, RoundedCornerShape(18.dp)).border(1.dp, C.border, RoundedCornerShape(18.dp))) {
            MenuRow(stringResource(R.string.menu_permissions), C.ink, onPermissions)
            HorizontalDivider(color = C.divider)
            MenuRow(stringResource(R.string.menu_pair_another), C.ink, onPairAnother)
            HorizontalDivider(color = C.divider)
            MenuRow(stringResource(R.string.menu_unlink, peerName), C.danger, onUnlink)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stat(value: String, label: String, ink: Color, modifier: Modifier) {
    Column(modifier.background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = ink, maxLines = 1)
        Text(label, fontSize = 13.sp, color = ink, maxLines = 1)
    }
}

@Composable
private fun MenuRow(text: String, color: Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, fontSize = 16.sp, color = color)
    }
}

package app.ibridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens from the approved Android mockups (E-Checklist, E-QR, F-Status). */
object C {
    val ink = Color(0xFF0E1A2B)
    val accent = Color(0xFF1D5BD8)
    val bg = Color(0xFFFBFBFD)
    val card = Color.White
    val border = Color(0xFFE3E6EB)
    val divider = Color(0xFFEDEFF2)
    val body = Color(0xFF3F4A5A)
    val muted = Color(0xFF5B6472)
    val grey = Color(0xFFEEF0F3)
    val greyFg = Color(0xFF6B7380)
    val handle = Color(0xFFC9CED6)
    val linked = Color(0xFF137A4B)
    val linkedBg = Color(0xFFE6F4EC)
    val linkedInk = Color(0xFF0F4D31)
    val warn = Color(0xFFA65300)
    val warnBg = Color(0xFFFFF1E0)
    val warnInk = Color(0xFF7A3E00)
    val warnBorder = Color(0xFFF2C9A0)
    val danger = Color(0xFFC4001A)
}

object Glyph {
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val DOT = "M12 12h.01"
    const val WARN = "M12 7v6M12 16.5h.01"
}

/** Draws a 24-unit stroked path at [size]. */
@Composable
fun StrokeIcon(path: String, color: Color, size: Int = 20, width: Float = 2.2f) {
    val p = PathParser().parsePathString(path).toPath()
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.width / 24f
        scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(p, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun PillButton(text: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

/** The checklist row; also the warning row on the home screen. */
@Composable
fun SetupRow(title: String, note: String, state: RowState, action: String?, onAction: () -> Unit) {
    val warn = state == RowState.DENIED
    Row(
        Modifier
            .fillMaxWidth()
            .background(C.card, RoundedCornerShape(18.dp))
            .border(1.dp, if (warn) C.warnBorder else C.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val (badgeBg, badgeFg, glyph) = when (state) {
            RowState.DONE -> Triple(C.linkedBg, C.linked, Glyph.CHECK)
            RowState.TODO -> Triple(C.grey, C.greyFg, Glyph.DOT)
            RowState.DENIED -> Triple(C.warnBg, C.warn, Glyph.WARN)
        }
        Box(Modifier.size(40.dp).background(badgeBg, CircleShape), contentAlignment = Alignment.Center) {
            StrokeIcon(glyph, badgeFg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = C.ink)
            Text(note, fontSize = 14.sp, lineHeight = 19.sp, color = if (warn) C.warnInk else C.muted)
        }
        if (action != null && state != RowState.DONE) {
            if (warn) PillButton(action, C.warnBg, C.warnInk, onAction) else PillButton(action, C.accent, Color.White, onAction)
        }
    }
}

/** Two dots joined by a line: solid when linked, broken when waiting. */
@Composable
fun LinkGlyph(color: Color, linked: Boolean) {
    Canvas(Modifier.size(width = 64.dp, height = 20.dp)) {
        val u = size.width / 64f
        val y = size.height / 2
        if (linked) {
            drawCircle(color, 8 * u, androidx.compose.ui.geometry.Offset(10 * u, y))
            drawCircle(color, 8 * u, androidx.compose.ui.geometry.Offset(54 * u, y))
            drawLine(color, androidx.compose.ui.geometry.Offset(18 * u, y), androidx.compose.ui.geometry.Offset(46 * u, y), 3 * u)
        } else {
            val st = Stroke(width = 2.5f * u)
            drawCircle(color, 7 * u, androidx.compose.ui.geometry.Offset(10 * u, y), style = st)
            drawCircle(color, 7 * u, androidx.compose.ui.geometry.Offset(54 * u, y), style = st)
            drawLine(color, androidx.compose.ui.geometry.Offset(19 * u, y), androidx.compose.ui.geometry.Offset(28 * u, y), 2.5f * u)
            drawLine(color, androidx.compose.ui.geometry.Offset(36 * u, y), androidx.compose.ui.geometry.Offset(45 * u, y), 2.5f * u)
        }
    }
}

package app.berth.android.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.security.RemoteClipboardGate
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.prompts.HostLine
import app.berth.android.ui.prompts.PromptSheet
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType

/**
 * The one-time notice for a host whose clipboard write was held back (spec C20, Remote clipboard).
 * Shows what the remote tried to put there, so the decision is an informed one, and offers to
 * allow that host; the app-wide switch stays where it is, under Settings › Security.
 */
@Composable
fun RemoteClipboardNoticeSheet(gate: RemoteClipboardGate) {
    val c = Berth.colors
    val notice by gate.notice.collectAsState()
    val n = notice ?: return
    PromptSheet(onDismiss = { gate.dismiss(n) }) {
        SheetTitle("Clipboard write blocked", "${n.host.name} tried to put text on this phone's clipboard.")
        HostLine(n.host)
        Text(
            n.text.lineSequence().firstOrNull()?.take(120).orEmpty().ifBlank { "(empty)" } + if (n.text.length > 120 || n.text.contains('\n')) " \u2026" else "",
            style = BerthType.mono.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = c.text1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(BerthRadius.row)).background(c.surface2).padding(12.dp),
        )
        Text(
            "Programs on a server can write your clipboard through the terminal (OSC 52). Berth keeps that off until you allow it, for this host here or for every host under Settings \u203A Security. You will not be asked about this host again.",
            style = BerthType.body,
            color = c.text2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BerthButton("Allow for ${n.host.name}", onClick = { gate.allow(n) }, kind = ButtonKind.PRIMARY)
            BerthButton("Keep blocked", onClick = { gate.dismiss(n) }, kind = ButtonKind.TEXT)
        }
    }
}

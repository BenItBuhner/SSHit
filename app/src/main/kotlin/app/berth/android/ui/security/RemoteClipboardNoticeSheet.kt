package app.berth.android.ui.security

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.security.ClipboardPreview
import app.berth.android.security.RemoteClipboardGate
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.files.formatSize
import app.berth.android.ui.prompts.HostLine
import app.berth.android.ui.prompts.PromptSheet
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType

/**
 * The one-time notice for a host whose clipboard write was held back (spec C20, Remote clipboard).
 * Shows what the remote tried to put there, escaped so it can be judged by eye, and offers the
 * two answers. The remote caused this sheet and the text on show may be the attack, so no button
 * is the primary: Keep blocked stands first in the resting kind and Allow is plain text, the same
 * weighting as the host-key-changed sheet. Swiping the sheet away answers nothing; only the two
 * buttons settle the host.
 */
@Composable
fun RemoteClipboardNoticeSheet(gate: RemoteClipboardGate) {
    val c = Berth.colors
    val notice by gate.notice.collectAsState()
    val n = notice ?: return
    val preview = remember(n) { ClipboardPreview.of(n.text) }
    PromptSheet(onDismiss = { gate.dismiss(n) }) {
        SheetTitle("Clipboard write blocked", "${n.host.name} tried to put text on this phone\u2019s clipboard.")
        HostLine(n.host)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (preview.truncated) preview.shown + "\u2026" else preview.shown,
                style = BerthType.mono.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = c.text1,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(BerthRadius.row)).background(c.surface2).padding(12.dp),
            )
            preview.countLine(::formatSize)?.let { Text(it, style = BerthType.caption, color = c.text3, modifier = Modifier.padding(start = 12.dp)) }
        }
        Text(
            "Programs on a server can write your clipboard through the terminal (OSC 52). Berth keeps that off until you allow it, for this host here or for every host under Settings \u203A Security. Either answer is kept; you will not be asked about this host again.",
            style = BerthType.body,
            color = c.text2,
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BerthButton("Keep blocked", onClick = { gate.keepBlocked(n) }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
            BerthButton("Allow for ${n.host.name}", onClick = { gate.allow(n) }, kind = ButtonKind.TEXT, modifier = Modifier.fillMaxWidth())
        }
    }
}

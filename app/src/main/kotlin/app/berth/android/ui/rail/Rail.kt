package app.berth.android.ui.rail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.Pill
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Workspace

/*
 * The rail as a session switcher is superseded by the tab strip (spec C3); the drawer in Drawer.kt
 * is its successor. What remains here are the session rows the Session sheet still lists.
 */

/**
 * 56 dp session row: swatch with state dot, title, subtitle, the local ports of tunnels that are
 * up as Mono pills (tap opens the host's tunnels), and an age when not Live.
 */
@Composable
fun SessionRow(
    session: TerminalSession,
    selected: Boolean,
    now: Long,
    onTap: () -> Unit,
    onReconnect: () -> Unit,
    onDetach: () -> Unit,
    onClose: () -> Unit,
    surface: androidx.compose.ui.graphics.Color = Berth.colors.surface2,
    tunnelPorts: List<Int> = emptyList(),
    onTunnelTap: (() -> Unit)? = null,
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = record.displayTitle,
            subtitle = subtitleFor(record),
            selected = selected,
            surface = surface,
            onClick = onTap,
            onLongClick = { menu = true },
            leading = {
                Swatch(
                    record.hostSnapshot.color,
                    record.hostSnapshot.monogram,
                    36.dp,
                    state = record.state,
                    attention = record.needsAttention,
                    showLiveDot = !selected,
                )
            },
            trailing = {
                if (tunnelPorts.isNotEmpty()) {
                    val pillModifier = if (onTunnelTap != null) Modifier.clip(CircleShape).clickable(onClick = onTunnelTap) else Modifier
                    // One tonal step above the row it sits on: the active row is already surface.3.
                    val pill = if (selected) c.surface4 else c.surface3
                    for (port in tunnelPorts.take(2)) Pill(port.toString(), mono = true, modifier = pillModifier, color = pill)
                    if (tunnelPorts.size > 2) Pill("+${tunnelPorts.size - 2}", mono = true, modifier = pillModifier, color = pill)
                }
                val trailing = when (record.state) {
                    SessionState.LIVE -> null
                    SessionState.DETACHED -> "Detached"
                    SessionState.RECONNECTING -> "Reconnecting"
                    SessionState.CONNECTING, SessionState.IDLE -> "Connecting"
                    SessionState.FAILED -> "Failed"
                    SessionState.CLOSED -> "Closed"
                }
                if (trailing != null) {
                    Text(trailing, style = BerthType.caption, color = if (record.state == SessionState.FAILED) c.danger else c.text3)
                }
                if (record.state == SessionState.DETACHED && record.lastLiveAt != null) {
                    Text(ageText(record.lastLiveAt, now), style = BerthType.caption, color = c.text3)
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface2, shape = RoundedCornerShape(BerthRadius.row)) {
            if (!record.state.isActive) DropdownMenuItem(text = { Text("Reconnect", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onReconnect() })
            if (record.state.isActive) DropdownMenuItem(text = { Text("Detach", style = BerthType.body, color = c.text1) }, onClick = { menu = false; onDetach() })
            DropdownMenuItem(text = { Text("Close", style = BerthType.body, color = c.danger) }, onClick = { menu = false; onClose() })
        }
    }
}

/** Subtitle order from the spec: running command, then working directory, then user@host. */
fun subtitleFor(record: SessionRecord): String {
    val h = record.hostSnapshot
    return when {
        !record.lastCommand.isNullOrBlank() -> record.lastCommand!!
        !record.cwd.isNullOrBlank() -> "${h.userAtHost} \u00B7 ${record.cwd}"
        else -> h.userAtHost
    }
}

@Composable
fun WorkspaceCaption(workspaces: List<Workspace>, id: String?) {
    val ws = workspaces.firstOrNull { it.id == id }
    SectionLabel(ws?.name ?: Workspace.DEFAULT_NAME)
}

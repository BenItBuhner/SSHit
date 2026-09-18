package app.berth.android.ui.stage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.rail.SessionRow
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionState

/**
 * The session sheet from the Grip or the ribbon title: this session's facts and actions, then the
 * other sessions in the workspace for a quick switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    vm: AppViewModel,
    session: TerminalSession,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onEditHost: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    val others by vm.workspaceSessions.collectAsState()
    val now = ageTicker()
    val host = record.hostSnapshot

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch(host.color, host.monogram, 40.dp, state = record.state)
                SheetTitle(record.title.ifBlank { host.name }, host.userAtHost + if (host.port != 22) ":${host.port}" else "")
            }
            Panel {
                Fact("State", stateLabel(record.state, record.lastLiveAt, now))
                record.cwd?.let { Fact("Directory", it) }
                record.lastCommand?.let { Fact("Running", it) }
                Fact("Size", "${session.emulator.cols} \u00D7 ${session.emulator.rows}")
                Fact(
                    "Layer",
                    when (record.layer) {
                        PersistenceLayer.LOCAL_FRAME -> "Saved frame"
                        PersistenceLayer.IN_APP -> "Live socket in Berth"
                        PersistenceLayer.TMUX -> "tmux on the server"
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!record.state.isActive) BerthButton("Reconnect", kind = ButtonKind.PRIMARY, onClick = { vm.reconnect(session.id); onDismiss() })
                if (record.state.isActive) BerthButton("Detach", onClick = { vm.detach(session.id); onDismiss() })
                if (record.hostId != null) BerthButton("Host", onClick = { onEditHost(record.hostId!!); onDismiss() })
                BerthButton("Close", kind = ButtonKind.DESTRUCTIVE, onClick = { vm.close(session.id); onDismiss() })
            }
            val rest = others.filter { it.id != session.id }
            if (rest.isNotEmpty()) {
                Text("Also in this workspace".uppercase(), style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in rest) {
                        SessionRow(
                            session = s,
                            selected = false,
                            now = now,
                            onTap = { onSwitch(s.id); onDismiss() },
                            onReconnect = { vm.reconnect(s.id) },
                            onDetach = { vm.detach(s.id) },
                            onClose = { vm.close(s.id) },
                        )
                    }
                }
            }
            BerthButton("New session", kind = ButtonKind.TEXT, onClick = { onNewSession(); onDismiss() })
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    val c = Berth.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = BerthType.body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = BerthType.body, color = c.text1)
    }
}

fun stateLabel(state: SessionState, lastLiveAt: Long?, now: Long): String = when (state) {
    SessionState.LIVE -> "Live"
    SessionState.IDLE, SessionState.CONNECTING -> "Connecting"
    SessionState.RECONNECTING -> "Reconnecting"
    SessionState.DETACHED -> "Detached ${ageText(lastLiveAt, now)}"
    SessionState.FAILED -> "Couldn't connect"
    SessionState.CLOSED -> "Closed"
}

package app.berth.android.ui.stage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.session.FilesTab
import app.berth.android.session.ManagedTab
import app.berth.android.session.TerminalSession
import app.berth.android.session.TunnelStatus
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.snippets.SnippetPickerSheet
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.Panel
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.rail.SessionRow
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionState

/**
 * The session sheet from the Grip or the ribbon title: the tab's facts and actions, then the other
 * tabs in the workspace for a quick switch. A terminal tab offers Detach or Reconnect, Files (the
 * host's Files tab, opened or brought on stage) and Snippets; a Files tab offers Connect or
 * Reconnect for the terminal it rides and Terminal to go there.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionSheet(
    vm: AppViewModel,
    tab: ManagedTab,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onEditHost: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenTunnels: (String) -> Unit = {},
    onOpenFiles: (tabId: String) -> Unit = {},
    onOpenTerminal: (filesTabId: String) -> Unit = {},
) {
    val c = Berth.colors
    val record by tab.record.collectAsState()
    val session = tab as? TerminalSession
    val filesTab = tab as? FilesTab
    val ride = filesTab?.ride?.collectAsState()?.value
    val rideRecord = ride?.record?.collectAsState()?.value
    val others by vm.workspaceTabs.collectAsState()
    val tunnels by vm.tunnels.collectAsState()
    val tunnelStatuses by vm.tunnelStatuses.collectAsState()
    val savedHosts by vm.hosts.collectAsState()
    val now = ageTicker()
    val host = record.hostSnapshot
    // A Quick connect tab's host is nobody's in the library (spec C11): the sheet offers to save it rather than to edit what is not there.
    val hostSaved = savedHosts.any { it.id == record.hostId }
    val hostTunnels = record.hostId?.let { id -> tunnels.filter { it.hostId == id } } ?: emptyList()
    val up = hostTunnels.count { tunnelStatuses[it.id] is TunnelStatus.Up }
    val failed = hostTunnels.count { tunnelStatuses[it.id] is TunnelStatus.Failed }
    var snippets by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }

    // The sheet opens whole, as the prompts do, rather than at half the window with its second row of
    // pills below the fold at the interface cap; and the column scrolls, for a window shorter than the
    // facts and the pills at that size (a half-open sheet without a scroll would lay them out unreached).
    BerthSheet(onDismiss = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch(host.color, host.monogram, 40.dp, state = record.state)
                SheetTitle(record.displayTitle.ifBlank { host.name }, host.userAtHost + if (host.port != 22) ":${host.port}" else "")
            }
            Panel {
                if (filesTab != null && ride == null) Fact("State", "Not connected")
                else Fact("State", stateLabel(record.state, record.lastLiveAt, now))
                if (session != null) {
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
                } else {
                    record.cwd?.let { Fact("Folder", it) }
                    // The browser has no login of its own; it rides one of the host's terminal tabs.
                    Fact("Terminal", rideRecord?.displayTitle ?: "None on this host", valueColor = if (rideRecord == null) c.text2 else c.text1)
                }
                if (hostTunnels.isNotEmpty()) {
                    val enabled = hostTunnels.count { it.enabled }
                    Fact(
                        "Tunnels",
                        buildList {
                            if (up > 0) add("$up up")
                            if (failed > 0) add("$failed failed")
                            if (up == 0 && failed == 0) add(if (enabled == 0) "${hostTunnels.size} off" else if (record.state.isActive) "starting" else "$enabled waiting")
                        }.joinToString(" \u00B7 "),
                        valueColor = if (failed > 0) c.danger else c.text1,
                    )
                }
            }
            // Two deliberate rows (C6): the tab's own actions, then the host's and the exit. The pills are
            // the spec's, each at its label's width and wrapping onto another line where the width runs
            // out (four of them at the interface cap); a pill given a share of the row instead cut its label.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (session != null) {
                        if (record.state.isActive) BerthButton("Detach", onClick = { vm.detach(session.id); onDismiss() })
                        else BerthButton("Reconnect", kind = ButtonKind.PRIMARY, onClick = { vm.reconnect(session.id); onDismiss() })
                        BerthButton("Files", onClick = { onOpenFiles(session.id); onDismiss() })
                        if (record.state == SessionState.LIVE) {
                            BerthButton("Snippets", onClick = { snippets = true })
                        }
                        BerthButton("History", onClick = { history = true })
                    } else if (filesTab != null) {
                        when {
                            ride == null -> BerthButton("Connect", kind = ButtonKind.PRIMARY, onClick = { vm.connectFor(filesTab); onDismiss() })
                            !record.state.isActive -> BerthButton("Reconnect", kind = ButtonKind.PRIMARY, onClick = { vm.reconnect(tab.id); onDismiss() })
                        }
                        BerthButton("Terminal", onClick = { onOpenTerminal(tab.id); onDismiss() })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    record.hostId?.let { hostId ->
                        if (hostSaved) {
                            BerthButton(if (up > 0) "Tunnels $up" else "Tunnels", onClick = { onOpenTunnels(hostId); onDismiss() })
                            BerthButton("Host", onClick = { onEditHost(hostId); onDismiss() })
                        } else {
                            BerthButton("Save as host", onClick = { vm.saveSnapshotAsHost(host) { saved -> onEditHost(saved.id) }; onDismiss() })
                        }
                    }
                    BerthButton("Close", kind = ButtonKind.DESTRUCTIVE, onClick = { vm.close(tab.id); onDismiss() })
                }
            }
            val rest = others.filter { it.id != tab.id }
            if (rest.isNotEmpty()) {
                Text("Also in this workspace".uppercase(), style = BerthType.caption, color = c.text2, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in rest) {
                        SessionRow(
                            tab = t,
                            selected = false,
                            now = now,
                            onTap = { onSwitch(t.id); onDismiss() },
                            onReconnect = { vm.reconnect(t.id) },
                            onDetach = { vm.detach(t.id) },
                            onClose = { vm.close(t.id) },
                        )
                    }
                }
            }
            BerthButton("New session", kind = ButtonKind.TEXT, onClick = { onNewSession(); onDismiss() })
        }
    }
    if (snippets && session != null) {
        SnippetPickerSheet(vm, session, onDismiss = { snippets = false })
    }
    if (history && session != null) {
        CommandHistorySheet(vm, session, onDismiss = { history = false })
    }
}

@Composable
private fun Fact(label: String, value: String, valueColor: Color = Berth.colors.text1) {
    val c = Berth.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = BerthType.body, color = c.text2, modifier = Modifier.weight(1f))
        Text(value, style = BerthType.body, color = valueColor)
    }
}

fun stateLabel(state: SessionState, lastLiveAt: Long?, now: Long): String = when (state) {
    SessionState.LIVE -> "Live"
    SessionState.IDLE, SessionState.CONNECTING -> "Connecting"
    SessionState.RECONNECTING -> "Reconnecting"
    SessionState.DETACHED -> "Detached ${ageText(lastLiveAt, now)}".trimEnd()
    SessionState.FAILED -> "Couldn't connect"
    SessionState.CLOSED -> "Closed"
}

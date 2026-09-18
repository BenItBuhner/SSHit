package app.berth.android.ui.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthField
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Workspace

enum class Library { HOSTS, KEYS, SETTINGS }

/**
 * The drawer: workspace chips across the top, the current workspace's sessions, then the library.
 * 304 dp wide on surface.1; rows on surface.2; the active row on surface.3.
 */
@Composable
fun Rail(
    vm: AppViewModel,
    onSessionTap: (String) -> Unit,
    onNewSession: () -> Unit,
    onLibrary: (Library) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val workspaces by vm.workspaces.collectAsState()
    val currentWorkspaceId by vm.currentWorkspaceId.collectAsState()
    val sessions by vm.workspaceSessions.collectAsState()
    val active by vm.activeSession.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())
    var newWorkspace by remember { mutableStateOf(false) }
    val now = ageTicker()

    Column(
        modifier
            .width(304.dp)
            .fillMaxHeight()
            .background(c.surface1)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (ws in workspaces) {
                val needs = records.any { it.workspaceId == ws.id && it.needsAttention }
                WorkspaceChip(ws, selected = ws.id == currentWorkspaceId, attention = needs) { vm.setWorkspace(ws.id) }
            }
            Box(
                Modifier
                    .width(64.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(BerthRadius.row))
                    .clickable { newWorkspace = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", style = BerthType.title, color = c.text2)
            }
        }
        if (newWorkspace) {
            var name by remember { mutableStateOf("") }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BerthField(name, { name = it }, placeholder = "Workspace name")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BerthButton("Create", kind = ButtonKind.PRIMARY, enabled = name.isNotBlank(), onClick = {
                        vm.createWorkspace(name.trim())
                        newWorkspace = false
                    })
                    BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = { newWorkspace = false })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    selected = active?.id == session.id,
                    now = now,
                    onTap = { onSessionTap(session.id) },
                    onReconnect = { vm.reconnect(session.id) },
                    onDetach = { vm.detach(session.id) },
                    onClose = { vm.close(session.id) },
                )
            }
            item {
                ListRow(
                    title = "New session",
                    surface = c.surface1,
                    minHeight = 48.dp,
                    onClick = onNewSession,
                    titleColor = c.text2,
                    leading = {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Text("+", style = BerthType.title, color = c.text2)
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LibraryLink("Hosts") { onLibrary(Library.HOSTS) }
            LibraryLink("Keys") { onLibrary(Library.KEYS) }
            LibraryLink("Settings") { onLibrary(Library.SETTINGS) }
        }
    }
}

@Composable
private fun LibraryLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = BerthType.label,
        color = Berth.colors.text2,
        modifier = Modifier
            .clip(RoundedCornerShape(BerthRadius.swatch))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

@Composable
private fun WorkspaceChip(ws: Workspace, selected: Boolean, attention: Boolean, onClick: () -> Unit) {
    val c = Berth.colors
    Column(
        Modifier
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(if (selected) c.surface3 else c.surface1)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .width(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Swatch(ws.color, ws.monogram, 48.dp, attention = attention)
        Text(ws.name, style = BerthType.caption, color = if (selected) c.text1 else c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 56 dp session row: swatch with state dot, title, subtitle, and an age when not Live. */
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
) {
    val c = Berth.colors
    val record by session.record.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = record.title.ifBlank { record.hostSnapshot.name },
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
                } else if (record.lastLiveAt != null && !selected) {
                    Text("", style = BerthType.caption)
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

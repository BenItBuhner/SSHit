package app.berth.android.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TabKind
import app.berth.domain.model.Workspace

/**
 * The tab's long-press menu (spec C3): Duplicate, Rename, then the other kind on the same host
 * (Files from a terminal tab, Terminal from a Files tab), Move to group ▸, Detach (Reconnect when
 * detached or failed; a Files tab has no connection of its own to detach), and last the closes,
 * Close and Close others, in the destructive tint. The order follows Chrome: the menu opens under
 * the tab on release, so the rows nearest the finger are the ones that change nothing for good,
 * and the closes sit furthest from where a long-press lets go. Move to group swaps the panel for
 * the groups as rows with swatches and a New group row; Back returns. Shared by the strip and the
 * switcher cards.
 */
@Composable
fun TabMenu(
    expanded: Boolean,
    record: SessionRecord,
    groups: List<Workspace>,
    actions: TabActions,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    var groupsPage by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) groupsPage = false }
    val id = record.id
    val files = record.kind == TabKind.Files
    // On a window with two panes (spec C23) a tab in neither can open beside the active one.
    val carry = LocalTabCarry.current
    val beside = carry != null && carry.activeId != id && carry.sideOf(id) == null
    MenuPanel(expanded = expanded, onDismiss = onDismiss) {
        if (!groupsPage) {
            if (beside) MenuRow("Open beside") { onDismiss(); actions.openInPane(id, null) }
            MenuRow("Duplicate") { onDismiss(); actions.duplicate(id) }
            MenuRow("Rename") { onDismiss(); actions.rename(id) }
            // A Files tab offers the terminal it rides; a terminal offers Files; a Tunnels tab, with no shell of its own, offers both.
            if (files || record.kind == TabKind.Tunnels) MenuRow("Terminal") { onDismiss(); actions.openTerminal(id) }
            if (!files) MenuRow("Files") { onDismiss(); actions.openFiles(id) }
            MenuRow("Move to group", trailing = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) }) { groupsPage = true }
            when {
                record.state.isActive -> if (!files) MenuRow("Detach") { onDismiss(); actions.detach(id) }
                record.state != SessionState.CLOSED -> MenuRow("Reconnect") { onDismiss(); actions.reconnect(id) }
            }
            MenuRow("Close", destructive = true) { onDismiss(); actions.close(id) }
            MenuRow("Close others", destructive = true) { onDismiss(); actions.closeOthers(id) }
        } else {
            MenuRow("Move to group", leading = { BerthIcon(BerthIcons.back, tint = c.text2, size = 20.dp) }, color = c.text2) { groupsPage = false }
            for (group in groups) {
                val current = group.id == record.workspaceId
                MenuRow(
                    group.name,
                    leading = { Swatch(group.color, group.monogram, 20.dp) },
                    trailing = { if (current) Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent)) },
                    enabled = !current,
                ) { onDismiss(); actions.moveToGroup(id, group.id) }
            }
            MenuRow("New group", leading = { BerthIcon(BerthIcons.add, tint = c.text2, size = 20.dp) }) { onDismiss(); actions.moveToNewGroup(id) }
        }
    }
}

/**
 * The chip's long-press menu (spec C3, Groups): Rename, Colour, New tab here, Collapse or Expand,
 * then the two that end runtimes at the tail in the destructive tint, Close group and Delete
 * group; the host confirms both when they would cut a connection.
 */
@Composable
fun GroupMenu(
    expanded: Boolean,
    group: Workspace,
    actions: TabActions,
    onDismiss: () -> Unit,
) {
    val id = group.id
    MenuPanel(expanded = expanded, onDismiss = onDismiss) {
        MenuRow("Rename") { onDismiss(); actions.editGroup(id) }
        MenuRow("Colour") { onDismiss(); actions.editGroup(id) }
        MenuRow("New tab here") { onDismiss(); actions.newTabIn(id) }
        MenuRow(if (group.collapsed) "Expand" else "Collapse") { onDismiss(); actions.setGroupCollapsed(id, !group.collapsed) }
        MenuRow("Close group", destructive = true) { onDismiss(); actions.closeGroup(id) }
        MenuRow("Delete group", destructive = true) { onDismiss(); actions.deleteGroup(id) }
    }
}

/** Floating panel at the row radius on `surface.3` with 8 dp padding (spec C3, Long-press menu). */
@Composable
private fun MenuPanel(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Berth.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = c.surface3,
        shape = RoundedCornerShape(BerthRadius.row),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 8.dp)) {
            androidx.compose.foundation.layout.Column { content() }
        }
    }
}

/** One 44 dp row in Body: an optional leading swatch or glyph, the label, an optional trailing mark. */
@Composable
private fun MenuRow(
    text: String,
    destructive: Boolean = false,
    enabled: Boolean = true,
    color: Color = if (destructive) Berth.colors.danger else Berth.colors.text1,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Text(text, style = BerthType.body, color = if (enabled) color else color.copy(alpha = 0.5f), modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
        },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.clip(RoundedCornerShape(BerthRadius.swatch)),
    )
}

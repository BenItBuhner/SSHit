package app.berth.android.ui.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.TabSlot
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Workspace

/**
 * The switcher (spec C3): every tab as a card with its frozen frame, in a grid grouped in strip
 * order with the group chip heading each run. Two columns in portrait, three in landscape, four on
 * expanded widths. Tap switches and dismisses, × closes, long-press opens the tab menu. The active
 * tab's card sits on `surface.3` and shows the live frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabSwitcher(
    vm: AppViewModel,
    actions: TabActions,
    onDismiss: () -> Unit,
    style: TabStripStyle = LocalTabStripStyle.current,
) {
    val c = Berth.colors
    val slots by vm.stripSlots.collectAsState()
    val groups by vm.workspaces.collectAsState()
    val activeId by vm.activeTabId.collectAsState()
    val now = ageTicker()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entries = remember(slots, groups, activeId) { buildEntries(slots, groups, activeId).filterNot { it is StripEntry.Plus } }
    var menuKey by remember { mutableStateOf<String?>(null) }

    // The last tab closing from here leaves nothing to switch between.
    LaunchedEffect(slots.isEmpty()) { if (slots.isEmpty()) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface1,
        shape = RoundedCornerShape(topStart = BerthRadius.sheet, topEnd = BerthRadius.sheet),
        dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tabs", style = BerthType.headline, color = c.text1, modifier = Modifier.weight(1f))
                IconAction(onClick = { onDismiss(); actions.newTab() }, description = "New tab") { BerthIcon(BerthIcons.add, tint = c.text1) }
                BerthButton("Done", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val columns = when {
                    maxWidth >= 840.dp -> 4
                    maxWidth >= 600.dp -> 3
                    else -> 2
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = rememberLazyGridState(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (entry in entries) {
                        when (entry) {
                            is StripEntry.Chip -> item(key = entry.key, span = { GridItemSpan(maxLineSpan) }, contentType = "chip") {
                                SwitcherChip(entry, style, actions, expanded = menuKey == entry.key, onMenu = { menuKey = if (it) entry.key else null })
                            }
                            is StripEntry.Tab -> item(key = entry.key, contentType = "tab") {
                                TabCard(
                                    slot = entry.slot,
                                    group = entry.group,
                                    groups = groups,
                                    active = entry.slot.id == activeId,
                                    vm = vm,
                                    actions = actions,
                                    now = now,
                                    expanded = menuKey == entry.key,
                                    onMenu = { menuKey = if (it) entry.key else null },
                                    onActivate = { actions.activate(entry.slot.id); onDismiss() },
                                )
                            }
                            StripEntry.Plus -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** A group's run header in the grid: the same pill as the strip, tap collapses, long-press for the group menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitcherChip(entry: StripEntry.Chip, style: TabStripStyle, actions: TabActions, expanded: Boolean, onMenu: (Boolean) -> Unit) {
    val resolved = rememberResolvedTabStyle(style)
    val group = entry.group
    val tint = group.color.rgb.toColor()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val attention by rememberGroupAttention(entry.tabs, enabled = group.collapsed).collectAsState(initial = false)
    val label = chipLabel(group, entry.tabs.size, style)
    Box(Modifier.fillMaxWidth().padding(top = if (entry.groupIndex == 0) 0.dp else 8.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .clip(resolved.chipShape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { actions.setGroupCollapsed(group.id, !group.collapsed) },
                    onLongClick = { onMenu(true) },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = "Group ${group.name}, ${entry.tabs.size} tabs" + if (group.collapsed) ", collapsed" else ""
                    onLongClick { onMenu(true); true }
                },
        ) {
            ChipPill(label = label, tint = tint, attention = attention, pressed = pressed, style = resolved)
        }
        GroupMenu(expanded = expanded, group = group, actions = actions, onDismiss = { onMenu(false) })
    }
}

/**
 * One card (spec C3, Switcher): panel radius, `surface.2` (`surface.3` active), 8 dp padding; a
 * 36 dp header with the 20 dp swatch, the title in Label and ×; a Caption subtitle; the frame at
 * row radius. A Files tab has no frame: its card shows the folder glyph on `surface.1`, the folder
 * in the subtitle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabCard(
    slot: TabSlot,
    group: Workspace?,
    groups: List<Workspace>,
    active: Boolean,
    vm: AppViewModel,
    actions: TabActions,
    now: Long,
    expanded: Boolean,
    onMenu: (Boolean) -> Unit,
    onActivate: () -> Unit,
) {
    val c = Berth.colors
    val session = slot.tab as? TerminalSession
    val record by slot.tab.record.collectAsState()
    val host = record.hostSnapshot
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> c.surface4
        active -> c.surface3
        else -> c.surface2
    }
    val theme = remember(host, group?.id, vm) { vm.themeFor(host, group?.id) }
    val font = remember(host, vm) { vm.fontFor(host) }
    val detached = record.state == SessionState.DETACHED || record.state == SessionState.CLOSED
    val title = record.displayTitle
    val subtitle = cardSubtitle(record, now)

    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(BerthRadius.panel))
                .background(fill)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onActivate,
                    onLongClick = { onMenu(true) },
                )
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    selected = active
                    contentDescription = listOfNotNull(title, subtitle.takeIf { it.isNotBlank() }, if (record.needsAttention) "needs attention" else null).joinToString(", ")
                    onClick { onActivate(); true }
                    onLongClick { onMenu(true); true }
                    customActions = listOf(
                        CustomAccessibilityAction("Close tab") { actions.close(slot.id); true },
                        CustomAccessibilityAction("More options") { onMenu(true); true },
                    )
                }
                .padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(4.dp))
                Swatch(host.color, host.monogram, 20.dp, state = record.state, attention = record.needsAttention)
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = BerthType.label,
                    color = if (detached) c.text2 else c.text1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .combinedClickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = { actions.close(slot.id) })
                        .semantics { contentDescription = "Close $title"; role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    BerthIcon(BerthIcons.close, tint = c.text2, size = 18.dp)
                }
            }
            Text(
                subtitle.ifBlank { " " },
                style = BerthType.caption,
                color = if (record.state == SessionState.FAILED) c.danger else c.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(BerthRadius.row))
                    .background(if (session != null) theme.background.toColor() else c.surface1)
                    .alpha(if (detached) 0.8f else 1f),
                contentAlignment = Alignment.Center,
            ) {
                if (session != null) {
                    // The same cursor rule as the Stage: only a live screen shows one, and only the active card follows changes.
                    FrameThumbnail(session, theme, font, live = active && record.state == SessionState.LIVE, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                } else {
                    BerthIcon(BerthIcons.folder, tint = c.text3, size = 36.dp)
                }
            }
        }
        TabMenu(expanded = expanded, record = record, groups = groups, actions = actions, onDismiss = { onMenu(false) })
    }
}

/**
 * Caption under the title: the last command, else the cwd, while live (two live cards on one host
 * then read `uptime` and `ls --color=always -la /` rather than the same folder); age and cwd when
 * detached; the state otherwise.
 */
internal fun cardSubtitle(record: SessionRecord, now: Long): String = when (record.state) {
    SessionState.LIVE -> record.lastCommand ?: record.cwd ?: "Live"
    SessionState.IDLE, SessionState.CONNECTING -> "Connecting\u2026"
    SessionState.RECONNECTING -> "Reconnecting\u2026"
    SessionState.DETACHED -> listOfNotNull(ageText(record.lastLiveAt, now).takeIf { it.isNotBlank() }, record.cwd).joinToString(" \u00B7 ")
    SessionState.FAILED -> "Couldn't connect"
    SessionState.CLOSED -> "Closed"
}

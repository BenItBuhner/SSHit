package app.berth.android.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.session.ClosedTab
import app.berth.android.session.PaneSide
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthSheet
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Workspace
import kotlinx.coroutines.delay

/** The New tab sheet is open, targeting [groupId] (the active group when null). */
data class NewTabRequest(val groupId: String?)

/** What the group editor is for. */
sealed interface GroupEditorRequest {
    data class Edit(val groupId: String) : GroupEditorRequest

    /** Create a group; then re-home [moveTabId] into it, or open the New tab sheet into it when [thenNewTab]. */
    data class Create(val moveTabId: String? = null, val thenNewTab: Boolean = false) : GroupEditorRequest
}

/**
 * A batch close that would cut a connection, waiting on the confirmation sheet (spec C3, Closing):
 * Close others keeps [Others.keepId], Close group empties [Group.groupId]. A batch has no Reopen,
 * so it is asked about first whenever any affected tab is connected or connecting.
 */
sealed interface CloseRequest {
    data class Others(val keepId: String) : CloseRequest
    data class Group(val groupId: String) : CloseRequest
}

/**
 * The tab UI that lives above every screen: which sheets are open and the Reopen bar. Owned by the
 * shell so the switcher, the New tab sheet and the menus' follow-ups work wherever they were asked for.
 */
class TabUiState {
    var newTab by mutableStateOf<NewTabRequest?>(null)
    var switcher by mutableStateOf(false)
    var renameId by mutableStateOf<String?>(null)
    var groupEditor by mutableStateOf<GroupEditorRequest?>(null)
    var deleteGroupId by mutableStateOf<String?>(null)
    var closeConfirm by mutableStateOf<CloseRequest?>(null)
    var closed by mutableStateOf<ClosedTab?>(null)

    val anySheet: Boolean get() = newTab != null || switcher || renameId != null || groupEditor != null || deleteGroupId != null || closeConfirm != null
}

@Composable
fun rememberTabUiState(): TabUiState = remember { TabUiState() }

/**
 * [TabActions] over the view model. Data changes go straight through; requests that need UI set the
 * matching field of [ui], and [onActivated] returns the shell to the Stage after a switch.
 */
class ShellTabActions(
    private val vm: AppViewModel,
    private val ui: TabUiState,
    private val onActivated: () -> Unit,
) : TabActions {
    override fun activate(id: String) {
        vm.setActive(id)
        onActivated()
    }

    override fun close(id: String) {
        val closed = vm.close(id) ?: return
        // Only a tab that was connected earns the bar; detached tabs close quietly (spec C3, Closing).
        if (closed.wasActive) ui.closed = closed
    }

    // A single close has Reopen; a batch does not, so one that would cut a connection asks first.
    override fun closeOthers(id: String) {
        val others = vm.records.value.filter { it.id != id }
        if (others.any { it.state.isActive }) ui.closeConfirm = CloseRequest.Others(id) else vm.closeOthers(id)
    }
    override fun duplicate(id: String) = vm.duplicate(id)
    override fun rename(id: String) { ui.renameId = id }
    override fun moveToGroup(id: String, groupId: String) = vm.moveToGroup(id, groupId)
    override fun moveToNewGroup(id: String) { ui.groupEditor = GroupEditorRequest.Create(moveTabId = id) }
    override fun detach(id: String) = vm.detach(id)
    override fun reconnect(id: String) = vm.reconnect(id)
    override fun openFiles(id: String) {
        vm.openFiles(id)
        onActivated()
    }
    override fun openTerminal(id: String) {
        vm.openTerminal(id)
        onActivated()
    }
    override fun move(id: String, toIndex: Int, groupId: String?) = vm.moveTab(id, toIndex, groupId)
    override fun openInPane(id: String, side: PaneSide?) {
        vm.openInPane(id, side)
        onActivated()
    }
    override fun splitActive() {
        vm.splitActive()
        onActivated()
    }
    override fun closePane(side: PaneSide) = vm.closePane(side)
    override fun newTab() { ui.newTab = NewTabRequest(groupId = null) }
    override fun duplicateActive() { vm.activeTabId.value?.let(vm::duplicate) }
    override fun openSwitcher() { ui.switcher = true }
    override fun setGroupCollapsed(groupId: String, collapsed: Boolean) = vm.setWorkspaceCollapsed(groupId, collapsed)
    override fun editGroup(groupId: String) { ui.groupEditor = GroupEditorRequest.Edit(groupId) }
    override fun newTabIn(groupId: String) { ui.newTab = NewTabRequest(groupId) }
    override fun closeGroup(groupId: String) {
        val tabs = vm.records.value.filter { it.workspaceId == groupId }
        if (tabs.any { it.state.isActive }) ui.closeConfirm = CloseRequest.Group(groupId) else vm.closeGroup(groupId)
    }
    override fun deleteGroup(groupId: String) { ui.deleteGroupId = groupId }
    override fun moveGroup(groupId: String, toIndex: Int) = vm.moveGroup(groupId, toIndex)
}

/** Every sheet the tab model can ask for, shown over whatever screen is up. */
@Composable
fun TabSheets(vm: AppViewModel, ui: TabUiState, actions: TabActions, onAddHost: () -> Unit) {
    val groups by vm.workspaces.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())

    ui.newTab?.let { request ->
        NewTabSheet(vm = vm, groupId = request.groupId, onDismiss = { ui.newTab = null }, onAddHost = onAddHost)
    }
    if (ui.switcher) {
        TabSwitcher(vm = vm, actions = actions, onDismiss = { ui.switcher = false })
    }
    ui.renameId?.let { id ->
        val record = records.firstOrNull { it.id == id }
        if (record == null) {
            ui.renameId = null
        } else {
            RenameTabSheet(record = record, onRename = { vm.rename(id, it) }, onDismiss = { ui.renameId = null })
        }
    }
    ui.groupEditor?.let { request ->
        when (request) {
            is GroupEditorRequest.Edit -> {
                val group = groups.firstOrNull { it.id == request.groupId }
                if (group == null) {
                    ui.groupEditor = null
                } else {
                    GroupEditorSheet(
                        group = group,
                        onCreate = { _, _, _ -> },
                        onRename = { name, monogram -> vm.renameWorkspace(group.id, name, monogram) },
                        onRecolor = { vm.setWorkspaceColor(group.id, it) },
                        onDismiss = { ui.groupEditor = null },
                        onReconnectAtLaunch = { vm.setWorkspaceReconnectAtLaunch(group.id, it) },
                    )
                }
            }
            is GroupEditorRequest.Create -> GroupEditorSheet(
                group = null,
                onCreate = { name, color, monogram ->
                    vm.createWorkspace(name, switchTo = request.moveTabId == null, color = color, monogram = monogram) { created ->
                        request.moveTabId?.let { vm.moveToGroup(it, created.id) }
                        if (request.thenNewTab) ui.newTab = NewTabRequest(created.id)
                    }
                },
                onRename = { _, _ -> },
                onRecolor = {},
                onDismiss = { ui.groupEditor = null },
            )
        }
    }
    ui.deleteGroupId?.let { id ->
        val group = groups.firstOrNull { it.id == id }
        if (group == null || groups.size < 2) {
            ui.deleteGroupId = null
        } else {
            DeleteGroupSheet(
                group = group,
                groups = groups,
                tabCount = records.count { it.workspaceId == id },
                onDelete = { closeTabs -> vm.deleteWorkspace(id, closeTabs) },
                onDismiss = { ui.deleteGroupId = null },
            )
        }
    }
    ui.closeConfirm?.let { request ->
        // Re-read the affected tabs at sheet time: a tab that dropped or closed since the menu row was
        // tapped changes the count, and if nothing connected remains the question no longer applies.
        val affected = when (request) {
            is CloseRequest.Others -> records.filter { it.id != request.keepId }
            is CloseRequest.Group -> records.filter { it.workspaceId == request.groupId }
        }
        val groupName = (request as? CloseRequest.Group)?.let { r -> groups.firstOrNull { it.id == r.groupId }?.name }
        if (affected.none { it.state.isActive } || (request is CloseRequest.Group && groupName == null)) {
            ui.closeConfirm = null
        } else {
            ConfirmCloseSheet(
                title = when (request) {
                    is CloseRequest.Others -> "Close ${plural(affected.size, "other tab")}?"
                    is CloseRequest.Group -> "Close ${groupName}'s ${plural(affected.size, "tab")}?"
                },
                affected = affected,
                onClose = {
                    when (request) {
                        is CloseRequest.Others -> vm.closeOthers(request.keepId)
                        is CloseRequest.Group -> vm.closeGroup(request.groupId)
                    }
                },
                onDismiss = { ui.closeConfirm = null },
            )
        }
    }
}

/** Delete group, with what happens to its tabs spelled out (spec C3, Groups). */
@Composable
private fun DeleteGroupSheet(
    group: Workspace,
    groups: List<Workspace>,
    tabCount: Int,
    onDelete: (closeTabs: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val ordered = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    val index = ordered.indexOfFirst { it.id == group.id }
    val receiver = ordered.getOrNull(index - 1) ?: ordered.getOrNull(index + 1)
    ConfirmSheet(
        title = "Delete ${group.name}?",
        body = when {
            tabCount == 0 -> "The group is empty."
            receiver != null -> "Its ${plural(tabCount, "tab")} can move to ${receiver.name}, or close."
            else -> "Its ${plural(tabCount, "tab")} will close."
        },
        onDismiss = onDismiss,
    ) {
        if (tabCount > 0 && receiver != null) {
            BerthButton("Move tabs", kind = ButtonKind.PRIMARY, onClick = { onDelete(false); onDismiss() })
            BerthButton("Close tabs", kind = ButtonKind.DESTRUCTIVE, onClick = { onDelete(true); onDismiss() })
        } else {
            BerthButton("Delete", kind = ButtonKind.DESTRUCTIVE, onClick = { onDelete(true); onDismiss() })
        }
    }
}

/**
 * Close others / Close group when the batch would cut a connection (spec C3, Closing): a single
 * close has the Reopen bar, a batch has none, so it says how many shells end and asks first.
 */
@Composable
private fun ConfirmCloseSheet(
    title: String,
    affected: List<SessionRecord>,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val connected = affected.count { it.state.isActive }
    ConfirmSheet(
        title = title,
        body = buildString {
            append(
                when {
                    connected == affected.size && connected == 1 -> "It is connected"
                    connected == affected.size -> "All $connected are connected"
                    connected == 1 -> "1 is connected"
                    else -> "$connected are connected"
                },
            )
            append(if (connected == 1) "; its shell ends. " else "; their shells end. ")
            append("A batch close has no Reopen.")
        },
        onDismiss = onDismiss,
    ) {
        BerthButton("Close tabs", kind = ButtonKind.DESTRUCTIVE, onClick = { onClose(); onDismiss() })
    }
}

/**
 * The confirmation sheet shared by Delete group and the batch closes: title, one line of Body in
 * `text.2` saying what happens, then the actions with Cancel last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = Berth.colors
    BerthSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(title)
            Text(body, style = BerthType.body, color = c.text2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** How long a [NoticeBar] stays before its owner takes it down. */
const val NOTICE_BAR_MS = 6_000L

/**
 * "Closed prod-web · Reopen" for six seconds after a connected tab closes (spec C3, Closing), as
 * the [NoticeSlot]'s notice while it is up: Reopen recreates the tab in its old slot and connects again.
 */
@Composable
fun reopenNotice(ui: TabUiState, vm: AppViewModel): Notice? {
    val closed = ui.closed
    LaunchedEffect(closed) {
        if (closed == null) return@LaunchedEffect
        delay(NOTICE_BAR_MS)
        if (ui.closed === closed) ui.closed = null
    }
    return closed?.let { tab ->
        Notice("Closed ${tab.record.displayTitle}", "Reopen") {
            vm.reopen(tab)
            ui.closed = null
        }
    }
}

/** What a [NoticeBar] says and offers: its line, how many lines it may take, and the one action beside it. */
class Notice(val text: String, val action: String, val maxLines: Int = 1, val onAction: () -> Unit)

/**
 * The one bar at the Stage's bottom edge, for everything that shares it: `Closed prod-web · Reopen`,
 * a link's reason, `Sessions keep running…`, `Landed in /tmp · Paste path`. [notices] are those
 * sources under a key each, up (a [Notice]) or down (null). Of the ones up at once the one raised
 * last shows, and when it goes the one raised before it is back if it is still up: a link's reason
 * over Landed, and Landed again when the reason is dismissed. Two raised in one frame show the
 * later in [notices]' order. A source whose notice would rise with another's and outlast it is the
 * caller's to hold down while the other is up, since the slot knows nothing of how long each
 * stays: the shell holds Landed down while Reopen is up, so closing a tab whose neighbour holds
 * paths shows Reopen's six seconds and then Landed (`AppRoot`). One bar, so two never draw over
 * each other and the state pill has one band to stand clear of ([BottomEdge]). A source's notice
 * may change while up (a count growing) and keeps its place; the last notice shown is kept through
 * the bar's exit, so the text does not blank as it slides away.
 */
@Composable
fun NoticeSlot(notices: Map<Any, Notice?>, modifier: Modifier = Modifier) {
    val up = notices.filterValues { it != null }.keys
    // The keys up in the order they were raised: one that went down leaves, one just up goes on the
    // end. Brought up to date in the composition itself rather than an effect after it, so a notice
    // shows on the frame it is raised; the same inputs give the same order, so a composition run
    // again or thrown away leaves it right.
    val raised = remember { ArrayList<Any>() }
    raised.retainAll(up)
    for (key in up) if (key !in raised) raised += key
    val shown = raised.lastOrNull()?.let { notices[it] }
    val kept = remember { Kept<Notice?>(null) }
    if (shown != null) kept.value = shown
    val bar = kept.value
    NoticeBar(
        visible = shown != null,
        text = bar?.text ?: "",
        action = bar?.action ?: "",
        onAction = { bar?.onAction?.invoke() },
        modifier = modifier,
        maxLines = bar?.maxLines ?: 1,
    )
}

/** A value remembered across compositions and written in them, that nothing subscribes to: not snapshot state. */
private class Kept<T>(var value: T)

/**
 * What stands at the window's bottom edge, above the keyboard and the navigation bar, so the two
 * things that float there agree by construction and not by their sizes happening to: each
 * [NoticeBar] that is showing records the height of its band (the bar and the 12 dp under it)
 * under a key of its own while it is composed, and the Stage records the height of the chrome it
 * has across the bottom (the Deck, or its strip, or nothing on a detached frame). The Stage's
 * state pill, which floats just above that chrome, reads [pillClearance]: how far the tallest bar
 * reaches past the chrome, and so how far the pill has to stand up to be clear of it. One per
 * shell, through [LocalBottomEdge]; a bar or a Stage composed outside one talks to the default,
 * which is the same rule with nobody else listening.
 */
class BottomEdge {
    private val bands = mutableStateMapOf<Any, Dp>()

    /** The height of the Stage's bottom chrome, without the insets it pads for; 0 while it has none. */
    var chrome: Dp by mutableStateOf(0.dp)

    /** The tallest notice band showing now, without the insets it stands on; 0 while none is. */
    val noticeBand: Dp get() = bands.values.maxOfOrNull { it } ?: 0.dp

    /** How far the Stage's state pill has to stand above the chrome to be clear of every bar showing. */
    val pillClearance: Dp get() = (noticeBand - chrome).coerceAtLeast(0.dp)

    fun setBand(key: Any, height: Dp) {
        bands[key] = height
    }

    fun clearBand(key: Any) {
        bands.remove(key)
    }
}

val LocalBottomEdge = compositionLocalOf { BottomEdge() }

/**
 * The Stage's one-line notice with one action, `Closed prod-web · Reopen` (spec C3, Closing) and
 * `Notifications are off · Settings` (spec C21): a full-radius bar on `surface.3` above the
 * keyboard and the navigation bar, Caption text, a middle dot, the action in accent. It stays
 * composed and [visible] drives it, so the exit animates; the owner decides when it goes. A line
 * that has to be read whole (`Sessions keep running. Detach all from the notification.`, spec
 * Part B) asks for [maxLines] of two and the bar grows to hold it at the font cap, its radius
 * kept at the one-line pill's so the two shapes agree. Whatever its height, the bar tells the
 * [BottomEdge] its band while it shows, and the Stage's state pill stands clear of it by that.
 */
@Composable
fun NoticeBar(visible: Boolean, text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val c = Berth.colors
    val edge = LocalBottomEdge.current
    val density = LocalDensity.current
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = BerthMotion.riseIn(),
            exit = BerthMotion.sinkOut(),
        ) {
            // The band is recorded for as long as the bar is composed, the exit's slide included, and let go with it.
            val key = remember { Any() }
            DisposableEffect(edge, key) { onDispose { edge.clearBand(key) } }
            Row(
                Modifier
                    .onSizeChanged { edge.setBand(key, with(density) { it.height.toDp() }) }
                    .padding(bottom = 12.dp)
                    // As tall as its lines ask, 44 at the least; fixed to that, so the action fills it and no further.
                    .heightIn(min = 44.dp)
                    .height(IntrinsicSize.Min)
                    .drawBehind {
                        // 6 dp of air above and below the pill; one line leaves the pill at 32.
                        val h = size.height - 12.dp.toPx()
                        drawRoundRect(color = c.surface3, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(16.dp.toPx()))
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The text yields to the action: a long line (a link's reason) ends in an ellipsis rather than pushing the action off the bar.
                Text(
                    text,
                    style = BerthType.caption,
                    color = c.text2,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = if (maxLines > 1) 8.dp else 0.dp),
                )
                Text("\u00B7", style = BerthType.caption, color = c.text3)
                BarAction(action, onAction)
            }
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .drawBehind {
                if (pressed || focused) {
                    val h = 24.dp.toPx()
                    drawRoundRect(color = c.surface4, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                }
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.caption, color = c.accent, maxLines = 1)
    }
}

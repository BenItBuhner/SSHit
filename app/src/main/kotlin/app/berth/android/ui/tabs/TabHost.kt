package app.berth.android.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.berth.android.session.ClosedTab
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.SheetHandle
import app.berth.android.ui.components.SheetTitle
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
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
 * The tab UI that lives above every screen: which sheets are open and the Reopen bar. Owned by the
 * shell so the switcher, the New tab sheet and the menus' follow-ups work wherever they were asked for.
 */
class TabUiState {
    var newTab by mutableStateOf<NewTabRequest?>(null)
    var switcher by mutableStateOf(false)
    var renameId by mutableStateOf<String?>(null)
    var groupEditor by mutableStateOf<GroupEditorRequest?>(null)
    var deleteGroupId by mutableStateOf<String?>(null)
    var closed by mutableStateOf<ClosedTab?>(null)

    val anySheet: Boolean get() = newTab != null || switcher || renameId != null || groupEditor != null || deleteGroupId != null
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

    override fun closeOthers(id: String) = vm.closeOthers(id)
    override fun duplicate(id: String) = vm.duplicate(id)
    override fun rename(id: String) { ui.renameId = id }
    override fun moveToGroup(id: String, groupId: String) = vm.moveToGroup(id, groupId)
    override fun moveToNewGroup(id: String) { ui.groupEditor = GroupEditorRequest.Create(moveTabId = id) }
    override fun detach(id: String) = vm.detach(id)
    override fun reconnect(id: String) = vm.reconnect(id)
    override fun move(id: String, toIndex: Int, groupId: String?) = vm.moveTab(id, toIndex, groupId)
    override fun newTab() { ui.newTab = NewTabRequest(groupId = null) }
    override fun duplicateActive() { vm.activeSessionId.value?.let(vm::duplicate) }
    override fun openSwitcher() { ui.switcher = true }
    override fun setGroupCollapsed(groupId: String, collapsed: Boolean) = vm.setWorkspaceCollapsed(groupId, collapsed)
    override fun editGroup(groupId: String) { ui.groupEditor = GroupEditorRequest.Edit(groupId) }
    override fun newTabIn(groupId: String) { ui.newTab = NewTabRequest(groupId) }
    override fun closeGroup(groupId: String) = vm.closeGroup(groupId)
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
                        onCreate = { _, _ -> },
                        onRename = { vm.renameWorkspace(group.id, it) },
                        onRecolor = { vm.setWorkspaceColor(group.id, it) },
                        onDismiss = { ui.groupEditor = null },
                    )
                }
            }
            is GroupEditorRequest.Create -> GroupEditorSheet(
                group = null,
                onCreate = { name, color ->
                    vm.createWorkspace(name, switchTo = request.moveTabId == null, color = color) { created ->
                        request.moveTabId?.let { vm.moveToGroup(it, created.id) }
                        if (request.thenNewTab) ui.newTab = NewTabRequest(created.id)
                    }
                },
                onRename = {},
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
}

/** Delete group, with what happens to its tabs spelled out (spec C3, Groups). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteGroupSheet(
    group: Workspace,
    groups: List<Workspace>,
    tabCount: Int,
    onDelete: (closeTabs: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Berth.colors
    val ordered = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    val index = ordered.indexOfFirst { it.id == group.id }
    val receiver = ordered.getOrNull(index - 1) ?: ordered.getOrNull(index + 1)
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
            SheetTitle("Delete ${group.name}?")
            Text(
                when {
                    tabCount == 0 -> "The group is empty."
                    receiver != null -> "Its ${plural(tabCount, "tab")} can move to ${receiver.name}, or close."
                    else -> "Its ${plural(tabCount, "tab")} will close."
                },
                style = BerthType.body,
                color = c.text2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tabCount > 0 && receiver != null) {
                    BerthButton("Move tabs", kind = ButtonKind.PRIMARY, onClick = { onDelete(false); onDismiss() })
                    BerthButton("Close tabs", kind = ButtonKind.DESTRUCTIVE, onClick = { onDelete(true); onDismiss() })
                } else {
                    BerthButton("Delete", kind = ButtonKind.DESTRUCTIVE, onClick = { onDelete(true); onDismiss() })
                }
                BerthButton("Cancel", kind = ButtonKind.TEXT, onClick = onDismiss)
            }
        }
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * "Closed prod-web · Reopen" for six seconds after a connected tab closes (spec C3, Closing): a
 * full-radius bar on `surface.3` above the keyboard and the navigation bar. Reopen recreates the tab
 * in its old slot and connects again.
 */
@Composable
fun ReopenBar(ui: TabUiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val closed = ui.closed
    LaunchedEffect(closed) {
        if (closed == null) return@LaunchedEffect
        delay(6_000)
        if (ui.closed === closed) ui.closed = null
    }
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = closed != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            val shown = remember { mutableStateOf(closed) }
            if (closed != null) shown.value = closed
            val record = shown.value?.record
            Row(
                Modifier
                    .padding(bottom = 12.dp)
                    .height(44.dp)
                    .drawBehind {
                        val h = 32.dp.toPx()
                        drawRoundRect(color = c.surface3, topLeft = Offset(0f, (size.height - h) / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Closed ${record?.displayTitle ?: ""}",
                    style = BerthType.caption,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text("\u00B7", style = BerthType.caption, color = c.text3)
                BarAction("Reopen") {
                    shown.value?.let(vm::reopen)
                    ui.closed = null
                }
            }
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { role = Role.Button }
            .drawBehind {
                if (pressed) {
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

package app.berth.android.ui.rail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.ListRow
import app.berth.android.ui.components.SectionLabel
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.tabs.GroupMenu
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.Workspace

/** The library rows. Files is not one: a host's files are a tab kind (spec C3), reached from the host or its terminal tab. */
enum class Library { HOSTS, KEYS, TUNNELS, SNIPPETS, SETTINGS }

/**
 * The drawer (spec C7): a secondary surface for jumping between groups and for the library. Not a
 * tab switcher; tabs are (C3). 304 dp on `surface.1`: the groups as 44 dp rows (swatch 24 with a ring
 * when a tab in the group needs attention, name in Body, tab count in Caption; the current group on
 * `surface.3`), New group, then the library rows with Settings last. The Groups label is the way
 * to the groups overview (spec C8, [onGroups]), and carries the chevron every navigating row does.
 * On an expanded window it stands as the rail (spec C23, A12): the same column at [width] 280 dp,
 * in place beside the Stage.
 */
@Composable
fun Drawer(
    vm: AppViewModel,
    actions: TabActions,
    onGroupTap: (String) -> Unit,
    onNewGroup: () -> Unit,
    onGroups: () -> Unit,
    onLibrary: (Library) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 304.dp,
) {
    val c = Berth.colors
    val groups by vm.workspaces.collectAsState()
    val currentId by vm.currentWorkspaceId.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())
    val ordered = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(c.surface1)
            .statusBarsPadding()
            .navigationBarsPadding()
            // On a phone lying on its side the cutout sits at one end (spec C23): the drawer's rows stay clear of it.
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "groups-label") { GroupsLabelRow(onClick = onGroups) }
            items(ordered, key = { it.id }) { group ->
                val count = records.count { it.workspaceId == group.id }
                val attention = records.any { it.workspaceId == group.id && it.needsAttention }
                Box {
                    ListRow(
                        title = group.name,
                        selected = group.id == currentId,
                        surface = c.surface1,
                        minHeight = 44.dp,
                        titleStyle = BerthType.body,
                        onClick = { onGroupTap(group.id) },
                        onLongClick = { menuFor = group.id },
                        leading = { Swatch(group.color, group.monogram, 24.dp, attention = attention) },
                        trailing = { Text(count.toString(), style = BerthType.caption, color = c.text3) },
                    )
                    GroupMenu(expanded = menuFor == group.id, group = group, actions = actions, onDismiss = { if (menuFor == group.id) menuFor = null })
                }
            }
            item(key = "new-group") {
                ListRow(
                    title = "New group",
                    surface = c.surface1,
                    minHeight = 44.dp,
                    titleColor = c.text2,
                    titleStyle = BerthType.body,
                    onClick = onNewGroup,
                    leading = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { BerthIcon(BerthIcons.add, size = 20.dp) }
                    },
                )
            }
            item(key = "library-label") { SectionLabel("Library", Modifier.padding(start = 4.dp, top = 24.dp, bottom = 6.dp)) }
            item(key = "lib-hosts") { LibraryRow("Hosts") { onLibrary(Library.HOSTS) } }
            item(key = "lib-keys") { LibraryRow("Keys") { onLibrary(Library.KEYS) } }
            item(key = "lib-tunnels") { LibraryRow("Tunnels") { onLibrary(Library.TUNNELS) } }
            item(key = "lib-snippets") { LibraryRow("Snippets") { onLibrary(Library.SNIPPETS) } }
            item(key = "lib-settings") { LibraryRow("Settings") { onLibrary(Library.SETTINGS) } }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * The Groups label as the row it is (spec C8): the Caption where the label stood, at the label's
 * 4 dp, with the chevron at the trailing edge, over a 44 dp target that takes the tonal step a
 * row does under a finger or the keyboard's focus. Said to a reader as what it opens.
 */
@Composable
private fun GroupsLabelRow(onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    val fill = when {
        pressed -> c.surface4
        focused -> c.surface3
        else -> c.surface1
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Open the groups overview", onClick = onClick)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Groups", Modifier.weight(1f), color = if (focused) c.accent else c.text2)
        BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp)
    }
}

/** A 44 dp library row: Label `text.1` with the trailing chevron every navigating row in Settings carries. */
@Composable
private fun LibraryRow(text: String, onClick: () -> Unit) {
    val c = Berth.colors
    ListRow(
        title = text,
        surface = c.surface1,
        minHeight = 44.dp,
        titleStyle = BerthType.label,
        onClick = onClick,
        trailing = { BerthIcon(BerthIcons.chevronRight, tint = c.text3, size = 20.dp) },
    )
}

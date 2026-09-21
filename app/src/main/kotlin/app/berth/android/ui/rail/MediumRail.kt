package app.berth.android.ui.rail

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.tabs.GroupMenu
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.domain.model.Workspace

/** The drawer's column on a medium width (spec C7, A12): 72 dp, a 48 dp slot in 12 dp of padding. */
val MediumRailWidth: Dp = 72.dp

/** A slot in the column: the 48 dp target every control is held to (spec A11), one to a row. */
private val SlotSize = 48.dp

/** The swatch in its slot, and what stays between the two: the slot's radius is the swatch's plus this, so the corners run concentric. */
private val SlotSwatch = 36.dp
private val SlotInset = (SlotSize - SlotSwatch) / 2

/**
 * The drawer standing on a medium width (spec C7, A12; #14 review): the same drawer as
 * [Drawer], with its words taken away. A persistent 72 dp column on `surface.1` beside the Stage:
 * the groups as their swatches, 36 dp with the attention ring, the current one on `surface.3`;
 * then New group as the `+` glyph; and at the foot the library as glyphs, Settings last, the order
 * the 280 dp rail keeps. A group's tap and long-press do what its row's do, and a screen reader
 * hears each swatch as the row it stands for (the group's name, its tab count, its attention) and
 * each glyph by the screen it opens. No labels and no lines: the run of swatches and the run of
 * glyphs are told apart by the room between them, as the rail's sections are.
 */
@Composable
fun MediumRail(
    vm: AppViewModel,
    actions: TabActions,
    onGroupTap: (String) -> Unit,
    onNewGroup: () -> Unit,
    onLibrary: (Library) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val groups by vm.workspaces.collectAsState()
    val currentId by vm.currentWorkspaceId.collectAsState()
    val records by vm.records.collectAsState(initial = emptyList())
    val ordered = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .width(MediumRailWidth)
            .fillMaxHeight()
            .background(c.surface1)
            .statusBarsPadding()
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = (MediumRailWidth - SlotSize) / 2, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The groups scroll when there are more than the height holds; the library under them stays put.
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            items(ordered, key = { it.id }) { group ->
                val count = records.count { it.workspaceId == group.id }
                val attention = records.any { it.workspaceId == group.id && it.needsAttention }
                Box {
                    RailSlot(
                        description = buildString {
                            append(group.name)
                            append(", ")
                            append(if (count == 1) "1 tab" else "$count tabs")
                            if (attention) append(", needs attention")
                        },
                        selected = group.id == currentId,
                        onClick = { onGroupTap(group.id) },
                        onLongClick = { menuFor = group.id },
                    ) {
                        Swatch(group.color, group.monogram, SlotSwatch, attention = attention)
                    }
                    GroupMenu(expanded = menuFor == group.id, group = group, actions = actions, onDismiss = { if (menuFor == group.id) menuFor = null })
                }
            }
            item(key = "new-group") {
                RailSlot(description = "New group", onClick = onNewGroup) { BerthIcon(BerthIcons.add, tint = c.text2) }
            }
        }
        // The rail's 24 dp between its Groups and its Library, kept even when the groups fill the height.
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryGlyph("Hosts", BerthIcons.hosts) { onLibrary(Library.HOSTS) }
            LibraryGlyph("Keys", BerthIcons.key) { onLibrary(Library.KEYS) }
            LibraryGlyph("Tunnels", BerthIcons.tunnel) { onLibrary(Library.TUNNELS) }
            LibraryGlyph("Snippets", BerthIcons.snippet) { onLibrary(Library.SNIPPETS) }
            LibraryGlyph("Settings", BerthIcons.settings) { onLibrary(Library.SETTINGS) }
        }
    }
}

/** A library screen as its glyph (spec C7): `text.2`, accent under the keyboard's focus, named for the screen it opens. */
@Composable
private fun LibraryGlyph(name: String, icon: Int, onClick: () -> Unit) {
    val c = Berth.colors
    RailSlot(description = name, onClick = onClick) { focused ->
        BerthIcon(icon, tint = if (focused) c.accent else c.text2)
    }
}

/**
 * One slot of the column: a 48 dp square that steps to `surface.3` when it is the current group,
 * pressed or holding the keyboard's focus, at the radius concentric with the swatch inside it, so
 * the row's tonal step (spec A6) becomes the slot's. One node to a screen reader, a button named
 * [description], selected when it is the current group; a long-press opens the group's menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailSlot(
    description: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    val bg by animateColorAsState(
        when {
            pressed -> c.surface4
            selected || focused -> c.surface3
            else -> Color.Transparent
        },
        tween(120),
        label = "slot",
    )
    Box(
        Modifier
            .size(SlotSize)
            .clip(RoundedCornerShape(BerthRadius.swatch + SlotInset))
            .background(bg)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            // The swatch's monogram and the glyph are decoration; the reader gets the group or the screen.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

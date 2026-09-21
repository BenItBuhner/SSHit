package app.berth.android.ui.groups

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthButton
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.ButtonKind
import app.berth.android.ui.components.LocalPanelSurface
import app.berth.android.ui.components.ScreenHeader
import app.berth.android.ui.components.StatusDot
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.components.linesAtFontScale
import app.berth.android.ui.stage.ageText
import app.berth.android.ui.stage.ageTicker
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthSpace
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.Workspace

/**
 * The groups overview (spec C8), reached from the drawer's Groups label: every group as a card on
 * `surface.2` at the panel radius, the current one a tonal step up with the A6 accent dot, in as
 * many columns as the width takes at [CARD_MIN_WIDTH]. A card is the group's swatch (ringed when a
 * tab in it needs the user) and name, its tab count with how many need the user, and up to three
 * of its tabs with the one mark each carries. Tap a card to switch to the group and go to the
 * Stage; long-press for its menu (Rename, Colour, New tab here, Move up and down, Close group,
 * Delete group), which Edit in the header puts under a tap as well, marking each card with the
 * menu glyph. The last card is New group on `surface.1`. The spec's vocabulary note (C3, C8)
 * makes these "groups" and their contents "tabs" everywhere the user reads them.
 */
@Composable
fun GroupsScreen(
    vm: AppViewModel,
    actions: TabActions,
    onBack: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val groups by vm.workspaces.collectAsState()
    val currentId by vm.currentWorkspaceId.collectAsState()
    val records by vm.records.collectAsState()
    val ordered = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    val now = ageTicker()
    var editing by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(c.surface0)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        ScreenHeader(title = "Groups", onBack = onBack, actions = {
            BerthButton(if (editing) "Done" else "Edit", onClick = { editing = !editing }, kind = ButtonKind.TEXT)
        })
        LazyVerticalGrid(
            columns = GridCells.Adaptive(CARD_MIN_WIDTH),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = BerthSpace.screenMargin, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(ordered, key = { _, group -> group.id }) { index, group ->
                val tabs = remember(records, group.id) { records.filter { it.workspaceId == group.id }.sortedBy { it.sortOrder } }
                Box {
                    GroupCard(
                        group = group,
                        tabs = tabs,
                        current = group.id == currentId,
                        editing = editing,
                        now = now,
                        onClick = { if (editing) menuFor = group.id else onOpenGroup(group.id) },
                        onLongClick = { menuFor = group.id },
                    )
                    GroupCardMenu(
                        expanded = menuFor == group.id,
                        group = group,
                        index = index,
                        count = ordered.size,
                        actions = actions,
                        onDismiss = { if (menuFor == group.id) menuFor = null },
                    )
                }
            }
            item(key = "new-group") { NewGroupCard(onClick = onNewGroup) }
        }
    }
}

/**
 * One group's card. One node to a reader, heard as the name, the count line, then each tab with
 * its mark (`live`, `needs you`, `detached 4 min ago`), and what a tap does; the long-press is the
 * menu in either mode, named so a reader finds it.
 */
@Composable
private fun GroupCard(
    group: Workspace,
    tabs: List<SessionRecord>,
    current: Boolean,
    editing: Boolean,
    now: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = Berth.colors
    val attention = tabs.any { it.needsAttention }
    val lines = remember(tabs, now) { GroupCards.lines(tabs, now) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    val fill by animateColorAsState(
        when {
            pressed -> c.surface4
            current || focused -> c.surface3
            else -> c.surface2
        },
        tween(120),
        label = "card",
    )
    CompositionLocalProvider(LocalPanelSurface provides fill) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = cardMinHeight())
                .clip(RoundedCornerShape(BerthRadius.panel))
                .background(fill)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (editing) "Group actions" else "Switch to ${group.name}",
                    onLongClickLabel = "Group actions",
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(group.color, group.monogram, 28.dp, attention = attention)
                Spacer(Modifier.width(10.dp))
                Text(
                    group.name,
                    style = BerthType.bodyMedium,
                    color = if (focused) c.accent else c.text1,
                    maxLines = linesAtFontScale(1),
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    editing -> BerthIcon(BerthIcons.moreVert, tint = c.text3, size = 20.dp)
                    current -> Box(Modifier.size(4.dp).clip(CircleShape).background(c.accent).semantics { contentDescription = "current group" })
                }
            }
            Text(GroupCards.countLine(tabs), style = BerthType.caption, color = c.text2, maxLines = linesAtFontScale(2), overflow = TextOverflow.Ellipsis)
            for (line in lines) TabLine(line, now)
        }
    }
}

/** A tab on its card: the title, then what it is doing, said once at the trailing edge. */
@Composable
private fun TabLine(line: GroupCards.Line, now: Long) {
    val c = Berth.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(line.title, style = BerthType.caption, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            // The ring the group's swatch and the strip's chip wear for a tab that needs the user, at the line's size.
            line.needsAttention -> Box(Modifier.size(10.dp).border(2.dp, c.attention, CircleShape).semantics { contentDescription = "needs you" })
            line.state.isActive -> StatusDot(line.state)
            line.age != null -> Text(
                line.age,
                style = BerthType.caption,
                color = c.text3,
                // `4m` on the card, `detached 4 min ago` to a reader, for whom the abbreviation is a letter.
                modifier = Modifier.semantics { contentDescription = "detached " + ageText(line.lastLiveAt, now) },
            )
        }
    }
}

/** The spec's New group card: `surface.1`, Label only, the same footprint as a group's card so the grid stays a grid. */
@Composable
private fun NewGroupCard(onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    val fill = when {
        pressed -> c.surface3
        focused -> c.surface2
        else -> c.surface1
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = cardMinHeight())
            .clip(RoundedCornerShape(BerthRadius.panel))
            .background(fill)
            .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("New group", style = BerthType.label, color = if (focused) c.accent else c.text2)
    }
}

/**
 * The card's menu: the chip's (C3) less Collapse, which is the strip's own concern, plus Move up
 * and Move down for the order the spec's Edit lets a drag change, each only where there is
 * somewhere to move to. Delete is not offered for the last group, which cannot be deleted.
 */
@Composable
private fun GroupCardMenu(
    expanded: Boolean,
    group: Workspace,
    index: Int,
    count: Int,
    actions: TabActions,
    onDismiss: () -> Unit,
) {
    val id = group.id
    BerthMenu(expanded = expanded, onDismiss = onDismiss) {
        BerthMenuItem("Rename", onClick = { onDismiss(); actions.editGroup(id) })
        BerthMenuItem("Colour", onClick = { onDismiss(); actions.editGroup(id) })
        BerthMenuItem("New tab here", onClick = { onDismiss(); actions.newTabIn(id) })
        if (index > 0) BerthMenuItem("Move up", onClick = { onDismiss(); actions.moveGroup(id, index - 1) })
        if (index < count - 1) BerthMenuItem("Move down", onClick = { onDismiss(); actions.moveGroup(id, index + 1) })
        BerthMenuItem("Close group", destructive = true, onClick = { onDismiss(); actions.closeGroup(id) })
        if (count > 1) BerthMenuItem("Delete group", destructive = true, onClick = { onDismiss(); actions.deleteGroup(id) })
    }
}

/**
 * A card's height with its three tab lines, so cards in a row stand level whether a group has
 * three tabs or none: the 28 dp header row, four Caption lines (the count and three tabs) at the
 * interface's font scale, the gaps between the five rows and the padding.
 */
@Composable
private fun cardMinHeight(): Dp {
    val captionLines = with(LocalDensity.current) { (BerthType.caption.lineHeight.value * 4).sp.toDp() }
    return 28.dp + captionLines + 6.dp * 4 + 32.dp
}

/** A card's least width; the grid takes as many columns as the screen's width allows at it (two on a phone, three on a tablet). */
private val CARD_MIN_WIDTH = 150.dp

package app.berth.android.ui.tabs

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.berth.android.session.TabSlot
import app.berth.android.session.TabSource
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.stage.DeckHaptics
import app.berth.android.ui.stage.rememberDeckHaptics
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.SessionState
import app.berth.domain.model.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs

// ---- keys and entries ------------------------------------------------------------------------------

internal const val PLUS_KEY = "plus"
private const val TAB_KEY_PREFIX = "t:"
private const val CHIP_KEY_PREFIX = "g:"

internal fun tabKey(id: String) = TAB_KEY_PREFIX + id
internal fun chipKey(groupId: String) = CHIP_KEY_PREFIX + groupId

/** One item of the strip in visual order: a group chip, a tab, or the plus tab at the end. */
internal sealed interface StripEntry {
    val key: String

    /** [stripIndex] is the tab's index in the full strip (hidden tabs included), what [TabActions.move] takes. */
    class Tab(val slot: TabSlot, val group: Workspace?, val stripIndex: Int, val groupIndex: Int) : StripEntry {
        override val key: String get() = tabKey(slot.id)
    }

    class Chip(val group: Workspace, val groupIndex: Int, val tabs: List<TabSource>) : StripEntry {
        override val key: String get() = chipKey(group.id)
    }

    data object Plus : StripEntry {
        override val key: String get() = PLUS_KEY
    }
}

/** Groups in order, each headed by its chip once a second group exists; a collapsed group shows only its active tab. */
internal fun buildEntries(slots: List<TabSlot>, groups: List<Workspace>, activeId: String?): List<StripEntry> {
    val ordered = groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt })
    val byGroup = slots.groupBy { it.groupId }
    val stripIndex = HashMap<String, Int>(slots.size * 2)
    slots.forEachIndexed { i, s -> stripIndex[s.id] = i }
    val showChips = ordered.size > 1
    val out = ArrayList<StripEntry>(slots.size + ordered.size + 1)
    ordered.forEachIndexed { gi, group ->
        val tabs = byGroup[group.id].orEmpty()
        if (showChips) out += StripEntry.Chip(group, gi, tabs.map { it.tab })
        for (slot in tabs) {
            if (group.collapsed && slot.id != activeId) continue
            out += StripEntry.Tab(slot, group, stripIndex.getValue(slot.id), gi)
        }
    }
    val known = ordered.mapTo(HashSet()) { it.id }
    for (slot in slots) if (slot.groupId !in known) out += StripEntry.Tab(slot, null, stripIndex.getValue(slot.id), -1)
    out += StripEntry.Plus
    return out
}

// ---- state ---------------------------------------------------------------------------------------------

/** A lifted item: where it was when lifted and where the finger took hold of it, both in the strip's coordinates. */
internal class TabDrag(val key: String, val startOffset: Int, val width: Int, val grabX: Float, val isChip: Boolean) {
    /** The entries a model move was issued against; the next crossing waits until they change (or 300 ms pass). */
    var pendingEntries: List<StripEntry>? = null
    var pendingAt: Long = 0L
}

/**
 * Scroll position plus the transient interaction state of a [TabStrip]: which item is lifted, how
 * far it travelled (read from the draw phase only, so a drag frame recomposes nothing), which
 * menu is open, and the tabs currently laid out (for the count tile's ring).
 */
@Stable
class TabStripState internal constructor(val listState: LazyListState) {
    internal var drag by mutableStateOf<TabDrag?>(null)
    internal var dragTravel by mutableFloatStateOf(0f)
    internal var lifted by mutableStateOf<String?>(null)
    internal var menuKey by mutableStateOf<String?>(null)
    internal var autoScroll by mutableIntStateOf(0)

    /** Ids of the tabs laid out right now, fully or partly; a tab with attention outside this set lights the count tile. */
    val visibleTabIds: Set<String> by derivedStateOf(structuralEqualityPolicy()) {
        listState.layoutInfo.visibleItemsInfo.mapNotNullTo(HashSet()) { item ->
            (item.key as? String)?.takeIf { it.startsWith(TAB_KEY_PREFIX) }?.substring(TAB_KEY_PREFIX.length)
        }
    }

    internal fun itemInfo(key: String): LazyListItemInfo? {
        val items = listState.layoutInfo.visibleItemsInfo
        for (i in items.indices) if (items[i].key == key) return items[i]
        return null
    }
}

@Composable
fun rememberTabStripState(): TabStripState {
    val listState = rememberLazyListState()
    return remember(listState) { TabStripState(listState) }
}

// ---- header and strip ----------------------------------------------------------------------------------

/**
 * The Stage header (spec C3): the scrolling [TabStrip] with weight 1, then the fixed [trailing]
 * slots (count tile, Overflow). Owns the status-bar inset and the chrome chosen by the style: a
 * flat toolbar on `surface.1`, or an island inset from the edges.
 */
@Composable
fun TabHeader(
    slots: List<TabSlot>,
    groups: List<Workspace>,
    activeId: String?,
    actions: TabActions,
    modifier: Modifier = Modifier,
    state: TabStripState = rememberTabStripState(),
    style: TabStripStyle = LocalTabStripStyle.current,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Berth.colors
    val resolved = rememberResolvedTabStyle(style)
    val row: @Composable (Modifier) -> Unit = { rowModifier ->
        Row(rowModifier.height(style.height), verticalAlignment = Alignment.CenterVertically) {
            TabStrip(slots, groups, activeId, actions, Modifier.weight(1f).fillMaxHeight(), state, style)
            trailing()
        }
    }
    when (style.chrome) {
        StripChrome.FLAT -> row(modifier.fillMaxWidth().background(c.surface1).statusBarsPadding())
        StripChrome.ISLAND -> Box(
            modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = style.islandInset, end = style.islandInset, top = style.islandInset),
        ) {
            row(Modifier.fillMaxWidth().clip(RoundedCornerShape(resolved.islandRadius)).background(c.surface1))
        }
    }
}

/**
 * The tab strip (spec C3): a lazy row of group chips, tabs and the plus tab. Tap switches;
 * long-press lifts (release for the menu, move to reorder live, hold near an edge to auto-scroll);
 * the active tab is kept in view. Every visual decision comes from [style]; every behaviour goes
 * through [actions]. Each tab observes its own record, so the strip itself recomposes only when
 * tabs open, close, move or the active tab changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(
    slots: List<TabSlot>,
    groups: List<Workspace>,
    activeId: String?,
    actions: TabActions,
    modifier: Modifier = Modifier,
    state: TabStripState = rememberTabStripState(),
    style: TabStripStyle = LocalTabStripStyle.current,
) {
    val resolved = rememberResolvedTabStyle(style)
    val entries = remember(slots, groups, activeId) { buildEntries(slots, groups, activeId) }
    val orderedGroups = remember(groups) { groups.sortedWith(compareBy<Workspace> { it.sortOrder }.thenBy { it.createdAt }) }
    val latestEntries = rememberUpdatedState(entries)
    val latestActions = rememberUpdatedState(actions)
    val haptics = rememberDeckHaptics()
    val density = LocalDensity.current
    val gapPx = with(density) { style.gap.toPx() }
    val edgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val tabCount = slots.size
    val scope = rememberCoroutineScope()

    val controller = remember(state, scope) { DragController(state, latestEntries, latestActions, haptics, scope) }
    controller.gapPx = gapPx
    controller.edgePx = edgePx

    // The active tab is scrolled into view in 120 ms (spec C3, Header row); a lift owns the scroll instead.
    LaunchedEffect(activeId, entries) {
        if (state.drag != null) return@LaunchedEffect
        val index = entries.indexOfFirst { it is StripEntry.Tab && it.slot.id == activeId }
        if (index < 0) return@LaunchedEffect
        val info = state.listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            state.listState.animateScrollToItem(index)
            return@LaunchedEffect
        }
        val delta = when {
            item.offset < info.viewportStartOffset -> item.offset - info.viewportStartOffset
            item.offset + item.size > info.viewportEndOffset -> item.offset + item.size - info.viewportEndOffset
            else -> 0
        }
        if (delta != 0) state.listState.animateScrollBy(delta.toFloat(), tween(120))
    }

    // Our own reorder changed the entries under a lifted item: pin the viewport by index so the
    // list does not scroll to follow the lifted key (LazyList anchors on the first visible item's key).
    val lastEntries = remember { mutableStateOf(entries) }
    if (state.drag != null && lastEntries.value !== entries) {
        SideEffect {
            lastEntries.value = entries
            state.listState.requestScrollToItem(state.listState.firstVisibleItemIndex, state.listState.firstVisibleItemScrollOffset)
        }
    } else if (lastEntries.value !== entries) {
        SideEffect { lastEntries.value = entries }
    }

    // Auto-scroll while a lifted item is held within the edge zone; crossings are re-checked per frame.
    LaunchedEffect(state.autoScroll) {
        val direction = state.autoScroll
        if (direction == 0) return@LaunchedEffect
        val step = with(density) { AUTO_SCROLL_STEP.toPx() } * direction
        while (state.autoScroll == direction && state.drag != null) {
            try {
                state.listState.scrollBy(step)
            } catch (e: CancellationException) {
                throw e
            }
            controller.checkCrossings()
            withFrameNanos { }
        }
    }

    LazyRow(
        modifier
            .selectableGroup()
            .clearAndSetSemantics { contentDescription = "Tabs, $tabCount open" },
        state = state.listState,
        contentPadding = PaddingValues(horizontal = style.edgeInset),
        horizontalArrangement = Arrangement.spacedBy(style.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(entries.size, key = { entries[it].key }, contentType = { entries[it]::class }) { index ->
            when (val entry = entries[index]) {
                is StripEntry.Tab -> TabItem(
                    entry = entry,
                    active = entry.slot.id == activeId,
                    groupCount = orderedGroups.size,
                    tabCount = tabCount,
                    style = resolved,
                    state = state,
                    controller = controller,
                    actions = actions,
                    groups = orderedGroups,
                    modifier = Modifier.liftable(entry.key, state),
                )
                is StripEntry.Chip -> GroupChip(
                    entry = entry,
                    style = resolved,
                    state = state,
                    controller = controller,
                    actions = actions,
                    modifier = Modifier.liftable(entry.key, state),
                )
                StripEntry.Plus -> PlusTab(style = resolved, actions = actions, modifier = Modifier.animateItem())
            }
        }
    }
}

/** The lifted item draws over its neighbours. */
private fun Modifier.liftable(key: String, state: TabStripState): Modifier = zIndex(if (state.drag?.key == key) 1f else 0f)

/** The 32 dp auto-scroll zone at either end of the strip and how far one frame scrolls (spec C3, Reorder). */
private val AUTO_SCROLL_EDGE = 32.dp
private val AUTO_SCROLL_STEP = 6.dp
private const val PENDING_MOVE_TIMEOUT_MS = 300L
private const val LONG_PRESS_MS = 400L

// ---- drag controller -----------------------------------------------------------------------------------

/**
 * Turns finger travel into model moves. Positions are in the lazy row's coordinates: the lifted
 * item's visual left edge is `startOffset + travel`, whatever the list did underneath it, so the
 * item stays under the finger through reorders and auto-scroll alike.
 */
internal class DragController(
    private val state: TabStripState,
    private val entries: State<List<StripEntry>>,
    private val actions: State<TabActions>,
    private val haptics: DeckHaptics,
    private val scope: CoroutineScope,
) {
    var gapPx = 0f
    var edgePx = 0f
    private var settle: Job? = null

    fun lift(key: String, grabX: Float, isChip: Boolean): Boolean {
        settle?.cancel()
        val item = state.itemInfo(key) ?: return false
        state.drag = TabDrag(key, item.offset, item.size, grabX, isChip)
        state.dragTravel = 0f
        state.lifted = key
        haptics.hold()
        return true
    }

    /** Finger moved by [dx]; clamps the visual to the viewport, checks crossings and the edge zones. */
    fun moveBy(dx: Float) {
        val drag = state.drag ?: return
        val info = state.listState.layoutInfo
        val minLeft = info.viewportStartOffset.toFloat()
        val maxLeft = (info.viewportEndOffset - drag.width).toFloat().coerceAtLeast(minLeft)
        state.dragTravel = (drag.startOffset + state.dragTravel + dx).coerceIn(minLeft, maxLeft) - drag.startOffset
        checkCrossings()
        val finger = drag.startOffset + state.dragTravel + drag.grabX
        state.autoScroll = when {
            finger < info.viewportStartOffset + edgePx -> -1
            finger > info.viewportEndOffset - edgePx -> 1
            else -> 0
        }
    }

    /** Applies at most one crossing per call; a second waits for the entries to reflect the first. */
    fun checkCrossings() {
        val drag = state.drag ?: return
        val list = entries.value
        val pending = drag.pendingEntries
        if (pending != null) {
            if (pending === list && SystemClock.uptimeMillis() - drag.pendingAt < PENDING_MOVE_TIMEOUT_MS) return
            drag.pendingEntries = null
        }
        val me = state.itemInfo(drag.key) ?: return
        val entry = list.getOrNull(me.index)?.takeIf { it.key == drag.key } ?: return
        val visualCenter = drag.startOffset + state.dragTravel + drag.width / 2f
        val items = state.listState.layoutInfo.visibleItemsInfo
        val next = items.firstOrNull { it.index == me.index + 1 }
        val prev = items.firstOrNull { it.index == me.index - 1 }
        val moved = when (entry) {
            is StripEntry.Tab -> when {
                next != null && visualCenter > next.offset + next.size / 2f -> crossTab(list, entry, list.getOrNull(next.index), forward = true, beyond = null)
                prev != null && visualCenter < prev.offset + prev.size / 2f -> crossTab(list, entry, list.getOrNull(prev.index), forward = false, beyond = list.getOrNull(prev.index - 1))
                else -> false
            }
            is StripEntry.Chip -> crossChip(list, entry, visualCenter, items)
            StripEntry.Plus -> false
        }
        if (moved) {
            drag.pendingEntries = list
            drag.pendingAt = SystemClock.uptimeMillis()
            haptics.reorderStep()
        }
    }

    /**
     * A tab passed its neighbour. Over a tab it swaps; over the next group's chip it joins that group
     * at the front; back over its own chip it joins the previous group at the end (spec C3, Reorder).
     */
    private fun crossTab(list: List<StripEntry>, me: StripEntry.Tab, neighbour: StripEntry?, forward: Boolean, beyond: StripEntry?): Boolean {
        val a = actions.value
        val id = me.slot.id
        when (neighbour) {
            is StripEntry.Tab -> a.move(id, if (forward) me.stripIndex + 1 else me.stripIndex - 1, null)
            is StripEntry.Chip -> {
                val target = if (forward) {
                    neighbour.group
                } else {
                    when (beyond) {
                        is StripEntry.Tab -> beyond.group ?: return false
                        is StripEntry.Chip -> beyond.group
                        else -> return false
                    }
                }
                if (target.collapsed) a.setGroupCollapsed(target.id, false)
                a.move(id, me.stripIndex, target.id)
            }
            else -> return false
        }
        return true
    }

    /** A chip passed the neighbouring group's chip: the whole group moves one place. */
    private fun crossChip(list: List<StripEntry>, me: StripEntry.Chip, visualCenter: Float, items: List<LazyListItemInfo>): Boolean {
        val chips = list.filterIsInstance<StripEntry.Chip>()
        val next = chips.firstOrNull { it.groupIndex == me.groupIndex + 1 }?.let { c -> items.firstOrNull { it.key == c.key } }
        val prev = chips.firstOrNull { it.groupIndex == me.groupIndex - 1 }?.let { c -> items.firstOrNull { it.key == c.key } }
        return when {
            next != null && visualCenter > next.offset + next.size / 2f -> {
                actions.value.moveGroup(me.group.id, me.groupIndex + 1)
                true
            }
            prev != null && visualCenter < prev.offset + prev.size / 2f -> {
                actions.value.moveGroup(me.group.id, me.groupIndex - 1)
                true
            }
            else -> false
        }
    }

    /**
     * Finger up: settle the item into its slot over 120 ms, then hand placement back to the list.
     * Runs on the strip's scope because the pointer scope may not suspend on anything but events.
     */
    fun drop() {
        state.autoScroll = 0
        state.lifted = null
        val drag = state.drag ?: return
        val rest = (state.itemInfo(drag.key)?.offset ?: drag.startOffset) - drag.startOffset.toFloat()
        settle = scope.launch {
            val travel = Animatable(state.dragTravel)
            try {
                travel.animateTo(rest, tween(120)) { state.dragTravel = value }
            } finally {
                if (state.drag === drag) state.drag = null
            }
        }
    }

    fun cancel() {
        settle?.cancel()
        state.autoScroll = 0
        state.lifted = null
        state.drag = null
    }
}

/** Where a press ended: released in place, taken over by the list's scroll (or moved off), or held. */
private sealed interface PressOutcome {
    data object Tap : PressOutcome
    data object Cancelled : PressOutcome
    class Held(val change: PointerInputChange) : PressOutcome
}

/**
 * Waits for the press that began with [down] to resolve. The strip's own scroll wins as soon as it
 * consumes a change; a release before the long-press timeout is a tap; holding still lifts.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitPressOutcome(down: PointerInputChange): PressOutcome {
    val slop = viewConfiguration.touchSlop
    var latest = down
    val outcome = withTimeoutOrNull(LONG_PRESS_MS) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull PressOutcome.Cancelled
            latest = change
            if (change.changedToUpIgnoreConsumed()) {
                return@withTimeoutOrNull if (change.isConsumed) PressOutcome.Cancelled else PressOutcome.Tap
            }
            if (change.isConsumed || (change.position - down.position).getDistance() > slop) return@withTimeoutOrNull PressOutcome.Cancelled
            val settled = awaitPointerEvent(PointerEventPass.Final)
            if (settled.changes.any { it.isConsumed }) return@withTimeoutOrNull PressOutcome.Cancelled
        }
        @Suppress("UNREACHABLE_CODE")
        PressOutcome.Cancelled
    }
    return outcome ?: PressOutcome.Held(latest)
}

/**
 * Tap, long-press and drag for one strip item. Consumes nothing until the item is lifted, so a
 * horizontal pull scrolls the strip as usual; once lifted every change is consumed in the Main
 * pass (this node sees it before the row's scroll does), so the row stays still while the item moves.
 */
private fun Modifier.stripItemGestures(
    key: String,
    isChip: Boolean,
    controller: DragController,
    state: TabStripState,
    onTap: () -> Unit,
    onPressedChange: (Boolean) -> Unit,
): Modifier = pointerInput(key, controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Main)
        onPressedChange(true)
        val outcome = awaitPressOutcome(down)
        onPressedChange(false)
        when (outcome) {
            PressOutcome.Tap -> onTap()
            PressOutcome.Cancelled -> Unit
            is PressOutcome.Held -> {
                if (!controller.lift(key, outcome.change.position.x, isChip)) return@awaitEachGesture
                var moved = false
                var travelled = 0f
                var dropped = false
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            dropped = true
                            break
                        }
                        val dx = change.positionChange().x
                        change.consume()
                        travelled += dx
                        if (!moved && abs(travelled) > viewConfiguration.touchSlop) {
                            moved = true
                            state.menuKey = null
                        }
                        if (moved) controller.moveBy(dx)
                    }
                } finally {
                    if (dropped) {
                        // Released: settle, then open the menu if the finger never travelled (spec C3, Long-press menu).
                        controller.drop()
                        if (!moved) state.menuKey = key
                    } else {
                        controller.cancel()
                    }
                }
            }
        }
    }
}

// ---- items ---------------------------------------------------------------------------------------------

/** One tab (spec C3, Tab anatomy): swatch with dot and ring, title, × on the active tab. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.TabItem(
    entry: StripEntry.Tab,
    active: Boolean,
    groupCount: Int,
    tabCount: Int,
    style: ResolvedTabStyle,
    state: TabStripState,
    controller: DragController,
    actions: TabActions,
    groups: List<Workspace>,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val s = style.style
    val id = entry.slot.id
    val record by entry.slot.tab.record.collectAsState()
    val host = record.hostSnapshot
    val title = record.displayTitle
    val titled = active || s.inactiveTitles
    val liftedHere = state.lifted == entry.key
    val draggedHere = state.drag?.key == entry.key
    var pressed by remember { mutableStateOf(false) }

    val groupTint = entry.group?.color?.rgb?.toColor()
    val fill by animateColorAsState(
        when {
            liftedHere || pressed -> style.pressedFill
            active && s.activeMark == ActiveTabMark.FILL -> if (groupTint != null && groupCount > 1) lerp(style.activeFill, groupTint, s.groupTintOnActive) else style.activeFill
            else -> s.idleFill ?: Color.Transparent
        },
        tween(120),
        label = "tab fill",
    )
    val titleColor = when {
        active || pressed -> c.text1
        record.state == SessionState.DETACHED || record.state == SessionState.CLOSED -> c.text3
        else -> c.text2
    }
    val scale by animateFloatAsState(if (liftedHere) 1.04f else 1f, tween(120), label = "tab lift")
    val stateText = when (record.state) {
        SessionState.LIVE -> "live"
        SessionState.IDLE, SessionState.CONNECTING -> "connecting"
        SessionState.RECONNECTING -> "reconnecting"
        SessionState.DETACHED -> "detached"
        SessionState.FAILED -> "failed"
        SessionState.CLOSED -> "closed"
    }
    val description = buildString {
        append(title).append(", ").append(stateText)
        append(", tab ").append(entry.stripIndex + 1).append(" of ").append(tabCount)
        entry.group?.let { if (groupCount > 1) append(", group ").append(it.name) }
        if (record.needsAttention) append(", needs attention")
    }
    val stripIndex = entry.stripIndex

    Box(
        modifier
            .then(if (draggedHere) Modifier else Modifier.animateItem())
            .fillMaxHeight()
            .graphicsLayer {
                val drag = state.drag
                translationX = if (drag != null && drag.key == entry.key) {
                    val current = state.itemInfo(entry.key)?.offset ?: drag.startOffset
                    drag.startOffset + state.dragTravel - current
                } else 0f
            }
            .stripItemGestures(entry.key, isChip = false, controller, state, onTap = { actions.activate(id) }, onPressedChange = { pressed = it })
            .clearAndSetSemantics {
                role = Role.Tab
                selected = active
                contentDescription = description
                onClick { actions.activate(id); true }
                onLongClick { state.menuKey = entry.key; true }
                customActions = listOf(
                    CustomAccessibilityAction("Close") { actions.close(id); true },
                    CustomAccessibilityAction("Move left") { actions.move(id, stripIndex - 1, null); true },
                    CustomAccessibilityAction("Move right") { actions.move(id, stripIndex + 1, null); true },
                    CustomAccessibilityAction("More options") { state.menuKey = entry.key; true },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .height(s.tabHeight)
                .then(if (titled) Modifier.widthIn(min = s.tabMinWidth, max = s.tabMaxWidth) else Modifier)
                .clip(RoundedCornerShape(style.tabRadius))
                .background(fill)
                .drawBehind {
                    if (active && s.activeMark == ActiveTabMark.UNDERLINE) {
                        val h = 2.dp.toPx()
                        drawRoundRect(c.accent, Offset(s.tabPadding.toPx(), size.height - h), Size(size.width - s.tabPadding.toPx() * 2, h), CornerRadius(h / 2))
                    }
                }
                .padding(s.tabPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabSwatch(
                color = host.color.rgb.toColor(),
                monogram = host.monogram,
                state = record.state,
                showLiveDot = false,
                attention = record.needsAttention,
                halo = if (fill.alpha > 0.01f) fill else c.surface1,
                style = style,
            )
            if (titled) {
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    style = BerthType.label,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 2.dp),
                )
            }
            if (active && s.closeOnActive) {
                Spacer(Modifier.width(2.dp))
                CloseGlyph(onClick = { actions.close(id) })
            }
        }
        TabMenu(
            expanded = state.menuKey == entry.key,
            record = record,
            groups = groups,
            actions = actions,
            onDismiss = { if (state.menuKey == entry.key) state.menuKey = null },
        )
    }
}

/** The × slot on the active tab: 24 dp target, 16 dp glyph; a click here never also taps the tab. */
@Composable
private fun CloseGlyph(onClick: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "Close tab"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        BerthIcon(BerthIcons.close, tint = if (pressed) c.text1 else c.text2, size = 16.dp)
    }
}

/**
 * The tab's swatch: colour fill with the monogram, radius concentric with the tab; the 6 dp state
 * dot at the bottom-right corner with a halo cut in [halo] so it reads on any swatch colour; the
 * 1.5 dp attention ring 2 dp outside, pulsing once when it lights (spec A7) and holding after.
 */
@Composable
internal fun TabSwatch(
    color: Color,
    monogram: String,
    state: SessionState,
    showLiveDot: Boolean,
    attention: Boolean,
    halo: Color,
    style: ResolvedTabStyle,
    size: Dp = style.style.swatchSize,
    radius: Dp = style.swatchRadius,
) {
    val c = Berth.colors
    val ring = remember { Animatable(0f) }
    LaunchedEffect(attention) {
        if (attention) {
            ring.snapTo(3f)
            ring.animateTo(1.5f, tween(600))
        } else ring.snapTo(0f)
    }
    val spinning = state == SessionState.CONNECTING || state == SessionState.RECONNECTING
    val rotation = if (spinning) {
        val t = rememberInfiniteTransition(label = "tab arc")
        val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "arc")
        r
    } else 0f
    val dotColor = when (state) {
        SessionState.LIVE -> if (showLiveDot) c.live else null
        SessionState.CONNECTING, SessionState.RECONNECTING, SessionState.IDLE -> c.pending
        SessionState.DETACHED, SessionState.CLOSED -> c.detached
        SessionState.FAILED -> c.danger
    }
    Box(
        Modifier
            .size(size)
            .drawBehind {
                val r = radius.toPx()
                val width = ring.value
                if (attention && width > 0f) {
                    val out = 2.dp.toPx() + width.dp.toPx() / 2
                    drawRoundRect(
                        color = c.attention,
                        topLeft = Offset(-out, -out),
                        size = Size(this.size.width + out * 2, this.size.height + out * 2),
                        cornerRadius = CornerRadius(r + out),
                        style = Stroke(width = width.dp.toPx()),
                    )
                }
                drawRoundRect(color, cornerRadius = CornerRadius(r))
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogram.take(2),
            style = BerthType.label.copy(fontSize = 9.sp, lineHeight = 12.sp),
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
        )
        if (dotColor != null) {
            Canvas(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(if (spinning) 12.dp else 6.dp)
                    .padding(0.dp),
            ) {
                val centre = Offset(this.size.width / 2 + 1.5.dp.toPx(), this.size.height / 2 + 1.5.dp.toPx())
                val dot = 3.dp.toPx()
                drawCircle(halo, dot + 1.5.dp.toPx(), centre)
                drawCircle(dotColor, dot, centre)
                if (spinning) {
                    val stroke = 1.5.dp.toPx()
                    val arc = dot * 2 + stroke * 3
                    drawArc(
                        color = dotColor,
                        startAngle = rotation,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = Offset(centre.x - arc / 2, centre.y - arc / 2),
                        size = Size(arc, arc),
                        style = Stroke(width = stroke),
                    )
                }
            }
        }
    }
}

/**
 * A group chip (spec C3, Groups): 22 dp pill tinted with the group colour, its name in Caption;
 * collapsed it reads `HOMELAB · 4` and carries its hidden tabs' rings. Tap collapses or expands,
 * long-press lifts for the menu or a drag that reorders whole groups.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.GroupChip(
    entry: StripEntry.Chip,
    style: ResolvedTabStyle,
    state: TabStripState,
    controller: DragController,
    actions: TabActions,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val group = entry.group
    val tint = group.color.rgb.toColor()
    val draggedHere = state.drag?.key == entry.key
    val liftedHere = state.lifted == entry.key
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (liftedHere) 1.04f else 1f, tween(120), label = "chip lift")
    val attention by rememberGroupAttention(entry.tabs, enabled = group.collapsed).collectAsState(initial = false)
    val label = if (group.collapsed) "${group.name.uppercase()} \u00B7 ${entry.tabs.size}" else group.name.uppercase()

    Box(
        modifier
            .then(if (draggedHere) Modifier else Modifier.animateItem())
            .fillMaxHeight()
            .graphicsLayer {
                val drag = state.drag
                translationX = if (drag != null && drag.key == entry.key) {
                    val current = state.itemInfo(entry.key)?.offset ?: drag.startOffset
                    drag.startOffset + state.dragTravel - current
                } else 0f
            }
            .stripItemGestures(entry.key, isChip = true, controller, state, onTap = { actions.setGroupCollapsed(group.id, !group.collapsed) }, onPressedChange = { pressed = it })
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "Group ${group.name}, ${entry.tabs.size} tabs" + (if (group.collapsed) ", collapsed" else "") + (if (attention) ", needs attention" else "")
                onClick { actions.setGroupCollapsed(group.id, !group.collapsed); true }
                onLongClick { state.menuKey = entry.key; true }
                customActions = listOf(
                    CustomAccessibilityAction("New tab here") { actions.newTabIn(group.id); true },
                    CustomAccessibilityAction("Move left") { actions.moveGroup(group.id, entry.groupIndex - 1); true },
                    CustomAccessibilityAction("Move right") { actions.moveGroup(group.id, entry.groupIndex + 1); true },
                    CustomAccessibilityAction("More options") { state.menuKey = entry.key; true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        ChipPill(
            label = label,
            tint = tint,
            attention = attention,
            pressed = pressed || liftedHere,
            height = style.style.chipHeight,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
        GroupMenu(
            expanded = state.menuKey == entry.key,
            group = group,
            actions = actions,
            onDismiss = { if (state.menuKey == entry.key) state.menuKey = null },
        )
    }
}

/**
 * The pill itself: full radius, the group colour at 20% (32% pressed) over the surface, the label
 * in Caption in the group colour, and the attention ring 2 dp outside. Shared with the switcher.
 */
@Composable
internal fun ChipPill(
    label: String,
    tint: Color,
    attention: Boolean,
    pressed: Boolean,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    Box(
        modifier
            .height(height)
            .drawBehind {
                if (attention) {
                    val out = 2.dp.toPx()
                    drawRoundRect(
                        color = c.attention,
                        topLeft = Offset(-out, -out),
                        size = Size(size.width + out * 2, size.height + out * 2),
                        cornerRadius = CornerRadius(size.height / 2 + out),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
            .clip(CircleShape)
            .background(tint.copy(alpha = if (pressed) 0.32f else 0.2f))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BerthType.caption, color = tint, maxLines = 1)
    }
}

/** Whether any of [tabs] needs attention; only computed while the chip is collapsed and its tabs are hidden. */
@Composable
internal fun rememberGroupAttention(tabs: List<TabSource>, enabled: Boolean): Flow<Boolean> = remember(tabs, enabled) {
    if (!enabled || tabs.isEmpty()) flowOf(false) else combine(tabs.map { it.record }) { records -> records.any { it.needsAttention } }
}

/** The plus tab (spec C3): a 32 dp square with the drawn `+`; tap for the New tab sheet, long-press duplicates the active tab. */
@Composable
private fun PlusTab(style: ResolvedTabStyle, actions: TabActions, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxHeight()
            .width(style.style.tabHeight)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = actions::newTab,
                onLongClick = actions::duplicateActive,
            )
            .clearAndSetSemantics {
                contentDescription = "New tab"
                role = Role.Button
                onLongClick { actions.duplicateActive(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(style.style.tabHeight)
                .clip(RoundedCornerShape(style.tabRadius))
                .background(if (pressed) style.pressedFill else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            BerthIcon(BerthIcons.add, tint = if (pressed) c.text1 else c.text2, size = 20.dp)
        }
    }
}

/**
 * The count tile (spec C3, Switcher): a 24 dp square on `surface.2` with the tab count in Label;
 * a ring when a tab scrolled out of view needs attention. Sits in the header's fixed slots inside
 * a 44 dp target and opens the switcher.
 */
@Composable
fun CountTile(
    count: Int,
    attention: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TabStripStyle = LocalTabStripStyle.current,
) {
    val c = Berth.colors
    val resolved = rememberResolvedTabStyle(style)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(44.dp)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$count tabs, open the tab switcher" + if (attention) ", a tab needs attention" else ""
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(style.countTileSize)
                .drawBehind {
                    if (attention) {
                        val out = 2.dp.toPx()
                        drawRoundRect(
                            color = c.attention,
                            topLeft = Offset(-out, -out),
                            size = Size(size.width + out * 2, size.height + out * 2),
                            cornerRadius = CornerRadius(resolved.countTileRadius.toPx() + out),
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
                .clip(RoundedCornerShape(resolved.countTileRadius))
                .background(if (pressed) c.surface4 else c.surface2),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) ":)" else count.toString(),
                style = BerthType.label.copy(fontSize = if (count > 9) 11.sp else 13.sp),
                color = c.text1,
                maxLines = 1,
            )
        }
    }
}
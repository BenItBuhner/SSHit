package app.berth.android.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.berth.android.session.ManagedTab
import app.berth.android.session.PaneSide
import app.berth.android.session.Panes
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.IconAction
import app.berth.android.ui.components.Swatch
import app.berth.android.ui.keyboard.LocalPaneActions
import app.berth.android.ui.keyboard.PaneActions
import app.berth.android.ui.keyboard.StageFocus
import app.berth.android.ui.keyboard.StageRegion
import app.berth.android.ui.keyboard.rememberStageFocus
import app.berth.android.ui.keyboard.stageRegion
import app.berth.android.ui.tabs.LocalTabCarry
import app.berth.android.ui.tabs.LocalTabStripStyle
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabCarry
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import kotlin.math.abs
import kotlin.math.roundToInt

/** The gap between the panes (spec C23): 12 dp, draggable, no line. */
private val PaneGap = 12.dp

/**
 * How far past the gap the divider answers a finger, each side: the 48 dp target every control has
 * (spec A11). The band lies under the panes' own controls, not over them ([paneDividerDrag]).
 */
private val DividerReach = 18.dp

/** The least a pane can be: enough for a 40-column terminal at the default size, or a Files row. */
private val MinPaneWidth = 240.dp

/** The divider settles here when released within [SnapReach] of one (spec C23, snap points). */
private val SnapFractions = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)
private val SnapReach = 24.dp

/** The divider's grip at rest: there, but not a line; 1.0 while a finger holds it. */
private const val DividerRestAlpha = 0.4f

/**
 * The Stage on a window that fits two panes (spec C23): the tab strip spans the window as it does
 * on a phone, and below it the active tab and its companion sit side by side, each under a header
 * of the strip's height with its swatch and title, a draggable 12 dp gap between them. The focused pane takes the
 * keys and the keyboard follows it; one Deck sits under both panes, the focused terminal's, or the
 * other pane's while the focused one has none to show, so a focus change never resizes a live frame.
 * Focus follows the last touched pane, and a tab from the strip can be dropped on either pane or sent
 * there from its menu. With no companion the active tab fills the width, as it always has, its
 * body's two halves take a tab carried from the strip (which splits the Stage with it on that
 * side), and Overflow offers Split.
 *
 * A layer over [StageScreen], not a second Stage: the strip, the overflow, the shortcuts and each
 * tab's body are the Stage's own, composed through [StageBodies.TabBody]; this only decides where
 * the bodies go and who has the keys. The panes own the window's bottom insets, so a body that
 * pads for the navigation bar itself (Files, Tunnels) pads for nothing inside one.
 *
 * A hardware keyboard reaches the panes the way a finger does (spec A11, C22): Ctrl+Shift+D is
 * Overflow's Split or Unsplit, Ctrl+Shift+O puts the focus in the other pane, and a focus that
 * lands in the unfocused pane by any route, Tab or the D-pad out of a header or a Files row as
 * much as the chord, is a focus change like a touch there: the pane model hears of it, so the
 * header's ×, the Deck under both panes and the canvas the keys go to never disagree. Both panes
 * are the Stage's body region, entered on the focused one.
 */
@Composable
fun PaneStageScreen(
    vm: AppViewModel,
    actions: TabActions,
    onOpenDrawer: (() -> Unit)?,
    onOpenSessionSheet: () -> Unit,
    onEditHost: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDeckEditor: () -> Unit = {},
    onGroups: () -> Unit = {},
) {
    val active by vm.activeTab.collectAsState()
    val panes by vm.panes.collectAsState()
    val dividerFraction by vm.paneDividerFraction.collectAsState()
    val carry = remember { TabCarry() }
    val tools = remember { HashMap<String, StageTools>() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val chrome = remember { HashMap<String, StageChromeHost>() }
    fun toolsFor(id: String) = tools.getOrPut(id) { StageTools() }
    fun focusFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    fun chromeFor(id: String) = chrome.getOrPut(id) { StageChromeHost() }

    // Both panes are in view while this layer is up: the companion is on stage too (its bells stay quiet).
    DisposableEffect(vm) {
        vm.setPanesShown(true)
        onDispose { vm.setPanesShown(false) }
    }
    val shown = setOfNotNull(active?.id, panes?.left?.id, panes?.right?.id)
    SideEffect {
        carry.placement = panes?.let { mapOf(it.left.id to PaneSide.LEFT, it.right.id to PaneSide.RIGHT) } ?: emptyMap()
        carry.activeId = active?.id
        // A tab's tools, focus and chrome live as long as it is in view.
        tools.keys.retainAll(shown)
        requesters.keys.retainAll(shown)
        chrome.keys.retainAll(shown)
    }
    // The keys follow the focused pane (a header tap, a drop, the strip, a pane closing) when a body in
    // this layer holds them: the focused body says so through [tracking], and the word stands over the
    // body's own removal, since a body that moves between the panes and the single slot is composed anew.
    // Nothing here takes the keys from nowhere: as on a phone, a terminal is first focused by a tap,
    // or asked for by the chord ([keysAskedFor]), which is the one route that puts them where none were.
    var keysIn by remember { mutableStateOf<String?>(null) }
    var keysAskedFor by remember { mutableStateOf<String?>(null) }
    fun tracking(id: String) = Modifier.onFocusChanged { if (it.hasFocus) keysIn = id else if (keysIn == id) keysIn = null }
    val focusedId = panes?.focusedTab?.id ?: active?.id
    val focus = rememberStageFocus()
    LaunchedEffect(focusedId, panes != null, keysAskedFor) {
        val id = focusedId ?: return@LaunchedEffect
        // The chord names the tab it wants the keys in, so the frame before the pane model has caught up
        // with it (its flows run a frame behind the call) cannot spend the request on the pane leaving.
        val asked = keysAskedFor == id
        if (asked) keysAskedFor = null
        if (keysIn == null && !asked) return@LaunchedEffect
        // The keys already in the focused pane's body, and held there: a focus that landed in this pane
        // moved the model (below), not the reverse, and it stays on the row it landed on.
        if (keysIn == id && !asked && focus.region == StageRegion.Body) return@LaunchedEffect
        // The body's region is entered on the focused pane: its terminal, or the first row of a Files or
        // Tunnels pane, which the pane's own requester (the terminal's) could not reach.
        if (!focus.focus(StageRegion.Body)) runCatching { focusFor(id).requestFocus() }
    }
    // A focus that lands in the unfocused pane, by Tab or the D-pad out of a header or a row, or by
    // the chord, is a touch on that pane as far as the pane model is concerned: the same route a
    // header tap takes ([PaneLayer]'s onFocus), so the header's ×, the Deck and the keys agree.
    LaunchedEffect(keysIn) {
        val id = keysIn ?: return@LaunchedEffect
        val two = panes ?: return@LaunchedEffect
        if (id != two.focusedTab.id && two.sideOf(id) != null) vm.setActive(id)
    }
    // Ctrl+Shift+D and Ctrl+Shift+O (spec C22), for the Stage's chord dispatcher.
    val onSplit: (() -> Unit)? = if (panes == null && active != null) actions::splitActive else null
    val onUnsplit: (() -> Unit)? = panes?.let { p -> { actions.closePane(p.focused.other) } }
    val paneActions = PaneActions(
        split = onSplit ?: onUnsplit,
        focusOtherPane = panes?.let { p ->
            {
                keysAskedFor = p.otherTab.id
                vm.setActive(p.otherTab.id)
            }
        },
    )

    val idle = remember { StageTools() }
    val activeTools = active?.let { toolsFor(it.id) } ?: idle
    CompositionLocalProvider(LocalTabCarry provides carry, LocalPaneActions provides paneActions) {
        StageScreen(
            vm = vm,
            tab = active,
            actions = actions,
            onOpenDrawer = onOpenDrawer,
            onOpenSessionSheet = onOpenSessionSheet,
            onEditHost = onEditHost,
            modifier = modifier,
            onOpenDeckEditor = onOpenDeckEditor,
            tools = activeTools,
            onSplit = onSplit,
            onUnsplit = onUnsplit,
            onGroups = onGroups,
            focus = focus,
            layer = { tab, body ->
                val two = panes
                if (two == null || two.sideOf(tab.id) == null) {
                    // One tab on the Stage: its body fills the width, the chrome where the body puts it, and
                    // the body's halves take a tab from the strip, which splits the Stage (spec C23).
                    SplitTargets(carry, modifier = body) {
                        TabBody(
                            tab,
                            toolsFor(tab.id),
                            Modifier.fillMaxSize().then(tracking(tab.id)).stageRegion(focus, StageRegion.Body),
                            focusRequester = focusFor(tab.id),
                        )
                    }
                } else {
                    PaneLayer(
                        panes = two,
                        bodies = this,
                        carry = carry,
                        focus = focus,
                        toolsFor = ::toolsFor,
                        focusFor = ::focusFor,
                        chromeFor = ::chromeFor,
                        tracking = ::tracking,
                        onFocus = { side -> vm.setActive(two.on(side).id) },
                        onClosePane = { side -> actions.closePane(side) },
                        restingFraction = dividerFraction,
                        onFractionSettled = vm::setPaneDividerFraction,
                        modifier = body,
                    )
                }
            },
        )
    }
}

/**
 * The two panes under the strip: headers, bodies, the gap between them and one Deck across the
 * bottom. Bodies are composed through [bodies] so a tab's kind picks its body in one place; each
 * terminal hands its Deck to a [StageChromeHost] and the layer lays out the focused pane's, or the
 * other pane's when the focused one has none, under both panes, paying the bottom insets once for
 * everything above it. The panes are the carry's targets while this is up, and not a moment longer.
 *
 * The divider opens at [restingFraction], where it last came to rest, and reports where it settles
 * ([onFractionSettled]: a drag's end, a keyboard step) so the panes come back there after an
 * unsplit, a rotation or a relaunch (spec C23 with C3's Persistence).
 */
@Composable
private fun PaneLayer(
    panes: Panes,
    bodies: StageBodies,
    carry: TabCarry,
    focus: StageFocus,
    toolsFor: (String) -> StageTools,
    focusFor: (String) -> FocusRequester,
    chromeFor: (String) -> StageChromeHost,
    tracking: (String) -> Modifier,
    onFocus: (PaneSide) -> Unit,
    onClosePane: (PaneSide) -> Unit,
    restingFraction: Float,
    onFractionSettled: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var fraction by remember { mutableFloatStateOf(restingFraction) }
    var dragging by remember { mutableStateOf(false) }
    // The stored rest as it arrives (the store reads a moment after the first frame) or moves under this
    // layer; a finger on the divider has the last word while it holds, and its release is what writes.
    LaunchedEffect(restingFraction) { if (!dragging) fraction = restingFraction }
    var rowWidthPx by remember { mutableStateOf(0) }
    var layerOrigin by remember { mutableStateOf(Offset.Zero) }
    val gapPx = with(density) { PaneGap.toPx() }
    val minPx = with(density) { MinPaneWidth.toPx() }
    val snapPx = with(density) { SnapReach.toPx() }

    fun clampFraction(f: Float): Float {
        val usable = rowWidthPx - gapPx
        if (usable <= 2 * minPx) return 0.5f
        return f.coerceIn(minPx / usable, 1f - minPx / usable)
    }
    fun snap(f: Float): Float {
        val usable = rowWidthPx - gapPx
        val nearest = SnapFractions.minByOrNull { abs(it - f) * usable } ?: return f
        return if (abs(nearest - f) * usable <= snapPx) clampFraction(nearest) else f
    }

    // The panes' bounds are this layer's word. When it leaves (a pane closed, the window narrowed) the
    // rectangles they occupied go with it, or a later carry would drop a tab into a pane that is not there.
    DisposableEffect(carry) { onDispose { carry.bounds = emptyMap() } }

    val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    val usable = (rowWidthPx - gapPx).coerceAtLeast(0f)
    val leftPx = (usable * clampFraction(fraction)).roundToInt()
    val leftWidth = with(density) { leftPx.toDp() }
    val bandReachPx = with(density) { (DividerReach + PaneGap / 2).toPx() }
    Column(modifier.onGloballyPositioned { layerOrigin = it.positionInRoot() }) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { rowWidthPx = it.size.width }
                // The panes own the window's bottom insets (paid once below both), so no body pads for them.
                .consumeWindowInsets(bottomInsets)
                // The divider's 48 dp band is the container's gesture, under the panes' own (see
                // [paneDividerDrag]): a control at a pane's inner edge keeps its whole target.
                .paneDividerDrag(
                    centre = leftPx + gapPx / 2,
                    reach = bandReachPx,
                    onDragStart = { dragging = true },
                    onDrag = { dx -> if (usable > 0f) fraction = clampFraction(fraction + dx / usable) },
                    onDragEnd = {
                        dragging = false
                        fraction = snap(fraction)
                        onFractionSettled(fraction)
                    },
                ),
        ) {
            Row(Modifier.fillMaxSize()) {
                Pane(
                    side = PaneSide.LEFT,
                    tab = panes.left,
                    focused = panes.focused == PaneSide.LEFT,
                    dropTarget = carry.target == PaneSide.LEFT,
                    bodies = bodies,
                    focus = focus,
                    tools = toolsFor(panes.left.id),
                    focusRequester = focusFor(panes.left.id),
                    chrome = chromeFor(panes.left.id),
                    tracking = tracking(panes.left.id),
                    onTouched = { onFocus(PaneSide.LEFT) },
                    onClose = { onClosePane(PaneSide.LEFT) },
                    onBounds = { carry.bounds = carry.bounds + (PaneSide.LEFT to it) },
                    modifier = Modifier.width(leftWidth).fillMaxHeight(),
                )
                Spacer(Modifier.width(PaneGap))
                Pane(
                    side = PaneSide.RIGHT,
                    tab = panes.right,
                    focused = panes.focused == PaneSide.RIGHT,
                    dropTarget = carry.target == PaneSide.RIGHT,
                    bodies = bodies,
                    focus = focus,
                    tools = toolsFor(panes.right.id),
                    focusRequester = focusFor(panes.right.id),
                    chrome = chromeFor(panes.right.id),
                    tracking = tracking(panes.right.id),
                    onTouched = { onFocus(PaneSide.RIGHT) },
                    onClose = { onClosePane(PaneSide.RIGHT) },
                    onBounds = { carry.bounds = carry.bounds + (PaneSide.RIGHT to it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Divider(
                dragging = dragging,
                fraction = clampFraction(fraction),
                onStep = { dir ->
                    fraction = clampFraction(fraction + dir * 0.1f)
                    onFractionSettled(fraction)
                },
                modifier = Modifier
                    .offset { IntOffset(leftPx, 0) }
                    .width(PaneGap)
                    .fillMaxHeight(),
            )
            CarryOverlay(carry, layerOrigin)
        }
        // The chrome across the bottom belongs to the terminal that has one: the focused pane's when it
        // has a Deck to show, else the other pane's. So a tap that moves the focus onto a detached frame
        // or a Files tab never takes the Deck from under a live terminal (its frame would grow, the remote
        // would get a window change, and again on the way back). With two live terminals the Deck follows
        // the focus; the Deck's layout and its visibility are the Stage's, so that swap is height-neutral.
        // Two panes with none still leave the insets clear, since the bodies above were told not to.
        val chromeContent = chromeFor(panes.focusedTab.id).content ?: chromeFor(panes.otherTab.id).content
        if (chromeContent != null) chromeContent() else Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(bottomInsets))
    }
}

/**
 * One pane: the header (the tab's swatch and title, the age of a detached frame, and × on the
 * focused pane to close the pane) over the tab's body. A touch in the unfocused pane focuses it,
 * seen on the way down and consumed by nobody, so the body's own gestures are untouched; the
 * focused pane's touches are its own, since a scroll in it has nothing to say to the manager. The
 * [DividerReach] along the pane's inner edge is the divider's band and says nothing on its own.
 * While a tab is carried over it the pane's surface steps up to say it will take the drop. To the
 * keyboard the pane, header and all, is the Stage's body region ([StageRegion.Body]), and the
 * focused pane's body is where a chord into the region lands (spec A11).
 */
@Composable
private fun Pane(
    side: PaneSide,
    tab: ManagedTab,
    focused: Boolean,
    dropTarget: Boolean,
    bodies: StageBodies,
    focus: StageFocus,
    tools: StageTools,
    focusRequester: FocusRequester,
    chrome: StageChromeHost,
    tracking: Modifier,
    onTouched: () -> Unit,
    onClose: () -> Unit,
    onBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val record by tab.record.collectAsState()
    val host = record.hostSnapshot
    val fill by animateColorAsState(if (dropTarget) c.surface2 else Color.Transparent, tween(120), label = "pane drop")
    val sideName = if (side == PaneSide.LEFT) "left" else "right"
    val touched by rememberUpdatedState(onTouched)
    val hasFocus by rememberUpdatedState(focused)
    Column(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(fill)
            .pointerInput(side) {
                val reach = DividerReach.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // A touch in the divider's band is a hand on the divider first ([paneDividerDrag]), not a
                    // word on which pane has the keys; what it lands on there takes them if it takes focus.
                    val inBand = if (side == PaneSide.LEFT) down.position.x > size.width - reach else down.position.x < reach
                    if (!hasFocus && !inBand) touched()
                }
            }
            .semantics {
                contentDescription = "${record.displayTitle}, $sideName pane"
                stateDescription = if (focused) "Focused" else "Not focused"
            }
            .stageRegion(focus, StageRegion.Body, entry = false),
    ) {
        PaneHeader(
            title = record.displayTitle.ifBlank { host.name },
            color = host.color,
            monogram = host.monogram,
            state = record.state,
            attention = record.needsAttention,
            lastLiveAt = record.lastLiveAt,
            focused = focused,
            onClose = onClose,
        )
        bodies.TabBody(
            tab = tab,
            tools = tools,
            modifier = Modifier.weight(1f).fillMaxWidth().then(tracking).stageRegion(focus, StageRegion.Body, entry = focused),
            chrome = chrome,
            focusRequester = focusRequester,
            lendsOverflow = focused,
        )
    }
}

/**
 * The pane's header (spec C23): 20 dp swatch with the state dot, the title in Body, then the age
 * of a detached frame in Caption, and on the focused pane the × that closes the pane (the tab
 * stays in the strip). Transparent: the strip above has the fill, and the header is a label. Its
 * height is the strip's (spec C3), so on a phone on its side it shortens with the strip rather than
 * standing taller than the window's own header and costing each pane rows. The × is a 48 dp
 * target on a 40 dp row, so its box reaches 4 dp over the body's top edge, as the strip's own
 * controls reach over the body on a phone (spec A11); the header stands over the body for it, or
 * a Tunnels or Files row flush under the header would take that band and leave the × 44 dp.
 */
@Composable
private fun PaneHeader(
    title: String,
    color: SwatchColor,
    monogram: String,
    state: SessionState,
    attention: Boolean,
    lastLiveAt: Long?,
    focused: Boolean,
    onClose: () -> Unit,
) {
    val c = Berth.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(LocalTabStripStyle.current.height)
            .zIndex(1f)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Swatch(color, monogram, 20.dp, state = state, attention = attention)
        Text(
            title,
            style = BerthType.bodyMedium,
            color = if (focused) c.text1 else c.text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (state == SessionState.DETACHED && lastLiveAt != null) {
            Text(ageText(lastLiveAt, ageTicker()), style = BerthType.caption, color = c.text3, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (focused) {
            IconAction(onClick = onClose, description = "Close pane") {
                BerthIcon(BerthIcons.close, size = 16.dp)
            }
        }
    }
}

/**
 * The gap between the panes (spec C23): a 4 × 24 pill in `text.3` centred in it, resting at
 * [DividerRestAlpha] so two same-theme terminals still show where the boundary is and that it
 * moves (a grip, not a line), full while a finger holds it. The finger's drag is the container's
 * ([paneDividerDrag]), reaching [DividerReach] over each pane's edge so the 12 dp gap answers a 48 dp
 * target without lying over either pane; this is the gap alone, what a reader lands on between the
 * panes and moves in tenths.
 */
@Composable
private fun Divider(
    dragging: Boolean,
    fraction: Float,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val percent = (fraction * 100).roundToInt()
    Box(
        modifier
            .semantics {
                contentDescription = "Divider between the panes"
                stateDescription = "Left pane $percent percent"
                customActions = listOf(
                    CustomAccessibilityAction("Widen left pane") { onStep(1); true },
                    CustomAccessibilityAction("Widen right pane") { onStep(-1); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .alpha(if (dragging) 1f else DividerRestAlpha)
                .size(4.dp, 24.dp)
                .clip(CircleShape)
                .background(c.text3),
        )
    }
}

/**
 * The one body on a Stage not yet split, as two drop targets (spec C23): a tab carried from the
 * strip and let go over the left or right half splits the Stage with that tab on that side and this
 * one on the other, what `Open in left pane` and `Open in right pane` on the tab's menu do. The half
 * under the finger steps up as a pane does, its ground to `surface.2`: a terminal paints its own
 * ground, so the step is laid over the half in a lighten blend, which lifts the ground and leaves
 * the text as it is. The ghost travels with the finger as it does over two panes. The one tab
 * already on the Stage has no beside-itself, so the carry turns it down (see [TabCarry.accepts]).
 * The halves' bounds are set as this lays out and cleared as it leaves, so a target is never a
 * rectangle nothing occupies any more.
 */
@Composable
private fun SplitTargets(carry: TabCarry, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Berth.colors
    val radius = BerthRadius.row
    var origin by remember { mutableStateOf(Offset.Zero) }
    val target = carry.target
    val left by animateColorAsState(if (target == PaneSide.LEFT) c.surface2 else Color.Transparent, tween(120), label = "left half")
    val right by animateColorAsState(if (target == PaneSide.RIGHT) c.surface2 else Color.Transparent, tween(120), label = "right half")
    DisposableEffect(carry) { onDispose { carry.bounds = emptyMap() } }
    Box(
        modifier
            .onGloballyPositioned {
                val b = it.boundsInRoot()
                origin = b.topLeft
                val middle = b.left + b.width / 2f
                carry.bounds = mapOf(
                    PaneSide.LEFT to Rect(b.left, b.top, middle, b.bottom),
                    PaneSide.RIGHT to Rect(middle, b.top, b.right, b.bottom),
                )
            }
            .drawWithContent {
                drawContent()
                val half = Size(size.width / 2f, size.height)
                val corner = CornerRadius(radius.toPx())
                if (left.alpha > 0f) drawRoundRect(left, Offset.Zero, half, corner, blendMode = BlendMode.Lighten)
                if (right.alpha > 0f) drawRoundRect(right, Offset(half.width, 0f), half, corner, blendMode = BlendMode.Lighten)
            },
    ) {
        content()
        CarryOverlay(carry, origin)
    }
}

/** The carried tab's ghost where the finger is, in the coordinates of the layer whose top-left is at [origin] in the root; nothing while no tab is carried. */
@Composable
private fun CarryOverlay(carry: TabCarry, origin: Offset) {
    val carried = carry.carried ?: return
    CarryGhost(title = carried.title, color = carried.color.rgb.toColor(), monogram = carried.monogram, at = carry.position - origin)
}

/** The carried tab under the finger: its swatch and title on `surface.3` at the row radius, a little above the touch. */
@Composable
private fun CarryGhost(title: String, color: Color, monogram: String, at: Offset) {
    val c = Berth.colors
    val density = LocalDensity.current
    val lift = with(density) { 28.dp.roundToPx() }
    val half = with(density) { 48.dp.roundToPx() }
    Row(
        Modifier
            .offset { IntOffset(at.x.roundToInt() - half, at.y.roundToInt() - lift) }
            .zIndex(2f)
            .semantics { contentDescription = "Carrying $title" }
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(c.surface3)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(BerthRadius.swatchSmall)).background(color), contentAlignment = Alignment.Center) {
            Text(monogram, style = BerthType.caption.copy(fontSize = BerthType.caption.fontSize * 0.8f), color = Color.White, maxLines = 1)
        }
        Text(title, style = BerthType.bodyMedium, color = c.text1, maxLines = 1)
    }
}

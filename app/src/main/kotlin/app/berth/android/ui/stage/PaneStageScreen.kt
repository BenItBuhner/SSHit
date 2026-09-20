package app.berth.android.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import app.berth.android.ui.tabs.LocalTabCarry
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

/** How far past the gap the divider answers a finger, each side (spec A11: targets of 44). */
private val DividerReach = 16.dp

/** The least a pane can be: enough for a 40-column terminal at the default size, or a Files row. */
private val MinPaneWidth = 240.dp

/** The divider settles here when released within [SnapReach] of one (spec C23, snap points). */
private val SnapFractions = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)
private val SnapReach = 24.dp

/** Each pane's header, the strip's height (spec C3: "each pane keeps a 40 dp header"). */
private val PaneHeaderHeight = 40.dp

/**
 * The Stage on a window that fits two panes (spec C23): the tab strip spans the window as it does
 * on a phone, and below it the active tab and its companion sit side by side, each under a 40 dp
 * header with its swatch and title, a draggable 12 dp gap between them. The focused pane takes the
 * keys; the Deck and the keyboard follow it, one Deck under both panes. Focus follows the last
 * touched pane, and a tab from the strip can be dropped on either pane or sent there from its menu.
 * With no companion the active tab fills the width, as it always has, and Overflow offers Split.
 *
 * A layer over [StageScreen], not a second Stage: the strip, the overflow, the shortcuts and each
 * tab's body are the Stage's own, composed through [StageBodies.TabBody]; this only decides where
 * the bodies go and who has the keys. The panes own the window's bottom insets, so a body that
 * pads for the navigation bar itself (Files, Tunnels) pads for nothing inside one.
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
) {
    val active by vm.activeTab.collectAsState()
    val panes by vm.panes.collectAsState()
    val carry = remember { TabCarry() }
    val tools = remember { HashMap<String, StageTools>() }
    val focus = remember { HashMap<String, FocusRequester>() }
    val chrome = remember { HashMap<String, StageChromeHost>() }
    fun toolsFor(id: String) = tools.getOrPut(id) { StageTools() }
    fun focusFor(id: String) = focus.getOrPut(id) { FocusRequester() }
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
        focus.keys.retainAll(shown)
        chrome.keys.retainAll(shown)
    }
    // The keys follow the focused pane (a header tap, a drop, the strip, a pane closing) when a body in
    // this layer holds them: the focused body says so through [tracking], and the word stands over the
    // body's own removal, since a body that moves between the panes and the single slot is composed anew.
    // Nothing here takes the keys from nowhere: as on a phone, a terminal is first focused by a tap.
    var keysIn by remember { mutableStateOf<String?>(null) }
    fun tracking(id: String) = Modifier.onFocusChanged { if (it.hasFocus) keysIn = id else if (keysIn == id) keysIn = null }
    val focusedId = panes?.focusedTab?.id ?: active?.id
    LaunchedEffect(focusedId, panes != null) {
        val id = focusedId ?: return@LaunchedEffect
        if (keysIn == null) return@LaunchedEffect
        runCatching { focusFor(id).requestFocus() }
    }

    val idle = remember { StageTools() }
    val activeTools = active?.let { toolsFor(it.id) } ?: idle
    CompositionLocalProvider(LocalTabCarry provides carry) {
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
            onSplit = if (panes == null && active != null) actions::splitActive else null,
            onUnsplit = panes?.let { p -> { actions.closePane(p.focused.other) } },
            layer = { tab, body ->
                val two = panes
                if (two == null || two.sideOf(tab.id) == null) {
                    // One tab on the Stage: its body fills the width, the chrome where the body puts it.
                    TabBody(tab, toolsFor(tab.id), body.then(tracking(tab.id)), focusRequester = focusFor(tab.id))
                } else {
                    PaneLayer(
                        panes = two,
                        bodies = this,
                        carry = carry,
                        toolsFor = ::toolsFor,
                        focusFor = ::focusFor,
                        chromeFor = ::chromeFor,
                        tracking = ::tracking,
                        onFocus = { side -> vm.setActive(two.on(side).id) },
                        onClosePane = { side -> actions.closePane(side) },
                        modifier = body,
                    )
                }
            },
        )
    }
}

/**
 * The two panes under the strip: headers, bodies, the gap between them and the focused pane's
 * chrome across the bottom. Bodies are composed through [bodies] so a tab's kind picks its body in
 * one place; each terminal hands its Deck to a [StageChromeHost] and the layer lays the focused
 * one out under both panes, paying the bottom insets once for everything above it.
 */
@Composable
private fun PaneLayer(
    panes: Panes,
    bodies: StageBodies,
    carry: TabCarry,
    toolsFor: (String) -> StageTools,
    focusFor: (String) -> FocusRequester,
    chromeFor: (String) -> StageChromeHost,
    tracking: (String) -> Modifier,
    onFocus: (PaneSide) -> Unit,
    onClosePane: (PaneSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var fraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var dragging by remember { mutableStateOf(false) }
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

    val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
    Column(modifier.onGloballyPositioned { layerOrigin = it.positionInRoot() }) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { rowWidthPx = it.size.width }
                // The panes own the window's bottom insets (paid once below both), so no body pads for them.
                .consumeWindowInsets(bottomInsets),
        ) {
            val usable = (rowWidthPx - gapPx).coerceAtLeast(0f)
            val leftPx = (usable * clampFraction(fraction)).roundToInt()
            val leftWidth = with(density) { leftPx.toDp() }
            Row(Modifier.fillMaxSize()) {
                Pane(
                    side = PaneSide.LEFT,
                    tab = panes.left,
                    focused = panes.focused == PaneSide.LEFT,
                    dropTarget = carry.target == PaneSide.LEFT,
                    bodies = bodies,
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
                onDragStart = { dragging = true },
                onDrag = { dx -> if (usable > 0f) fraction = clampFraction(fraction + dx / usable) },
                onDragEnd = {
                    dragging = false
                    fraction = snap(fraction)
                },
                onStep = { dir -> fraction = clampFraction(fraction + dir * 0.1f) },
                modifier = Modifier
                    .offset { IntOffset(leftPx + (gapPx / 2).roundToInt() - (DividerReach + PaneGap / 2).roundToPx(), 0) }
                    .width(PaneGap + DividerReach * 2)
                    .fillMaxHeight()
                    .zIndex(1f),
            )
            carry.carried?.let { carried ->
                CarryGhost(
                    title = carried.title,
                    color = carried.color.rgb.toColor(),
                    monogram = carried.monogram,
                    at = carry.position - layerOrigin,
                )
            }
        }
        // The focused pane's chrome (the Deck over the bottom insets) spans both panes; a pane with none
        // (a Files tab) still leaves the insets clear, since the bodies above were told not to.
        val chromeContent = chromeFor(panes.focusedTab.id).content
        if (chromeContent != null) chromeContent() else Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(bottomInsets))
    }
}

/**
 * One pane: the 40 dp header (the tab's swatch and title, the age of a detached frame, and × on
 * the focused pane to close the pane) over the tab's body. Any touch in the pane focuses it, seen
 * on the way down and consumed by nobody, so the body's own gestures are untouched. While a tab is
 * carried over it the pane's surface steps up to say it will take the drop.
 */
@Composable
private fun Pane(
    side: PaneSide,
    tab: ManagedTab,
    focused: Boolean,
    dropTarget: Boolean,
    bodies: StageBodies,
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
    Column(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(BerthRadius.row))
            .background(fill)
            .pointerInput(onTouched) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    onTouched()
                }
            }
            .semantics {
                contentDescription = "${record.displayTitle}, $sideName pane"
                stateDescription = if (focused) "Focused" else "Not focused"
            },
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
            modifier = Modifier.weight(1f).fillMaxWidth().then(tracking),
            chrome = chrome,
            focusRequester = focusRequester,
            lendsOverflow = focused,
        )
    }
}

/**
 * The pane's header (spec C23): 20 dp swatch with the state dot, the title in Body, then the age
 * of a detached frame in Caption, and on the focused pane the × that closes the pane (the tab
 * stays in the strip). Transparent: the strip above has the fill, and the header is a label.
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
            .height(PaneHeaderHeight)
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
 * The gap between the panes, draggable (spec C23): nothing is drawn until a finger holds it, then
 * a 4 × 24 pill in `text.3` centred in the gap says what is being moved. The touch area reaches
 * [DividerReach] over each pane's edge, so a 12 dp gap answers a 44 dp target. TalkBack moves it
 * in tenths.
 */
@Composable
private fun Divider(
    dragging: Boolean,
    fraction: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Berth.colors
    val percent = (fraction * 100).roundToInt()
    Box(
        modifier
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        onDrag(dx)
                    },
                )
            }
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
                .alpha(if (dragging) 1f else 0f)
                .size(4.dp, 24.dp)
                .clip(CircleShape)
                .background(c.text3),
        )
    }
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

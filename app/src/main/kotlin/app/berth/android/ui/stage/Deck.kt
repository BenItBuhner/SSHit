package app.berth.android.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.berth.android.ui.a11y.alwaysFocusable
import app.berth.android.ui.a11y.keyPressable
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckReach
import app.berth.domain.model.Snippet
import app.berth.domain.model.describe
import app.berth.domain.model.isEmpty
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Layers that can be shown: a layer that is only the snippets slot needs pinned snippets to show. */
fun DeckLayout.usableLayers(hasSnippets: Boolean = false): List<DeckLayer> =
    layers.filter { layer -> hasSnippets || !(layer.keys.size == 1 && layer.keys[0].snippets) }

/**
 * The layer [Deck] shows for [layerIndex]: the index held to the usable layers the way the Deck
 * holds it, so a Deck cut to one layer (the compact Deck under a hardware keyboard) is named for
 * that layer whatever index the whole Deck had saved.
 */
fun DeckLayout.shownLayer(layerIndex: Int, hasSnippets: Boolean = false): DeckLayer? =
    usableLayers(hasSnippets).let { layers -> layers.getOrNull(layerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))) }

/**
 * The tag every Deck control carries (keys, the Nub, the grip, the layer key, an editor slot), and
 * the Deck publishes as the control's resource id, so an accessibility audit can tell a Deck key
 * from any other control. The Deck is a keyboard: seven or more keys share a row, so on a phone
 * a key is 43 dp wide by the spec's 44 dp tall and cannot be the 48 dp square every other control
 * is held to (spec A9 sets the height; the width is the row divided by the keys). Every key is a
 * button to a screen reader, with its alternates as actions, and the audit's touch-target check
 * exempts what carries this tag, the way the framework's own scanner exempts a keyboard's keys.
 */
const val DeckKeyTag: String = "deck-key"

/**
 * Hooks the Deck editor passes so the very same composable becomes the editing surface: a tap
 * selects a slot instead of sending, and a long-press or a horizontal pull lifts a key and drags
 * it past its neighbours. Nothing is added to the strip, so its keys measure exactly as the
 * Stage's do. Slots index the shown layer's keys.
 */
class DeckEditing(
    val selectedSlot: Int?,
    val onSelectSlot: (Int) -> Unit,
    val onMoveKey: (from: Int, to: Int) -> Unit,
)

/**
 * The keyboard accessory bar, rendered from [DeckLayout] rather than a hardcoded row. Each key has
 * tap, swipe-up and hold gestures; modifiers latch one-shot or locked; the Nub sends arrows.
 * [snippets] are the pinned snippets for the session on stage: the snippets slot expands to one
 * key per snippet, and a key bound to a snippet through [DeckAction.Snippet] shows its name.
 * [DeckLayout.reach] mirrors the row for the left thumb, [DeckLayout.arrows] swaps the Nub for
 * four arrow keys or shows both, and [DeckLayout.rows] adds a second row with its own layer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Deck(
    layout: DeckLayout,
    layerIndex: Int,
    onLayerIndexChange: (Int) -> Unit,
    input: StageInput,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    predictiveText: Boolean = false,
    onGripTap: () -> Unit = {},
    onGripSwipeDown: () -> Unit = {},
    onGripLongPress: () -> Unit = {},
    snippets: List<Snippet> = emptyList(),
    onOpenDeckEditor: (() -> Unit)? = null,
    editing: DeckEditing? = null,
    surface: Color = Berth.colors.surface1,
) {
    val c = Berth.colors
    val layers = layout.usableLayers(hasSnippets = snippets.isNotEmpty())
    if (layers.isEmpty()) return
    val index = layerIndex.coerceIn(0, layers.lastIndex)
    val layer = layers[index]
    var strip by remember { mutableStateOf<List<DeckKeyCode>?>(null) }
    val haptics = LocalHapticFeedback.current
    // The setting is the key height (A9: 44, range 40 to 52); each row adds the 4 dp gap above and below.
    val keyHeight = layout.heightDp.coerceIn(40, 52).dp
    val rowHeight = keyHeight + DeckGap * 2
    // The second row keeps its own layer and starts on Nav/Fn when the layout has one (spec C4).
    var secondIndex by rememberSaveable(layers.size) {
        mutableIntStateOf(layers.indexOfFirst { it.name.equals("Nav/Fn", ignoreCase = true) }.takeIf { it >= 0 } ?: 1)
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (layout.reach == DeckReach.LEFT) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .background(surface)
                .alpha(if (enabled) 1f else 0.5f)
                // Publishes [DeckKeyTag] on the keys as their resource id, for the audit's exemption.
                .semantics { testTagsAsResourceId = true },
        ) {
            strip?.let { keys ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = DeckEdge + GripWidth + DeckGap, vertical = DeckGap),
                    horizontalArrangement = Arrangement.spacedBy(DeckGap),
                ) {
                    for (k in keys) {
                        DeckKeyView(
                            key = DeckKey(tap = DeckAction.Key(k)),
                            latch = input.latch,
                            enabled = enabled && editing == null,
                            haptics = haptics,
                            modifier = Modifier.width(48.dp).fillMaxHeight(),
                            onAction = { input.dispatch(it, layer) },
                            onHold = {},
                        )
                    }
                }
            }
            DeckRow(
                layout = layout,
                layer = layer,
                layerCount = layers.size,
                input = input,
                enabled = enabled,
                haptics = haptics,
                height = rowHeight,
                grip = { Grip(accent = predictiveText, onTap = onGripTap, onSwipeDown = onGripSwipeDown, onLongPress = onGripLongPress) },
                onNext = { strip = null; onLayerIndexChange((index + 1) % layers.size) },
                onPrevious = { strip = null; onLayerIndexChange((index - 1 + layers.size) % layers.size) },
                onLayerHold = onOpenDeckEditor,
                onStrip = { held -> strip = if (strip == held) null else held },
                editing = editing,
                snippets = snippets,
            )
            if (layout.rows >= 2 && layers.size > 1) {
                val second = secondIndex.coerceIn(0, layers.lastIndex).let { if (it == index) (it + 1) % layers.size else it }
                DeckRow(
                    layout = layout,
                    layer = layers[second],
                    layerCount = layers.size,
                    input = input,
                    enabled = enabled,
                    haptics = haptics,
                    height = rowHeight,
                    grip = null,
                    onNext = { secondIndex = (second + 1) % layers.size },
                    onPrevious = { secondIndex = (second - 1 + layers.size) % layers.size },
                    onLayerHold = onOpenDeckEditor,
                    onStrip = { held -> strip = if (strip == held) null else held },
                    editing = null,
                    snippets = snippets,
                )
            }
        }
    }
}

/**
 * What a slot holds, as the editor's reader hears it: the key's label, or for the snippets key the
 * pinned snippets it stands for (the keys it expands to send nothing in the editor, so the slot
 * names them), or `empty`.
 */
private fun DeckKey.editorLabel(pinned: List<Snippet>): String = when {
    snippets && pinned.isNotEmpty() -> "Snippets: " + pinned.joinToString { it.name }
    else -> withSnippetName(pinned).label.ifEmpty { "empty" }
}

/** A key bound to a snippet without a display label takes the snippet's name; the editor's hook. */
private fun DeckKey.withSnippetName(snippets: List<Snippet>): DeckKey {
    if (display != null) return this
    val id = (tap as? DeckAction.Snippet)?.snippetId ?: return this
    val name = snippets.firstOrNull { it.id == id }?.name ?: return this
    return copy(display = name.take(12))
}

/**
 * A key's text sized from the key rather than from the system's font size (spec A11 at the
 * interface's 1.3× cap): the key stands 44 dp whatever the font size, so a label in sp outgrows it,
 * and at the cap the alternate's hint at the top right ran into the label under it (`S-Tab` into
 * `Tab`, `^C` into `Ctrl`). Font size and line height are read as dp, the way a terminal's cell text
 * is sized, so the two texts sit where they sit at 1×; what a reader hears is not affected, and the
 * key height setting is where a larger Deck comes from.
 */
@Composable
private fun TextStyle.keySized(): TextStyle = with(LocalDensity.current) {
    copy(
        fontSize = fontSize.value.dp.toSp(),
        lineHeight = if (lineHeight.isSpecified) lineHeight.value.dp.toSp() else lineHeight,
    )
}

/** Gap between Deck keys and between the keys and the strip's edges (A11). */
private val DeckGap = 4.dp

/** Deck strip side padding. */
private val DeckEdge = 8.dp

/** Touch column of the Grip; the 6 × 24 pill is centred in it. */
private val GripWidth = 20.dp

/**
 * One row of the Deck: grip or its spacer, the layer's slots, then the layer key, or on a Deck of
 * one layer (nothing to cycle) the Deck editor key in its place ([DeckEditorKey]).
 */
@Composable
private fun DeckRow(
    layout: DeckLayout,
    layer: DeckLayer,
    layerCount: Int,
    input: StageInput,
    enabled: Boolean,
    haptics: HapticFeedback,
    height: Dp,
    grip: (@Composable () -> Unit)?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onLayerHold: (() -> Unit)?,
    onStrip: (List<DeckKeyCode>) -> Unit,
    editing: DeckEditing?,
    snippets: List<Snippet>,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    val drag = remember { DragState() }
    val editingState = rememberUpdatedState(editing)
    val keyCount = rememberUpdatedState(layer.keys.size)
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = DeckEdge, vertical = DeckGap),
        horizontalArrangement = Arrangement.spacedBy(DeckGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (grip != null) grip() else Spacer(Modifier.width(GripWidth))
        layer.keys.forEachIndexed { slot, key ->
            val weight = if (key.nub) {
                when (layout.arrows) {
                    DeckArrows.NUB -> 1f
                    DeckArrows.FOUR_KEYS -> 3f
                    DeckArrows.BOTH -> 4f
                }
            } else 1f
            val base = Modifier
                .weight(weight)
                .then(if (weight == 1f) Modifier.widthIn(min = 40.dp) else Modifier)
                .fillMaxHeight()
            val selected = editing != null && editing.selectedSlot == slot
            Box(
                if (editing == null) base else base.editableSlot(slot, key.editorLabel(snippets), editingState, keyCount, drag, patterns, mirror),
                contentAlignment = Alignment.Center,
            ) {
                SlotContent(
                    key = key,
                    arrows = layout.arrows,
                    layer = layer,
                    input = input,
                    enabled = enabled && editing == null,
                    haptics = haptics,
                    selected = selected,
                    onStrip = onStrip,
                    snippets = snippets,
                )
                if (editing != null && key.isEmpty) {
                    Text("empty", style = BerthType.caption.keySized(), color = c.text3)
                }
                if (selected) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 4.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(c.accent),
                    )
                }
            }
        }
        if (layerCount > 1) {
            LayerKey(
                enabled = enabled,
                haptics = haptics,
                layerName = layer.name,
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                onNext = onNext,
                onPrevious = onPrevious,
                onHold = onLayerHold,
            )
        } else {
            DeckEditorKey(
                enabled = enabled,
                haptics = haptics,
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                onOpen = onLayerHold,
            )
        }
    }
}

/**
 * What a slot shows: the Nub, four arrow keys or both for the arrow slot; one key per pinned
 * snippet for the snippets slot; otherwise the key itself, named after its snippet when bound to one.
 */
@Composable
private fun SlotContent(
    key: DeckKey,
    arrows: DeckArrows,
    layer: DeckLayer,
    input: StageInput,
    enabled: Boolean,
    haptics: HapticFeedback,
    selected: Boolean,
    onStrip: (List<DeckKeyCode>) -> Unit,
    snippets: List<Snippet>,
) {
    @Composable
    fun arrowKeys(modifier: Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (code in listOf(DeckKeyCode.LEFT, DeckKeyCode.DOWN, DeckKeyCode.UP, DeckKeyCode.RIGHT)) {
                DeckKeyView(
                    key = DeckKey(tap = DeckAction.Key(code)),
                    latch = input.latch,
                    enabled = enabled,
                    haptics = haptics,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    selected = selected,
                    onAction = { input.dispatch(it, layer) },
                    onHold = {},
                )
            }
        }
    }
    when {
        key.nub -> when (arrows) {
            DeckArrows.NUB -> Nub(enabled = enabled, haptics = haptics, modifier = Modifier.fillMaxSize(), selected = selected, onArrow = { input.onKey(it) })
            DeckArrows.FOUR_KEYS -> arrowKeys(Modifier.fillMaxSize())
            DeckArrows.BOTH -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Nub(enabled = enabled, haptics = haptics, modifier = Modifier.weight(1f).fillMaxHeight(), selected = selected, onArrow = { input.onKey(it) })
                arrowKeys(Modifier.weight(3f).fillMaxHeight())
            }
        }
        key.snippets -> if (snippets.isNotEmpty()) {
            Row(
                Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(DeckGap),
            ) {
                for (s in snippets) {
                    DeckKeyView(
                        key = DeckKey(tap = DeckAction.Snippet(s.id), display = s.name.take(16)),
                        latch = input.latch,
                        enabled = enabled,
                        haptics = haptics,
                        modifier = Modifier.widthIn(min = 56.dp).fillMaxHeight(),
                        selected = selected,
                        onAction = { input.dispatch(it, layer) },
                        onHold = {},
                        labelPadding = 12.dp,
                    )
                }
            }
        }
        else -> DeckKeyView(
            key = key.withSnippetName(snippets),
            latch = input.latch,
            enabled = enabled,
            haptics = haptics,
            modifier = Modifier.fillMaxSize(),
            selected = selected,
            onAction = { input.dispatch(it, layer) },
            onHold = { held -> if (held is DeckAction.Strip) onStrip(held.strip) },
        )
    }
}

/** Drag bookkeeping for one Deck row: which slot is lifted and how far it has been pulled. */
private class DragState {
    var slot by mutableStateOf<Int?>(null)
    var dx by mutableFloatStateOf(0f)
}

/**
 * Editing gestures for a slot. A tap selects. A long-press, or a horizontal pull past touch slop,
 * lifts the key; while lifted it follows the finger and swaps places with a neighbour each time it
 * crosses half a key, so the row reorders live under the drag. Vertical movement is left to the
 * page so the editor still scrolls. To a screen reader the slot is one button, `Slot 3` in the
 * state [label] (the key it holds, or `empty`), that selects on activation and moves left or right
 * through its actions; the key inside says nothing of its own here, since it sends nothing.
 */
private fun Modifier.editableSlot(
    slot: Int,
    label: String,
    editing: State<DeckEditing?>,
    keyCount: State<Int>,
    drag: DragState,
    patterns: DeckHaptics,
    mirror: Boolean,
): Modifier = this
    .zIndex(if (drag.slot == slot) 1f else 0f)
    .graphicsLayer {
        if (drag.slot == slot) {
            translationX = drag.dx
            scaleX = 1.06f
            scaleY = 1.06f
        }
    }
    .testTag(DeckKeyTag)
    .clearAndSetSemantics {
        contentDescription = "Slot ${slot + 1}"
        stateDescription = label
        role = Role.Button
        onClick { editing.value?.onSelectSlot(slot); true }
        // "Left" and "right" are the user's: a left-reach row is mirrored, so its index runs the other way.
        val dir = if (mirror) -1 else 1
        customActions = buildList {
            if (slot - dir in 0 until keyCount.value) add(CustomAccessibilityAction("Move left") { editing.value?.onMoveKey(slot, slot - dir); true })
            if (slot + dir in 0 until keyCount.value) add(CustomAccessibilityAction("Move right") { editing.value?.onMoveKey(slot, slot + dir); true })
        }
    }
    .pointerInput(slot, mirror) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val slop = viewConfiguration.touchSlop
            val step = size.width + DeckGap.toPx()
            val dir = if (mirror) -1 else 1
            var lifted = false
            var lastX = down.position.x
            fun lift() {
                lifted = true
                drag.slot = slot
                drag.dx = 0f
                editing.value?.onSelectSlot(slot)
                patterns.hold()
            }
            try {
                while (true) {
                    val event = withTimeoutOrNull(if (lifted) Long.MAX_VALUE else viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                    if (event == null) {
                        if (!lifted) lift()
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!lifted) editing.value?.onSelectSlot(slot)
                        break
                    }
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (!lifted) {
                        if (abs(dx) > slop && abs(dx) > abs(dy)) lift() else if (abs(dy) > slop) break
                    }
                    if (lifted) {
                        change.consume()
                        drag.dx += change.position.x - lastX
                        while (true) {
                            val from = drag.slot ?: break
                            val to = when {
                                drag.dx > step / 2 -> from + dir
                                drag.dx < -step / 2 -> from - dir
                                else -> break
                            }
                            if (to !in 0 until keyCount.value) break
                            editing.value?.onMoveKey(from, to)
                            drag.slot = to
                            drag.dx += if (drag.dx > 0) -step else step
                            patterns.reorderStep()
                        }
                    }
                    lastX = change.position.x
                }
            } finally {
                drag.slot = null
                drag.dx = 0f
            }
        }
    }

/**
 * The Deck's grip (spec C4): tap for the Session sheet, swipe down to hide the keyboard and Deck,
 * hold to jump to the most recent unread tab. A hold that fires swallows the release, and a
 * finger that has moved past the slop is a swipe in the making, never a hold.
 */
@Composable
private fun Grip(accent: Boolean, onTap: () -> Unit, onSwipeDown: () -> Unit, onLongPress: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    // The grip has no fill to step up, so the keyboard's focus colours the mark itself.
    val focused = interaction.showsFocus()
    val color by animateColorAsState(if (accent || focused) c.accent else c.text3, tween(120), label = "grip")
    Box(
        Modifier
            .width(GripWidth)
            .fillMaxHeight()
            .keyPressable(enabled = true, interactionSource = interaction, onPress = onTap)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    var swipedDown = false
                    var moved = false
                    var held = false
                    while (true) {
                        val event = withTimeoutOrNull(if (moved || held) Long.MAX_VALUE else viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                        if (event == null) {
                            held = true
                            onLongPress()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            when {
                                held -> Unit
                                swipedDown -> onSwipeDown()
                                else -> onTap()
                            }
                            break
                        }
                        val dy = change.position.y - down.position.y
                        if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                        if (dy > 24.dp.toPx()) swipedDown = true
                        change.consume()
                    }
                }
            }
            .testTag(DeckKeyTag)
            // A button that opens the session sheet; the swipe and the hold are its actions.
            .semantics {
                contentDescription = "Grip, opens the session sheet"
                role = Role.Button
                onClick { onTap(); true }
                customActions = listOf(
                    CustomAccessibilityAction("Hide the keyboard") { onSwipeDown(); true },
                    CustomAccessibilityAction("Jump to the tab that needs you") { onLongPress(); true },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(6.dp, 24.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * One Deck slot. Tap fires [DeckKey.tap]; a swipe of 24 dp up or down fires [DeckKey.up] or
 * [DeckKey.down] on release; holding fires [DeckKey.hold] or autorepeats repeating keys.
 */
@Composable
fun DeckKeyView(
    key: DeckKey,
    latch: ModifierLatch,
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onAction: (DeckAction) -> Unit,
    onHold: (DeckAction) -> Unit,
    labelPadding: Dp = 0.dp,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    var pressed by remember { mutableStateOf(false) }
    var swipe by remember { mutableStateOf(0) }
    val modifierAction = key.tap as? DeckAction.Modifier
    val latchState = modifierAction?.let { latch.state(it.modifier) } ?: LatchState.NONE
    val latched = latchState != LatchState.NONE
    // The keyboard's focus (spec, Components): one tonal step up and the label in accent, while keys drive.
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.showsFocus()
    val bg by animateColorAsState(
        when {
            latched -> c.accent
            pressed -> c.surface4
            selected || focused -> c.surface3
            else -> c.surface2
        },
        tween(80),
        label = "key",
    )
    val labelColor = when {
        latched -> c.onAccent
        focused -> c.accent
        else -> c.text1
    }
    val secondaryColor = if (latched) c.onAccent.copy(alpha = 0.7f) else c.text3
    val currentKey by rememberUpdatedState(key)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnHold by rememberUpdatedState(onHold)
    // One path for a release and for a screen reader's activation, so both feel the same pattern.
    val fire: (DeckAction) -> Unit = { action ->
        currentOnAction(action)
        // The latch has settled by now, so the pattern can tell one-shot from lock.
        if (action is DeckAction.Modifier) patterns.modifier(latch.state(action.modifier)) else patterns.keyTap()
    }
    // The key's name and latch state; its swipe and hold alternates are actions rather than
    // gesture instructions, since a screen reader's user reaches them from the actions menu.
    val description = when (latchState) {
        LatchState.ONE_SHOT -> "${key.label}, one-shot"
        LatchState.LOCKED -> "${key.label}, locked"
        LatchState.NONE -> key.label
    }

    // Left reach mirrors the strip by flipping its layout direction; the inside of a key is not
    // mirrored, or `^C` would reorder to `C^` under bidi rules and the alternate would jump to the
    // top-left. The weight the Row gave [modifier] still applies: the provider emits no node.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier
                .clip(RoundedCornerShape(BerthRadius.key))
                .background(bg)
                .testTag(DeckKeyTag)
                // One node for the key: its label and alternates inside merge into it, so a reader
                // hears the description once rather than the button and then its text.
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    role = Role.Button
                    if (enabled) {
                        key.tap?.let { tap -> onClick { fire(tap); true } }
                        customActions = buildList {
                            key.up?.let { up -> add(CustomAccessibilityAction(up.describe()) { fire(up); true }) }
                            key.down?.let { down -> add(CustomAccessibilityAction(down.describe()) { fire(down); true }) }
                            key.hold?.let { hold -> add(CustomAccessibilityAction(hold.describe()) { patterns.hold(); currentOnHold(hold); true }) }
                        }
                    } else {
                        disabled()
                    }
                }
                // Enter, Space or the D-pad's centre from a keyboard is the tap; the alternates stay the reader's actions.
                .keyPressable(enabled, interaction) { currentKey.tap?.let(fire) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        swipe = 0
                        var moved = false
                        var holdFired = false
                        val threshold = 24.dp.toPx()
                        val slop = viewConfiguration.touchSlop
                        val k = currentKey
                        val repeating = (k.tap as? DeckAction.Key)?.key?.repeats == true
                        try {
                            while (true) {
                                val timeout = when {
                                    holdFired && repeating -> 55L
                                    !holdFired && !moved && (k.hold != null || repeating) -> 400L
                                    else -> Long.MAX_VALUE
                                }
                                val event = withTimeoutOrNull(timeout) { awaitPointerEvent() }
                                if (event == null) {
                                    if (!holdFired) {
                                        holdFired = true
                                        val hold = k.hold
                                        if (hold != null) {
                                            patterns.hold()
                                            currentOnHold(hold)
                                        } else if (repeating) {
                                            k.tap?.let(currentOnAction)
                                        }
                                    } else if (repeating) {
                                        k.tap?.let(currentOnAction)
                                        patterns.repeatTick()
                                    }
                                    continue
                                }
                                val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!holdFired) {
                                        val action = when (swipe) {
                                            1 -> k.up ?: k.tap
                                            -1 -> k.down ?: k.tap
                                            else -> k.tap
                                        }
                                        if (action != null) fire(action)
                                    }
                                    break
                                }
                                val dy = change.position.y - down.position.y
                                val dx = change.position.x - down.position.x
                                if (abs(dy) > slop || abs(dx) > slop) moved = true
                                swipe = when {
                                    dy < -threshold -> 1
                                    dy > threshold -> -1
                                    else -> 0
                                }
                                change.consume()
                            }
                        } finally {
                            pressed = false
                            swipe = 0
                        }
                    }
                },
        ) {
            val secondary = key.secondaryLabel
            val previewing = swipe == 1 && secondary != null
            val shown = if (previewing) secondary!! else key.label
            Text(
                text = shown,
                style = (if (shown.isSymbolLabel()) BerthType.label.copy(fontFamily = JetBrainsMono) else BerthType.label).keySized(),
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = labelPadding),
            )
            if (secondary != null && !previewing) {
                // A8: text alternates in Caption, symbols in Mono; both at the top-right in text.3.
                Text(
                    text = secondary,
                    style = (if (secondary.isSymbolLabel()) BerthType.caption.copy(fontFamily = JetBrainsMono, letterSpacing = 0.sp) else BerthType.caption.copy(letterSpacing = 0.sp)).keySized(),
                    color = secondaryColor,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 6.dp),
                )
            }
            if (latchState == LatchState.LOCKED) {
                // The lock bar hangs 3 dp under the label's baseline; the label itself does not move.
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = LockBarOffset)
                        .size(16.dp, 2.dp)
                        .clip(CircleShape)
                        .background(c.onAccent),
                )
            }
        }
    }
}

/** Label-centre to lock-bar-centre distance: half the 13 sp label's cap height plus the 3 dp gap plus half the bar. */
private val LockBarOffset = 9.dp

/**
 * Symbols, key chords and function keys (`|`, `\`, `:w`, `^C`, `M-x`, `F1`, `F12`) set in Mono; words
 * (`Esc`, `S-Tab`, `Home`) and two-letter words (`Fn`, `Up`) stay in Plex.
 */
private fun String.isSymbolLabel(): Boolean =
    length == 1 || (length == 2 && !all { it.isLetter() }) || startsWith('^') || startsWith("M-") || none { it.isLetter() } || matches(FunctionKeyLabel)

private val FunctionKeyLabel = Regex("F\\d{1,2}")

/** The circular arrow key: tap sends Up; drag sends the dominant-axis arrow with spatial speed. */
@Composable
fun Nub(
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onArrow: (TerminalKey) -> Unit,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    var active by remember { mutableStateOf<TerminalKey?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val currentOnArrow by rememberUpdatedState(onArrow)
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.showsFocus()
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (pressed) c.surface4 else if (selected || focused) c.surface3 else c.surface2)
                .testTag(DeckKeyTag)
                // Activation is the tap (Up); each arrow is an action, since a screen reader cannot drag it.
                .semantics {
                    contentDescription = "Nub, the arrow keys"
                    role = Role.Button
                    if (enabled) {
                        onClick { patterns.keyTap(); currentOnArrow(TerminalKey.UP); true }
                        customActions = listOf(
                            CustomAccessibilityAction("Up") { patterns.nubStep(); currentOnArrow(TerminalKey.UP); true },
                            CustomAccessibilityAction("Down") { patterns.nubStep(); currentOnArrow(TerminalKey.DOWN); true },
                            CustomAccessibilityAction("Left") { patterns.nubStep(); currentOnArrow(TerminalKey.LEFT); true },
                            CustomAccessibilityAction("Right") { patterns.nubStep(); currentOnArrow(TerminalKey.RIGHT); true },
                        )
                    } else {
                        disabled()
                    }
                }
                // A keyboard's press is the tap, Up; its own arrows walk the Deck rather than drive the Nub.
                .keyPressable(enabled, interaction) { patterns.keyTap(); currentOnArrow(TerminalKey.UP) }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        val dead = 8.dp.toPx()
                        val ring1 = 28.dp.toPx()
                        val ring2 = 56.dp.toPx()
                        var direction: TerminalKey? = null
                        var interval = 180L
                        var everMoved = false
                        try {
                            while (true) {
                                val event = withTimeoutOrNull(if (direction != null) interval else Long.MAX_VALUE) { awaitPointerEvent() }
                                if (event == null) {
                                    direction?.let {
                                        currentOnArrow(it)
                                        patterns.nubStep()
                                    }
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!everMoved) {
                                        patterns.keyTap()
                                        currentOnArrow(TerminalKey.UP)
                                    }
                                    break
                                }
                                val d = change.position - down.position
                                val dist = d.getDistance()
                                val next = if (dist < dead) null else if (abs(d.x) > abs(d.y)) (if (d.x > 0) TerminalKey.RIGHT else TerminalKey.LEFT) else (if (d.y > 0) TerminalKey.DOWN else TerminalKey.UP)
                                interval = when {
                                    dist < ring1 -> 180L
                                    dist < ring2 -> 90L
                                    else -> 45L
                                }
                                if (next != null) everMoved = true
                                if (next != direction) {
                                    direction = next
                                    active = next
                                    if (next != null) {
                                        currentOnArrow(next)
                                        patterns.nubStep()
                                    }
                                }
                                change.consume()
                            }
                        } finally {
                            pressed = false
                            active = null
                        }
                    }
                },
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(c.text2, radius = 3.dp.toPx(), center = center)
            val stroke = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val r = 12.dp.toPx()
            val s = 3.dp.toPx()
            fun chevron(key: TerminalKey, dx: Float, dy: Float) {
                val tip = Offset(center.x + dx * r, center.y + dy * r)
                val color = if (active == key) c.accent else if (active != null) c.text3.copy(alpha = 0.5f) else c.text2
                val path = Path()
                if (dx == 0f) {
                    path.moveTo(tip.x - s, tip.y - dy * s)
                    path.lineTo(tip.x, tip.y)
                    path.lineTo(tip.x + s, tip.y - dy * s)
                } else {
                    path.moveTo(tip.x - dx * s, tip.y - s)
                    path.lineTo(tip.x, tip.y)
                    path.lineTo(tip.x - dx * s, tip.y + s)
                }
                drawPath(path, color, style = stroke)
            }
            chevron(TerminalKey.UP, 0f, -1f)
            chevron(TerminalKey.DOWN, 0f, 1f)
            chevron(TerminalKey.LEFT, -1f, 0f)
            chevron(TerminalKey.RIGHT, 1f, 0f)
        }
    }
}

/**
 * The trailing layer key: tap for the next layer, swipe up for the previous one, hold for the Deck
 * editor. To a reader it is the `Layer` button in the state [layerName], whose activation is
 * `Next layer` and whose actions are the previous layer and the editor.
 */
@Composable
private fun LayerKey(
    enabled: Boolean,
    haptics: HapticFeedback,
    layerName: String,
    modifier: Modifier = Modifier,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onHold: (() -> Unit)? = null,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    var pressed by remember { mutableStateOf(false) }
    // The gesture block only restarts when `enabled` changes, so it reads the latest callbacks
    // rather than the ones captured when the key first composed (those hold that moment's layer index).
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnHold by rememberUpdatedState(onHold)
    val interaction = remember { MutableInteractionSource() }
    val focused = interaction.showsFocus()
    Box(
        modifier
            .clip(RoundedCornerShape(BerthRadius.key))
            .background(if (pressed) c.surface4 else if (focused) c.surface3 else c.surface2)
            .testTag(DeckKeyTag)
            .semantics(mergeDescendants = true) {
                contentDescription = "Layer"
                stateDescription = layerName
                role = Role.Button
                if (enabled) {
                    onClick(label = "Next layer") { patterns.keyTap(); currentOnNext(); true }
                    customActions = buildList {
                        add(CustomAccessibilityAction("Previous layer") { patterns.keyTap(); currentOnPrevious(); true })
                        if (onHold != null) add(CustomAccessibilityAction("Deck editor") { patterns.hold(); currentOnHold?.invoke(); true })
                    }
                } else {
                    disabled()
                }
            }
            .keyPressable(enabled, interaction) { patterns.keyTap(); currentOnNext() }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var up = false
                    var held = false
                    try {
                        while (true) {
                            val hold = currentOnHold
                            val event = withTimeoutOrNull(if (hold != null && !held && !up) viewConfiguration.longPressTimeoutMillis else Long.MAX_VALUE) { awaitPointerEvent() }
                            if (event == null) {
                                held = true
                                patterns.hold()
                                hold?.invoke()
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!held) {
                                    patterns.keyTap()
                                    if (up) currentOnPrevious() else currentOnNext()
                                }
                                break
                            }
                            up = down.position.y - change.position.y > 24.dp.toPx()
                            change.consume()
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BerthIcon(BerthIcons.moreHoriz, tint = if (focused) c.accent else c.text2)
    }
}

/**
 * The collapsed Deck (C4 "hardware keyboard attached", also the hidden-Deck state in either
 * orientation): layer name plus latched modifiers on a 20 dp strip; tap to expand.
 */
@Composable
fun DeckStrip(layerName: String, latch: ModifierLatch, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val mods = buildList {
        if (latch.ctrl != LatchState.NONE) add("Ctrl")
        if (latch.alt != LatchState.NONE) add("Alt")
        if (latch.shift != LatchState.NONE) add("Shift")
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Row(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(if (pressed || focused) c.surface2 else c.surface1)
            // Where Ctrl+Shift+K lands while the Deck is hidden, and where a Deck hidden under the
            // keyboard's focus hands it: taken in touch mode too, or the chord would find nothing here.
            .alwaysFocusable()
            // A real click, so a touch that merely starts here (or a swipe passing through) does not
            // open the Deck, and the announced button can be activated.
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onExpand)
            .semantics { contentDescription = "Deck collapsed, tap to show it" }
            .padding(horizontal = DeckEdge + GripWidth + DeckGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sized from the 20 dp strip like a key's text from its key, so the line holds at the interface's font cap.
        Text((listOf(layerName) + mods).joinToString(" \u00B7 "), style = BerthType.caption.keySized(), color = if (focused) c.accent else c.text3)
        Spacer(Modifier.weight(1f))
    }
}

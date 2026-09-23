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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import app.berth.android.ui.a11y.alwaysFocusable
import app.berth.android.ui.a11y.keyPressable
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.components.BerthMenu
import app.berth.android.ui.components.BerthMenuItem
import app.berth.android.ui.components.BerthMenuToggle
import app.berth.android.ui.components.BerthPopup
import app.berth.android.ui.components.CoachMark
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.DensityTokens
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.android.ui.theme.MonoFontFeatures
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckReach
import app.berth.domain.model.DeckSettings
import app.berth.domain.model.DeckKey.Companion.alternateLabel
import app.berth.domain.model.Snippet
import app.berth.domain.model.describe
import app.berth.domain.model.isEmpty
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * the gestures of spec D2, as [DeckKeyGesture] reads them and [settings] allow them: tap, swipe up,
 * swipe down for the tertiary, hold for the hold action, the autorepeat or the alternates popover,
 * and a swipe across the keys for the layer; modifiers latch one-shot or locked; the Nub sends
 * arrows. [snippets] are the pinned snippets for the session on stage: the snippets slot expands
 * to one key per snippet, and a key bound to a snippet through [DeckAction.Snippet] shows its name.
 * [DeckLayout.reach] mirrors the row for the left thumb, [DeckLayout.arrows] swaps the Nub for
 * four arrow keys or shows both, and [DeckLayout.rows] adds a second row with its own layer.
 * [onPredictiveTextChange], where the Stage gives one, puts the tab's [predictiveText] in the
 * layer picker; [echoOff] puts the grip's dot up while the shell is not echoing (spec C2).
 * [nubCoachMark], where the Stage gives one, hangs the Nub's one-time coach mark over the first
 * row's Nub while the Deck is in reach and not being edited, and is what its dismissal calls.
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
    onPredictiveTextChange: ((Boolean) -> Unit)? = null,
    echoOff: Boolean = false,
    nubCoachMark: (() -> Unit)? = null,
    settings: DeckSettings = DeckSettings(),
    onGripTap: () -> Unit = {},
    onGripSwipeDown: () -> Unit = {},
    onGripDragUp: () -> Unit = onGripTap,
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
    // Each row adds the 4 dp gap above and below the key.
    val keyHeight = deckKeyHeight(layout.heightDp, Berth.density)
    val rowHeight = keyHeight + DeckGap * 2
    // The second row keeps its own layer and starts on Nav/Fn when the layout has one (spec C4).
    var secondIndex by rememberSaveable(layers.size) {
        mutableIntStateOf(layers.indexOfFirst { it.name.equals("Nav/Fn", ignoreCase = true) }.takeIf { it >= 0 } ?: 1)
    }
    // The picker reads in the interface's direction; left reach mirrors the row, not the words.
    val direction = LocalLayoutDirection.current
    fun pickerFor(shown: Int, taken: Int?, onSelect: (Int) -> Unit) =
        LayerPicker(layers, shown, taken, onSelect, predictiveText, onPredictiveTextChange, direction)
    val nubMark: (@Composable () -> Unit)? = nubCoachMark?.takeIf { enabled && editing == null }?.let { dismiss ->
        { CompositionLocalProvider(LocalLayoutDirection provides direction) { CoachMark(NubCoachMarkText, onDismiss = dismiss) } }
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
                settings = settings,
                grip = { Grip(accent = predictiveText, echoOff = echoOff, onTap = onGripTap, onSwipeDown = onGripSwipeDown, onDragUp = onGripDragUp, onLongPress = onGripLongPress) },
                // A layer step leaves the F-strip where it is, in either row: the strip belongs to the
                // key whose hold raised it and that hold closes it, and a strip that fell with the
                // step would drop a row of Deck under the finger and reflow the terminal (#20 review).
                onNext = { onLayerIndexChange((index + 1) % layers.size) },
                onPrevious = { onLayerIndexChange((index - 1 + layers.size) % layers.size) },
                picker = pickerFor(index, taken = null, onLayerIndexChange),
                onLayerHold = onOpenDeckEditor,
                onStrip = { held -> strip = if (strip == held) null else held },
                editing = editing,
                snippets = snippets,
                nubMark = nubMark,
            )
            if (layout.rows >= 2 && layers.size > 1) {
                val second = secondIndex.coerceIn(0, layers.lastIndex).let { if (it == index) (it + 1) % layers.size else it }
                // The second row's layer is its own (spec C4: both rows swipe layers independently).
                DeckRow(
                    layout = layout,
                    layer = layers[second],
                    layerCount = layers.size,
                    input = input,
                    enabled = enabled,
                    haptics = haptics,
                    height = rowHeight,
                    settings = settings,
                    grip = null,
                    onNext = { secondIndex = (second + 1) % layers.size },
                    onPrevious = { secondIndex = (second - 1 + layers.size) % layers.size },
                    // The first row's layer is not offered here: this row would step past it the moment it took it.
                    picker = pickerFor(second, taken = index) { secondIndex = it },
                    onLayerHold = onOpenDeckEditor,
                    onStrip = { held -> strip = if (strip == held) null else held },
                    editing = null,
                    snippets = snippets,
                    nubMark = null,
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
 * interface's 1.3× cap): the key stands as tall as it is set (44 dp, 40 under Compact) whatever the
 * font size, so a label in sp outgrows it,
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

/**
 * A key label's style in the mono face, the font's ligatures and contextual alternates off
 * ([MonoFontFeatures]) the way every mono span of the interface has them: a hand-set `->`, `|=`
 * or `--` on a key draws as the characters it sends, not the arrow or the long dash the font
 * would fuse them into.
 */
private fun TextStyle.inMono(): TextStyle = copy(fontFamily = JetBrainsMono, fontFeatureSettings = MonoFontFeatures)

/** The key heights the setting offers (spec A9: 44, from 40 to 52). */
private const val MIN_KEY_DP = 40
private const val MAX_KEY_DP = 52

/**
 * A Deck key's height: the setting (spec A9) less the step [density] takes off the Deck (A12: 44
 * to 40 under Compact), and never under the setting's own least, so Compact brings each height one
 * step down and leaves 40 where it is.
 */
fun deckKeyHeight(setting: Int, density: DensityTokens): Dp {
    val step = DensityTokens.Comfortable.deckKey - density.deckKey
    return (setting.coerceIn(MIN_KEY_DP, MAX_KEY_DP).dp - step).coerceAtLeast(MIN_KEY_DP.dp)
}

/** What [density] makes of the height [setting], to say beside the setting (`40 dp under Compact`); null where the keys stand as set. */
fun deckKeyHeightNote(setting: Int, density: DensityTokens): String? {
    val height = deckKeyHeight(setting, density)
    return if (height < setting.coerceIn(MIN_KEY_DP, MAX_KEY_DP).dp) "${height.value.roundToInt()} dp under Compact" else null
}

/** Gap between Deck keys and between the keys and the strip's edges (A11). */
private val DeckGap = 4.dp

/** Deck strip side padding. */
private val DeckEdge = 8.dp

/** Touch column of the Grip; the 6 × 24 pill is centred in it. */
private val GripWidth = 20.dp

/**
 * The most a key grows to on a wide row (#15 review, nit 8): on a phone seven keys divide the row
 * and each is 43 dp; on a tablet the same seven would be 170 and the five of the compact Deck
 * 290, `Ctrl` wider than the word `Paste` is tall. Past this the run stops growing and sits
 * centred between the grip and the layer key, the way a keyboard's keys keep their size on a
 * wider keyboard. The snippets slot is the exception: its chips scroll, and it takes the row.
 */
private val KeyMaxWidth = 120.dp

/**
 * One row of the Deck: grip or its spacer, the layer's slots, then the layer key, or on a Deck of
 * one layer (nothing to cycle) the Deck editor key in its place ([DeckEditorKey]). A swipe across
 * a key steps this row's layer (spec D2), the way its layer key's tap and swipe do.
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
    settings: DeckSettings,
    grip: (@Composable () -> Unit)?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    picker: LayerPicker,
    onLayerHold: (() -> Unit)?,
    onStrip: (List<DeckKeyCode>) -> Unit,
    editing: DeckEditing?,
    snippets: List<Snippet>,
    nubMark: (@Composable () -> Unit)?,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    val drag = remember { DragState() }
    val editingState = rememberUpdatedState(editing)
    val keyCount = rememberUpdatedState(layer.keys.size)
    val mirror = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Right is the next layer and left the one before (spec D2), in the hand: a pointer's axis is not
    // mirrored with a left-reach row, and the layers have no side of their own.
    val onLayerStep: ((Int) -> Unit)? = if (settings.layerSwipe && layerCount > 1) { dir -> if (dir > 0) onNext() else onPrevious() } else null
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = DeckEdge, vertical = DeckGap),
        horizontalArrangement = Arrangement.spacedBy(DeckGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (grip != null) grip() else Spacer(Modifier.width(GripWidth))
        // The keys share what is left between the grip and the layer key; capped, the run is centred in it.
        Row(
            Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(DeckGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            layer.keys.forEachIndexed { slot, key ->
                val weight = if (key.nub) {
                    when (layout.arrows) {
                        DeckArrows.NUB -> 1f
                        DeckArrows.FOUR_KEYS -> 3f
                        DeckArrows.BOTH -> 4f
                    }
                } else 1f
                val base = if (key.snippets) {
                    Modifier.weight(weight).fillMaxHeight()
                } else {
                    Modifier
                        .weight(weight, fill = false)
                        .widthIn(min = if (weight == 1f) 40.dp else 0.dp, max = KeyMaxWidth * weight + DeckGap * (weight - 1))
                        .fillMaxWidth()
                        .fillMaxHeight()
                }
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
                        settings = settings,
                        onLayerStep = onLayerStep,
                        onStrip = onStrip,
                        snippets = snippets,
                        nubMark = nubMark,
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
        }
        if (layerCount > 1) {
            var picking by remember { mutableStateOf(false) }
            // The picker hangs from the key: the menu is placed from the box that holds it.
            Box(Modifier.width(40.dp).fillMaxHeight()) {
                LayerKey(
                    enabled = enabled,
                    haptics = haptics,
                    layerName = layer.name,
                    modifier = Modifier.fillMaxSize(),
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onPick = { picking = true },
                    onHold = onLayerHold,
                )
                LayerPickerMenu(picker, expanded = picking && enabled, onDismiss = { picking = false })
            }
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
    settings: DeckSettings,
    onLayerStep: ((Int) -> Unit)?,
    onStrip: (List<DeckKeyCode>) -> Unit,
    snippets: List<Snippet>,
    nubMark: (@Composable () -> Unit)?,
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
                    onLayerStep = onLayerStep,
                )
            }
        }
    }
    when {
        key.nub -> when (arrows) {
            DeckArrows.NUB -> Nub(enabled = enabled, haptics = haptics, modifier = Modifier.fillMaxSize(), selected = selected, mark = nubMark, onArrow = { input.onKey(it) })
            DeckArrows.FOUR_KEYS -> arrowKeys(Modifier.fillMaxSize())
            DeckArrows.BOTH -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Nub(enabled = enabled, haptics = haptics, modifier = Modifier.weight(1f).fillMaxHeight(), selected = selected, mark = nubMark, onArrow = { input.onKey(it) })
                arrowKeys(Modifier.weight(3f).fillMaxHeight())
            }
        }
        // The snippet chips scroll sideways, so a swipe across them is the scroll's and not the layer's.
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
            swipeDown = settings.swipeDown,
            onLayerStep = onLayerStep,
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
 * The Deck's grip (spec C4): tap or drag up for the Session sheet (the drag up is C4's gesture; the
 * sheet opens at its content height either way, so [onDragUp] is [onTap] unless a caller has a
 * taller form to offer), swipe down to hide the keyboard and Deck, hold to jump to the most recent
 * unread tab. The vertical gestures
 * are decided at release, from where the finger is then, so a finger can come back to a tap; a hold
 * that fires swallows the release, and a finger that has moved past the slop is a swipe in the
 * making, never a hold. The pill is `accent` while the tab has predictive text on ([accent]), and
 * a 4 dp dot stands over it while the shell is not echoing ([echoOff], spec C2: a password prompt,
 * whose typing the command history leaves out); a reader hears both as the grip's state.
 */
@Composable
private fun Grip(accent: Boolean, echoOff: Boolean, onTap: () -> Unit, onSwipeDown: () -> Unit, onDragUp: () -> Unit, onLongPress: () -> Unit) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    // The grip has no fill to step up, so the keyboard's focus colours the mark itself.
    val focused = interaction.showsFocus()
    val color by animateColorAsState(if (accent || focused) c.accent else c.text3, tween(120), label = "grip")
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentOnDragUp by rememberUpdatedState(onDragUp)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    Box(
        Modifier
            .width(GripWidth)
            .fillMaxHeight()
            .keyPressable(enabled = true, interactionSource = interaction, onPress = onTap)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    val threshold = SwipeThreshold.toPx()
                    // Where the finger stands: 1 dragged up past the threshold, -1 swiped down past it, 0 neither.
                    var vertical = 0
                    var moved = false
                    var held = false
                    while (true) {
                        val event = withTimeoutOrNull(if (moved || held) Long.MAX_VALUE else viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                        if (event == null) {
                            held = true
                            currentOnLongPress()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            when {
                                held -> Unit
                                vertical < 0 -> currentOnSwipeDown()
                                vertical > 0 -> currentOnDragUp()
                                else -> currentOnTap()
                            }
                            break
                        }
                        val dy = change.position.y - down.position.y
                        if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                        vertical = when {
                            dy > threshold -> -1
                            dy < -threshold -> 1
                            else -> 0
                        }
                        change.consume()
                    }
                }
            }
            .testTag(DeckKeyTag)
            // A button that opens the session sheet; the swipe and the hold are its actions.
            .semantics {
                contentDescription = "Grip, opens the session sheet"
                gripState(accent, echoOff)?.let { stateDescription = it }
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
        if (echoOff) {
            Box(
                Modifier
                    // Centred over the pill's top: half the pill, a 2 dp gap, half the dot.
                    .offset(y = -(24 / 2 + 2 + 4 / 2).dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(c.text2),
            )
        }
    }
}

/** What the grip's marks say to a reader: the pill's predictive text and the dot's echo, or nothing when neither is up. */
internal fun gripState(predictiveText: Boolean, echoOff: Boolean): String? =
    listOfNotNull("predictive text on".takeIf { predictiveText }, "echo off, history paused".takeIf { echoOff })
        .joinToString()
        .ifEmpty { null }
        ?.replaceFirstChar { it.uppercaseChar() }

/** A swipe on a key or the grip is 24 dp of travel (spec C4, D2); the way across a key for the layer is twice that. */
private val SwipeThreshold = 24.dp

/**
 * One Deck slot, on [DeckKeyGesture] (spec D2). Tap fires [DeckKey.tap]; a swipe of [SwipeThreshold]
 * up fires [DeckKey.up] on release and, with [swipeDown] on, one down fires [DeckKey.down]; holding
 * fires [DeckKey.hold], autorepeats a repeating key on the Nub's cadence, or raises the key's
 * alternates as a popover to slide onto; a swipe across the key steps the layer through
 * [onLayerStep] when the row offers it. What the finger is doing shows on the key: the label
 * becomes the alternate a swipe would send, and the popover's chip under the finger fills accent.
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
    swipeDown: Boolean = false,
    onLayerStep: ((Int) -> Unit)? = null,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    var pressed by remember { mutableStateOf(false) }
    // What the touch in progress shows, mirrored out of the machine after every event it takes.
    var swipe by remember { mutableIntStateOf(0) }
    var popover by remember { mutableStateOf<List<DeckAction>?>(null) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    // The machine of the touch in progress, for the popover to tell where its chips are.
    val touch = remember { TouchInProgress() }
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
    val currentOnLayerStep by rememberUpdatedState(onLayerStep)
    // One path for a release and for a screen reader's activation, so both feel the same pattern.
    val fire: (DeckAction) -> Unit = { action ->
        currentOnAction(action)
        // The latch has settled by now, so the pattern can tell one-shot from lock.
        if (action is DeckAction.Modifier) patterns.modifier(latch.state(action.modifier)) else patterns.keyTap()
    }
    // What the machine decided, done: sent, held, repeated, raised or stepped, each with its pattern.
    val apply: (DeckKeyGesture.Effect?) -> Unit = { effect ->
        when (effect) {
            null -> Unit
            is DeckKeyGesture.Effect.Fire -> fire(effect.action)
            is DeckKeyGesture.Effect.Hold -> {
                patterns.hold()
                currentOnHold(effect.action)
            }
            is DeckKeyGesture.Effect.Repeat -> {
                currentOnAction(effect.action)
                patterns.repeatTick()
            }
            // The popover rising is the hold resolving, and feels like one.
            is DeckKeyGesture.Effect.Alternates -> patterns.hold()
            is DeckKeyGesture.Effect.LayerStep -> {
                patterns.keyTap()
                currentOnLayerStep?.invoke(effect.direction)
            }
        }
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
                .pointerInput(enabled, swipeDown, onLayerStep != null) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val k = currentKey
                        val machine = DeckKeyGesture(
                            key = k,
                            repeating = (k.tap as? DeckAction.Key)?.key?.repeats == true,
                            swipeDown = swipeDown,
                            layerSwipe = onLayerStep != null,
                            threshold = SwipeThreshold.toPx(),
                            slop = viewConfiguration.touchSlop,
                            layerThreshold = SwipeThreshold.toPx() * 2,
                        )
                        touch.machine = machine
                        pressed = true
                        // The machine's clock is the events' uptime; a wait that ran out moves it by the wait.
                        var now = down.uptimeMillis
                        machine.down(down.position.x, down.position.y, now)
                        fun show() {
                            swipe = machine.swipe
                            popover = machine.popover
                            hovered = machine.hovered
                        }
                        try {
                            while (!machine.done) {
                                val wait = machine.waitMs(now)
                                val event = if (wait == null) awaitPointerEvent() else withTimeoutOrNull(wait) { awaitPointerEvent() }
                                if (event == null) {
                                    now += wait!!
                                    apply(machine.timeout())
                                    show()
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                now = change.uptimeMillis
                                if (!change.pressed) {
                                    apply(machine.up())
                                    break
                                }
                                apply(machine.move(change.position.x, change.position.y))
                                show()
                                change.consume()
                            }
                        } finally {
                            touch.machine = null
                            pressed = false
                            swipe = 0
                            popover = null
                            hovered = null
                        }
                    }
                },
        ) {
            val secondary = key.secondaryLabel
            val tertiary = key.tertiaryLabel?.takeIf { swipeDown }
            val previewUp = swipe == 1 && secondary != null
            val previewDown = swipe == -1 && tertiary != null
            // The label is what a release would send: the alternate while the swipe stands, else the primary.
            val shown = when {
                previewUp -> secondary!!
                previewDown -> tertiary!!
                else -> key.label
            }
            Text(
                text = shown,
                style = (if (shown.isSymbolLabel()) BerthType.label.inMono() else BerthType.label).keySized(),
                color = labelColor,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = labelPadding),
            )
            if (secondary != null && !previewUp) {
                // A8: text alternates in Caption, symbols in Mono; the swipe-up's at the top right in text.3.
                AlternateHint(secondary, secondaryColor, Modifier.align(Alignment.TopEnd).hintInset(top = true).padding(end = 6.dp))
            }
            if (tertiary != null && !previewDown) {
                // The swipe-down's at the bottom right (spec D2), the way the swipe-up's sits at the top.
                AlternateHint(tertiary, secondaryColor, Modifier.align(Alignment.BottomEnd).hintInset(top = false).padding(end = 6.dp))
            }
            popover?.let { chips -> AlternatesPopover(chips, hovered, onPlaced = { left, width -> touch.machine?.chipsAt(left, width) }) }
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

/** An alternate's inset from the key's top or bottom edge on a key [HintHeight] tall or taller. */
private val HintInset = 3.dp
private const val HintHeight = 44

/**
 * An alternate's inset from its edge of the key, the top ([top]) or the bottom: [HintInset] on a
 * key [HintHeight] dp tall or taller, and on a shorter one less by as much as the centred label
 * comes nearer that edge, so the hint keeps the distance from the label it has at 44. On a 40 dp
 * key (Compact's, and the setting's least) that is 1 dp in; at 3 the `S-Tab` hint's baseline met
 * the `Tab` label's ink (the design audit's S10). Read from the key's own height, whole dp.
 */
private fun Modifier.hintInset(top: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val keyDp = if (constraints.hasBoundedHeight) (constraints.maxHeight / density).roundToInt() else HintHeight
    val inset = (HintInset - ((HintHeight - keyDp).coerceAtLeast(0) / 2f).dp).coerceAtLeast(0.dp).roundToPx()
    layout(placeable.width, placeable.height + inset) { placeable.place(0, if (top) inset else 0) }
}

/** The [DeckKeyGesture] of the touch on a key while there is one; what the popover reports its chips to. */
private class TouchInProgress {
    var machine: DeckKeyGesture? = null
}

/** An alternate's glyph in a key's corner (spec C4, A8): Caption, symbols in Mono, text.3. */
@Composable
private fun AlternateHint(text: String, color: Color, modifier: Modifier) {
    Text(
        text = text,
        style = (if (text.isSymbolLabel()) BerthType.caption.inMono().copy(letterSpacing = 0.sp) else BerthType.caption.copy(letterSpacing = 0.sp)).keySized(),
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/** A popover chip is a key's height by a snippet chip's width; the row of them sits this far above the key. */
private val ChipWidth = 56.dp
private val ChipHeight = 44.dp
private val PopoverRise = 8.dp

/**
 * A key's alternates, risen on a hold (spec D2): one chip per alternate in a row over the key, on
 * the surface two tonal steps above the Deck the way a menu is, each in the key's own radius, the
 * one under the finger filled accent. No panel around them: a row of keys lifted off the Deck,
 * not a box of boxes. A window of the app's ([BerthPopup]) that takes no focus, since the finger
 * holding the key owns it and puts it away. Centred on the key and kept inside the window; where
 * the row lands is reported through [onPlaced] in the key's coordinates, the first chip's left
 * edge and the width of each chip's span (its width and the gap), so the machine can tell which
 * chip a finger is over.
 */
@Composable
private fun AlternatesPopover(chips: List<DeckAction>, hovered: Int?, onPlaced: (left: Float, width: Float) -> Unit) {
    val c = Berth.colors
    val density = LocalDensity.current
    val currentOnPlaced by rememberUpdatedState(onPlaced)
    val provider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = anchorBounds.top - popupContentSize.height - with(density) { PopoverRise.roundToPx() }
                val gap = with(density) { DeckGap.toPx() }
                val chip = with(density) { ChipWidth.toPx() }
                // Each chip's span is its width and the gap, beginning half a gap before its left edge.
                currentOnPlaced((x - anchorBounds.left) - gap / 2, chip + gap)
                return IntOffset(x, y)
            }
        }
    }
    BerthPopup(provider, PopupProperties(focusable = false)) {
        Row(horizontalArrangement = Arrangement.spacedBy(DeckGap)) {
            chips.forEachIndexed { i, action ->
                val label = action.alternateLabel()
                val over = hovered == i
                Box(
                    Modifier
                        .size(ChipWidth, ChipHeight)
                        .clip(RoundedCornerShape(BerthRadius.key))
                        .background(if (over) c.accent else c.surface3)
                        .semantics { contentDescription = action.describe() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = (if (label.isSymbolLabel()) BerthType.label.inMono() else BerthType.label).keySized(),
                        color = if (over) c.onAccent else c.text1,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Symbols, key chords and function keys (`|`, `\`, `:w`, `^C`, `M-x`, `F1`, `F12`) set in Mono; words
 * (`Esc`, `S-Tab`, `Home`) and two-letter words (`Fn`, `Up`) stay in Plex.
 */
private fun String.isSymbolLabel(): Boolean =
    length == 1 || (length == 2 && !all { it.isLetter() }) || startsWith('^') || startsWith("M-") || none { it.isLetter() } || matches(FunctionKeyLabel)

private val FunctionKeyLabel = Regex("F\\d{1,2}")

/**
 * The circular arrow key: tap sends Up; drag sends the dominant-axis arrow with spatial speed.
 * [mark] is the coach mark that hangs from it, where the Deck has one up.
 */
@Composable
fun Nub(
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    mark: (@Composable () -> Unit)? = null,
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
        mark?.invoke()
    }
}

/** What the Nub's coach mark says (product vision, the Nub): the gesture, its spatial speed, and the tap. */
internal const val NubCoachMarkText = "This is the Nub, your arrow keys. Drag from it in any direction; the further you drag, the faster it repeats. A tap sends Up."

/**
 * The trailing layer key (spec C4): tap for the next layer, swipe up for the layer picker
 * ([LayerPickerMenu]), hold for the Deck editor. To a reader it is the `Layer` button in the state
 * [layerName], whose activation is `Next layer` and whose actions are the previous layer, the
 * picker and the editor; the swipe across a key (spec D2) is the finger's way back a layer.
 */
@Composable
private fun LayerKey(
    enabled: Boolean,
    haptics: HapticFeedback,
    layerName: String,
    modifier: Modifier = Modifier,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPick: () -> Unit,
    onHold: (() -> Unit)? = null,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    var pressed by remember { mutableStateOf(false) }
    // The gesture block only restarts when `enabled` changes, so it reads the latest callbacks
    // rather than the ones captured when the key first composed (those hold that moment's layer index).
    val currentOnNext by rememberUpdatedState(onNext)
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnPick by rememberUpdatedState(onPick)
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
                        add(CustomAccessibilityAction("Layers") { patterns.keyTap(); currentOnPick(); true })
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
                                    if (up) currentOnPick() else currentOnNext()
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
 * What a row's layer picker offers: the Deck's [layers] with the row's [shown] one selected, less
 * the one the other row [taken] shows, and the tab's predictive text when the Stage lets the Deck
 * switch it ([onPredictiveTextChange]). The picker reads in [direction], the interface's.
 */
private class LayerPicker(
    val layers: List<DeckLayer>,
    val shown: Int,
    val taken: Int?,
    val onSelect: (Int) -> Unit,
    val predictiveText: Boolean,
    val onPredictiveTextChange: ((Boolean) -> Unit)?,
    val direction: LayoutDirection,
)

/**
 * The layer picker the layer key's swipe up opens (spec C4): a menu of the row's layers, where a
 * choice shows that layer and closes it, then the Predictive text switch for the tab on stage
 * (product vision, the IME: the Session sheet's and the picker's), which leaves it open.
 */
@Composable
private fun LayerPickerMenu(picker: LayerPicker, expanded: Boolean, onDismiss: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides picker.direction) {
        BerthMenu(expanded = expanded, onDismiss = onDismiss) {
            picker.layers.forEachIndexed { i, layer ->
                if (i != picker.taken) {
                    BerthMenuItem(layer.name, selected = i == picker.shown, onClick = {
                        picker.onSelect(i)
                        onDismiss()
                    })
                }
            }
            picker.onPredictiveTextChange?.let { change -> BerthMenuToggle("Predictive text", picker.predictiveText, change) }
        }
    }
}

/** The collapsed Deck's strip is 20 dp (spec C4); its tap reaches up to 24 dp below it, a 44 dp target (spec A11, #15 review nit 9). */
val DeckStripHeight: Dp = 20.dp
val DeckStripReach: Dp = 24.dp

/**
 * The collapsed Deck (C4 "hardware keyboard attached", also the hidden-Deck state in either
 * orientation): layer name plus latched modifiers on a 20 dp strip; tap to expand. The strip is
 * drawn at its 20 dp and the layout around it sees 20 dp, so the terminal keeps the rows the strip
 * gave back; the box that takes the tap reaches down under the strip, into the navigation bar's
 * inset the chrome pays below it, as far as that inset goes and [DeckStripReach] at most: a 44 dp
 * target under a 24 dp bar. Down and not up: above the strip is the terminal's last line, or the
 * state pill's Detach while a session reconnects, and a reach there would take their touches;
 * under it is the bar's inset, which is nobody's, and which is there whenever the strip is, since
 * the strip stands for a keyboard that is not on the screen. Under a soft keyboard's own window
 * there is nothing to reach into, and the strip is its 20 dp. (A reader and a switch measure 48 dp
 * either way: Compose reports a small control's touch bounds grown to the minimum.)
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
    val fill = if (pressed || focused) c.surface2 else c.surface1
    // The window's own insets, not what the chrome above has consumed: the room is there whether or not it was paid here.
    val density = LocalDensity.current
    val barBelow = WindowInsets.navigationBars.getBottom(density)
    val keyboardBelow = WindowInsets.ime.getBottom(density)
    val reach = if (keyboardBelow > 0) 0.dp else with(density) { barBelow.toDp() }.coerceAtMost(DeckStripReach)
    Row(
        modifier
            .fillMaxWidth()
            .reachingDown(reach)
            .height(DeckStripHeight + reach)
            // Only the strip's own 20 dp are painted; the reach under it draws nothing.
            .drawBehind { drawRect(fill, size = Size(size.width, size.height - reach.toPx())) }
            // Where Ctrl+Shift+K lands while the Deck is hidden, and where a Deck hidden under the
            // keyboard's focus hands it: taken in touch mode too, or the chord would find nothing here.
            .alwaysFocusable()
            // A real click, so a touch that merely starts here (or a swipe passing through) does not
            // open the Deck, and the announced button can be activated.
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onExpand)
            .semantics { contentDescription = "Deck collapsed, tap to show it" }
            .padding(bottom = reach)
            .padding(horizontal = DeckEdge + GripWidth + DeckGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sized from the 20 dp strip like a key's text from its key, so the line holds at the interface's
        // font cap; in `text.2`, since `Base · Ctrl` is the one place a latched modifier shows while the
        // Deck is down and `text.3` does not carry meaning alone (A11; #20 review).
        Text((listOf(layerName) + mods).joinToString(" \u00B7 "), style = BerthType.caption.keySized(), color = if (focused) c.accent else c.text2)
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Lets a control reach [reach] below its place without moving what is around it: the control is
 * measured [reach] taller than the room it is given and the layout is told the height it would
 * have had, so its bottom lies over whatever the parent has under it (an inset's padding) while
 * nothing else moves. The reach takes a touch; the control draws there or not as it likes.
 */
private fun Modifier.reachingDown(reach: Dp): Modifier = layout { measurable, constraints ->
    val extra = reach.roundToPx()
    val taller = constraints.copy(
        minHeight = 0,
        maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + extra else constraints.maxHeight,
    )
    val placeable = measurable.measure(taller)
    val height = (placeable.height - extra).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.place(0, 0) }
}

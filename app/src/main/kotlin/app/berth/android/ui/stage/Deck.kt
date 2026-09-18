package app.berth.android.ui.stage

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.JetBrainsMono
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Layers that can be shown: the snippets layer waits for a snippets screen. */
fun DeckLayout.usableLayers(): List<DeckLayer> = layers.filter { layer -> !(layer.keys.size == 1 && layer.keys[0].snippets) }

/**
 * The keyboard accessory bar, rendered from [DeckLayout] rather than a hardcoded row. Each key has
 * tap, swipe-up and hold gestures; modifiers latch one-shot or locked; the Nub sends arrows.
 */
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
) {
    val c = Berth.colors
    val layers = layout.usableLayers()
    if (layers.isEmpty()) return
    val index = layerIndex.coerceIn(0, layers.lastIndex)
    val layer = layers[index]
    var strip by remember { mutableStateOf<List<DeckKeyCode>?>(null) }
    val haptics = LocalHapticFeedback.current
    val height = layout.heightDp.coerceIn(40, 52).dp

    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface1)
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        strip?.let { keys ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (k in keys) {
                    DeckKeyView(
                        key = DeckKey(tap = DeckAction.Key(k)),
                        latch = input.latch,
                        enabled = enabled,
                        haptics = haptics,
                        modifier = Modifier.width(48.dp).fillMaxHeight(),
                        onAction = { input.dispatch(it, layer) },
                        onHold = {},
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Grip(
                accent = predictiveText,
                onTap = onGripTap,
                onSwipeDown = onGripSwipeDown,
            )
            for (key in layer.keys) {
                when {
                    key.nub -> Nub(
                        enabled = enabled,
                        haptics = haptics,
                        modifier = Modifier.weight(1f).widthIn(min = 40.dp),
                        onArrow = { input.onKey(it) },
                    )
                    key.snippets -> Unit
                    else -> DeckKeyView(
                        key = key,
                        latch = input.latch,
                        enabled = enabled,
                        haptics = haptics,
                        modifier = Modifier.weight(1f).widthIn(min = 40.dp).fillMaxHeight(),
                        onAction = { input.dispatch(it, layer) },
                        onHold = { held ->
                            if (held is DeckAction.Strip) strip = if (strip == held.strip) null else held.strip
                        },
                    )
                }
            }
            LayerKey(
                enabled = enabled,
                haptics = haptics,
                modifier = Modifier.width(40.dp).fillMaxHeight(),
                onNext = { strip = null; onLayerIndexChange((index + 1) % layers.size) },
                onPrevious = { strip = null; onLayerIndexChange((index - 1 + layers.size) % layers.size) },
            )
        }
    }
}

@Composable
private fun Grip(accent: Boolean, onTap: () -> Unit, onSwipeDown: () -> Unit) {
    val c = Berth.colors
    val color by animateColorAsState(if (accent) c.accent else c.text3, tween(120), label = "grip")
    Box(
        Modifier
            .width(14.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var swipedDown = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (swipedDown) onSwipeDown() else onTap()
                            break
                        }
                        if (change.position.y - down.position.y > 24.dp.toPx()) swipedDown = true
                        change.consume()
                    }
                }
            }
            .semantics { contentDescription = "Grip: tap for the session sheet, swipe down to hide the keyboard" },
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
    onAction: (DeckAction) -> Unit,
    onHold: (DeckAction) -> Unit,
) {
    val c = Berth.colors
    var pressed by remember { mutableStateOf(false) }
    var swipe by remember { mutableStateOf(0) }
    val modifierAction = key.tap as? DeckAction.Modifier
    val latchState = modifierAction?.let { latch.state(it.modifier) } ?: LatchState.NONE
    val latched = latchState != LatchState.NONE
    val bg by animateColorAsState(
        when {
            latched -> c.accent
            pressed -> c.surface4
            else -> c.surface2
        },
        tween(80),
        label = "key",
    )
    val labelColor = if (latched) c.onAccent else c.text1
    val secondaryColor = if (latched) c.onAccent.copy(alpha = 0.7f) else c.text3
    val currentKey by rememberUpdatedState(key)
    val currentOnAction by rememberUpdatedState(onAction)
    val currentOnHold by rememberUpdatedState(onHold)
    val description = buildString {
        append(key.label)
        when (latchState) {
            LatchState.ONE_SHOT -> append(", one-shot")
            LatchState.LOCKED -> append(", locked")
            LatchState.NONE -> Unit
        }
        key.secondaryLabel?.let { append(", swipe up for $it") }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(BerthRadius.key))
            .background(bg)
            .semantics { contentDescription = description }
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
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        currentOnHold(hold)
                                    } else if (repeating) {
                                        k.tap?.let(currentOnAction)
                                    }
                                } else if (repeating) {
                                    k.tap?.let(currentOnAction)
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
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
                                    if (action != null) {
                                        haptics.performHapticFeedback(
                                            if (action is DeckAction.Modifier) HapticFeedbackType.Confirm else HapticFeedbackType.VirtualKey,
                                        )
                                        currentOnAction(action)
                                    }
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
        val mono = key.tap is DeckAction.Text && key.label.length <= 2
        Text(
            text = if (swipe == 1 && secondary != null) secondary else key.label,
            style = if (mono) BerthType.label.copy(fontFamily = JetBrainsMono) else BerthType.label,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center),
        )
        if (secondary != null && swipe != 1) {
            Text(
                text = secondary,
                style = BerthType.caption.copy(fontFamily = JetBrainsMono, letterSpacing = 0.sp),
                color = secondaryColor,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 5.dp),
            )
        }
        if (latchState == LatchState.LOCKED) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(16.dp, 2.dp)
                    .clip(CircleShape)
                    .background(c.onAccent),
            )
        }
    }
}

/** The circular arrow key: tap sends Up; drag sends the dominant-axis arrow with spatial speed. */
@Composable
fun Nub(
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    onArrow: (TerminalKey) -> Unit,
) {
    val c = Berth.colors
    var active by remember { mutableStateOf<TerminalKey?>(null) }
    var pressed by remember { mutableStateOf(false) }
    val currentOnArrow by rememberUpdatedState(onArrow)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (pressed) c.surface4 else c.surface2)
                .semantics { contentDescription = "Nub, tap for Up, drag to move the cursor" }
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
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                    }
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!everMoved) {
                                        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
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
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
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

/** The trailing layer key: tap for the next layer, swipe up for the previous one. */
@Composable
private fun LayerKey(
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val c = Berth.colors
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(BerthRadius.key))
            .background(if (pressed) c.surface4 else c.surface2)
            .semantics { contentDescription = "Layer: tap for the next layer, swipe up for the previous" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pressed = true
                    var up = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                if (up) onPrevious() else onNext()
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
        Text("\u22EF", style = BerthType.label, color = c.text2)
    }
}

/** Visible when a hardware keyboard is attached: layer name plus latched modifiers, 20 dp tall. */
@Composable
fun DeckStrip(layerName: String, latch: ModifierLatch, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val mods = buildList {
        if (latch.ctrl != LatchState.NONE) add("Ctrl")
        if (latch.alt != LatchState.NONE) add("Alt")
        if (latch.shift != LatchState.NONE) add("Shift")
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(c.surface1)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onExpand() } }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text((listOf(layerName) + mods).joinToString(" \u00B7 "), style = BerthType.caption, color = c.text3)
        Spacer(Modifier.weight(1f))
    }
}

package app.berth.android.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.a11y.touchTarget
import app.berth.android.ui.stage.LocalHapticLevel
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.HapticLevel

/** How long a [HoldButton] is held before it fires: A9's hold-to-confirm, "a fill sweeps left to right over 800 ms". */
const val HOLD_TO_CONFIRM_MS = 800

/**
 * The spec's hold-to-confirm answer (C13, `Replace saved key — hold to confirm`): a destructive
 * button in [BerthButton]'s clothes that fires after [holdMillis] of being held (A9's 800 ms), the fill filling
 * from the leading edge for as long as the finger stays and falling back when it lifts early, so a
 * tap explains itself. A screen reader's long-press (TalkBack's double-tap and hold) confirms
 * outright, since a timed hold is not a gesture it relays; a keyboard holds Enter or Space the way
 * a finger holds the button. One heavy tick marks the moment it fires, at any haptic level but Off.
 */
@Composable
fun HoldButton(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdMillis: Int = HOLD_TO_CONFIRM_MS,
) {
    val c = Berth.colors
    val haptics = LocalHapticFeedback.current
    val hapticLevel = LocalHapticLevel.current
    val interaction = remember { MutableInteractionSource() }
    val focused = enabled && interaction.showsFocus()
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    var holding by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(holding) {
        if (holding) {
            progress.animateTo(1f, tween(((1f - progress.value) * holdMillis).toInt(), easing = LinearEasing))
            if (progress.value >= 1f) {
                if (hapticLevel != HapticLevel.OFF) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                holding = false
                progress.snapTo(0f)
                currentOnConfirm()
            }
        } else if (progress.value > 0f) {
            progress.animateTo(0f, tween(160))
        }
    }

    val fill = if (holding || focused) c.surface4 else c.surface3
    val label = c.danger
    Box(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (holding) "Holding" else "Hold to confirm"
                if (enabled) onLongClick(label = "Confirm") { currentOnConfirm(); true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            }
            .onKeyEvent { event ->
                val pressKey = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.DirectionCenter || event.key == Key.Spacebar
                when {
                    !enabled || !pressKey -> false
                    event.type == KeyEventType.KeyDown -> { holding = true; true }
                    event.type == KeyEventType.KeyUp -> { holding = false; true }
                    else -> false
                }
            }
            .focusable(enabled, interaction)
            .touchTarget(),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .defaultMinSize(minHeight = 44.dp, minWidth = 44.dp)
                .clip(RoundedCornerShape(BerthRadius.row))
                .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.5f))
                // The hold's progress, a danger wash from the leading edge over the fill's width.
                .drawBehind {
                    val width = size.width * progress.value
                    val start = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRect(c.danger.copy(alpha = 0.22f), topLeft = Offset(start, 0f), size = Size(width, size.height))
                }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The label carries the spec's `— hold to confirm`, so at the interface's font cap (A11) it takes a second line rather than losing that half.
            Text(
                text,
                style = BerthType.label,
                color = if (enabled) label else label.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                maxLines = linesAtFontScale(1),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

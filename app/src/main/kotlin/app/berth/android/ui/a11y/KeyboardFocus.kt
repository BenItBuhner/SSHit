package app.berth.android.ui.a11y

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager

/**
 * Whether a control shows its focus (spec, Components: "Focus is shown by moving one tonal step up
 * and colouring the label with accent"): it holds the keyboard's focus, and keys are what is
 * driving. Android leaves touch mode on the first hardware key and returns to it on the next touch,
 * and Compose reads that mode; while a finger drives, the focus sits where the finger last put it
 * (the terminal, a field) and is not shown, the way a View's focus highlight is not. So a control
 * takes its pressed tone while focused this way, and its label the accent.
 */
@Composable
fun InteractionSource.showsFocus(): Boolean {
    val focused by collectIsFocusedAsState()
    return focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

/**
 * The keyboard's press of a control whose touch is its own gesture (a Deck key's tap, swipe and
 * hold; a tab's tap and drag; the Nub's drag): the control takes focus, and Enter, the keypad's
 * Enter, the D-pad's centre and Space press it on release, the keys Compose's own clickable takes.
 * Only the press goes to [onPress]; a control's alternates stay with the screen reader's actions,
 * where a keyboard user reaches them through TalkBack too. Disabled, the control neither takes the
 * focus nor a press, and a control that is `clickable` needs none of this.
 */
fun Modifier.keyPressable(enabled: Boolean, interactionSource: MutableInteractionSource, onPress: () -> Unit): Modifier = this
    .onKeyEvent { event ->
        when {
            !enabled || !event.isPressKey() -> false
            event.type == KeyEventType.KeyUp -> {
                onPress()
                true
            }
            // The down is taken too, so it travels no further as a key of the host's or a scroll.
            event.type == KeyEventType.KeyDown -> true
            else -> false
        }
    }
    .focusable(enabled, interactionSource)

private fun KeyEvent.isPressKey(): Boolean =
    key == Key.Enter || key == Key.NumPadEnter || key == Key.DirectionCenter || key == Key.Spacebar

/**
 * A `clickable` that the Stage puts the keyboard's focus on by request (a region's landing control,
 * the collapsed Deck strip) takes it in touch mode too. Compose makes a clickable focusable only out
 * of touch mode, as a View is, and Android leaves touch mode for a typed character or a navigation
 * key but not for a Ctrl chord; so with a keyboard attached and nothing typed yet, a chord's landing
 * place would refuse the focus and the chord would put it somewhere else. A control that takes
 * focus by [keyPressable] or `focusable` is focusable in either mode already, and in touch mode
 * no control shows its focus ([showsFocus]), so the only change is that the request is honoured.
 */
fun Modifier.alwaysFocusable(): Modifier = focusProperties { canFocus = true }

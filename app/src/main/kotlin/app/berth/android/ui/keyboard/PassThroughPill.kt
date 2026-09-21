package app.berth.android.ui.keyboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.Pill
import app.berth.android.ui.theme.Berth

/**
 * Pass-through's indicator in the Stage chrome (spec C22, A46): the `scrolled` pill's twin in the
 * header's trailing slot, `pass-through` in accent Caption on surface.3, one step up while pressed
 * or focused. Quiet by design, since the mode may stand for a whole session, and visible for the
 * same reason: while it stands no chord works, and this is the one thing on screen that says why.
 * The tap is the touch way out; the chord that turned it on is the keyboard's. In a 40 dp target
 * inside the header's row; to a screen reader one button that says what it ends.
 */
@Composable
fun PassThroughPill(onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val c = Berth.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    Box(
        modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onEnd)
            .clearAndSetSemantics {
                contentDescription = "Pass-through on, every key goes to the shell. End pass-through"
                role = Role.Button
            }
            .padding(horizontal = 4.dp, vertical = 9.dp)
            .testTag(PassThroughPillTag),
    ) {
        Pill("pass-through", color = if (pressed || focused) c.surface4 else c.surface3, textColor = c.accent)
    }
}

/** The pill's tag, for a test to find it by. */
const val PassThroughPillTag = "pass-through-pill"

package app.berth.android.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.berth.android.ui.a11y.showsFocus
import app.berth.android.ui.components.BerthIcon
import app.berth.android.ui.components.BerthIcons
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius

/**
 * The key that trails a one-layer Deck, in the layer key's place. Spec C4's `⋯` cycles the layers
 * on a tap, swipes up to the one before and holds for the Deck editor; with one layer there is
 * nothing to cycle, and a key that ticks and changes nothing is a false affordance, twice so to a
 * reader told `Next layer`. So the trailing key is the Deck editor outright: the pencil, a tap or
 * Enter opens it, and there are no layer actions to announce. The compact Deck under a hardware
 * keyboard is one layer; a layout the user has cut to one is another. Where there is no editor to
 * open (the editor's own preview, the layout previews) the key stands disabled rather than absent,
 * so the row's keys measure as the Stage's do.
 */
@Composable
internal fun DeckEditorKey(
    enabled: Boolean,
    haptics: HapticFeedback,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)?,
) {
    val c = Berth.colors
    val patterns = rememberDeckHaptics(haptics)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused = interaction.showsFocus()
    val opens = enabled && onOpen != null
    Box(
        modifier
            .clip(RoundedCornerShape(BerthRadius.key))
            .background(if (pressed) c.surface4 else if (focused) c.surface3 else c.surface2)
            .testTag(DeckKeyTag)
            .semantics { contentDescription = "Deck editor" }
            // A clickable outright: the touch is a tap and nothing else, so Enter, Space and the
            // D-pad's centre come with it, and the reader hears one button with one activation.
            .clickable(enabled = opens, interactionSource = interaction, indication = null, role = Role.Button) {
                patterns.keyTap()
                onOpen?.invoke()
            },
        contentAlignment = Alignment.Center,
    ) {
        BerthIcon(BerthIcons.edit, tint = if (focused) c.accent else c.text2)
    }
}

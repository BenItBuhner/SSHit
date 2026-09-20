package app.berth.android.ui.keyboard

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout

/** The name of the one layer the compact Deck has; the strip shows it while that Deck is hidden. */
const val HARDWARE_DECK_LAYER = "Keyboard"

/** Whether a hardware keyboard is attached and open, as the configuration reports it. */
@Composable
fun rememberHardwareKeyboardAttached(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}

/**
 * The Deck's fold on a hardware keyboard (spec C4, "Hardware keyboard attached"): when one is
 * attached the Deck folds to its 20 dp strip, the layer name and any latched modifier, and the
 * terminal has the rows back; when the keyboard goes, the Deck stands again. Attaching and
 * removing each flip the Deck once, through [onDeckVisibleChange], and between them the user's own
 * choice holds: the strip's tap, Ctrl+Shift+E and the overflow's Show Deck open it, the grip's
 * swipe hides it. What was last seen is saved with the Stage, so a rotation with the keyboard still
 * attached does not fold a Deck the user opened, and a tab switch never re-folds it (the Stage,
 * not the tab, owns the Deck's visibility). The first composition with a keyboard folds; the
 * first without leaves the Deck as it was saved.
 */
@Composable
fun FoldDeckOnHardwareKeyboard(attached: Boolean, onDeckVisibleChange: (Boolean) -> Unit) {
    var seen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(attached) {
        if (attached == seen) return@LaunchedEffect
        val first = seen == null
        seen = attached
        if (!first || attached) onDeckVisibleChange(!attached)
    }
}

/**
 * The Deck a hardware keyboard's user opens from that strip (spec C4; Settings › Hardware keyboard ›
 * Compact Deck when expanded): one row of the layout's modifier keys followed by its app action
 * keys, each once, in the order they first appear across the layers, with Paste added when no key
 * pastes. Letters, symbols, arrows and function keys have a keyboard now; a Ctrl or Alt latch for
 * the next character, Paste through the preview and the session actions still earn their place,
 * one tap away without standing there. The keys keep their swipe and hold alternates, since they
 * are the user's own.
 */
fun DeckLayout.compactForHardwareKeyboard(): DeckLayout {
    val modifiers = ArrayList<DeckKey>()
    val actions = ArrayList<DeckKey>()
    val seen = HashSet<DeckAction>()
    for (layer in layers) {
        for (key in layer.keys) {
            val tap = key.tap ?: continue
            when (tap) {
                is DeckAction.Modifier -> if (seen.add(tap)) modifiers += key
                is DeckAction.App -> if (seen.add(tap) && tap.action.keepsOnHardwareKeyboard) actions += key
                else -> Unit
            }
        }
    }
    if (actions.none { (it.tap as? DeckAction.App)?.action == DeckAppAction.PASTE }) {
        actions += DeckKey(tap = DeckAction.App(DeckAppAction.PASTE), display = "Paste")
    }
    return copy(rows = 1, layers = listOf(DeckLayer(name = HARDWARE_DECK_LAYER, keys = modifiers + actions)))
}

/** Layer switching has nothing to switch on a one-layer Deck, and the soft keyboard is not up to hide. */
private val DeckAppAction.keepsOnHardwareKeyboard: Boolean
    get() = this != DeckAppAction.NEXT_LAYER && this != DeckAppAction.PREVIOUS_LAYER && this != DeckAppAction.HIDE_KEYBOARD

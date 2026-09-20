package app.berth.android.ui.keyboard

import android.content.res.Configuration
import androidx.compose.runtime.Composable
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
 * The Deck a hardware keyboard leaves standing (spec C4, "Hardware keyboard attached"; Settings ›
 * Hardware keyboard › Compact Deck): one row of the layout's modifier keys followed by its app
 * action keys, each once, in the order they first appear across the layers, with Paste added when
 * no key pastes. Letters, symbols, arrows and function keys have a keyboard now; a Ctrl or Alt
 * latch for the next character, Paste through the preview and the session actions still earn
 * their place. The keys keep their swipe and hold alternates, since they are the user's own.
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

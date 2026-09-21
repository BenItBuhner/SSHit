package app.berth.android.ui.keyboard

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import app.berth.android.session.TerminalSession
import app.berth.domain.model.VolumeButtons
import app.berth.terminal.TerminalKey

/** What one volume button does under a [VolumeButtons] setting (spec A43): a key, a control character or a font step. */
sealed interface VolumeAction {
    data class Key(val key: TerminalKey) : VolumeAction
    data class Control(val char: Char) : VolumeAction
    data class FontStep(val step: Int) : VolumeAction
}

/**
 * The action [up] (volume up, else volume down) sends under [setting]: the arrows, Page Up and
 * Page Down, the font a step larger and smaller, or Ctrl+C and Enter; null under [VolumeButtons.OFF].
 */
fun volumeButtonAction(setting: VolumeButtons, up: Boolean): VolumeAction? = when (setting) {
    VolumeButtons.OFF -> null
    VolumeButtons.ARROWS -> VolumeAction.Key(if (up) TerminalKey.UP else TerminalKey.DOWN)
    VolumeButtons.PAGES -> VolumeAction.Key(if (up) TerminalKey.PAGE_UP else TerminalKey.PAGE_DOWN)
    VolumeButtons.FONT_SIZE -> VolumeAction.FontStep(if (up) 1 else -1)
    VolumeButtons.INTERRUPT_AND_ENTER -> if (up) VolumeAction.Control('C') else VolumeAction.Key(TerminalKey.ENTER)
}

/**
 * The phone's volume buttons as the Stage's (spec A43, C20 "Volume buttons"). The activity hands
 * every key event here before the window sees it ([dispatch]), so a volume press never reaches
 * the system's volume while the Stage has a use for it; the Stage sets [handler] while a shell
 * tab is on it under a setting other than Off ([BindVolumeButtons]) and clears it when the tab
 * or the screen goes, so on every other screen, and on a Files tab, the buttons are the volume's.
 * A held button repeats the way a held arrow does; the release is swallowed with the press, or
 * the system would show its volume panel for it.
 */
class VolumeKeys {
    var handler: ((up: Boolean) -> Unit)? = null

    /** True when the event was a volume button the Stage took; false leaves it to the window and the system. */
    fun dispatch(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        val handler = handler ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) handler(up)
        return true
    }
}

/** The activity's [VolumeKeys]; the default stands alone, for a Stage composed without one (a test). */
val LocalVolumeKeys = staticCompositionLocalOf { VolumeKeys() }

/**
 * Binds the volume buttons to [session] under [setting] for as long as both are on stage: the
 * arrows, the pages and Ctrl+C and Enter go to the shell as the keyboard's would, the font step
 * goes to [onFontStep], the Stage's own. Nothing is bound under Off or with no shell tab, and the
 * binding is dropped when the composition leaves, so the buttons return to the system with the Stage.
 */
@Composable
fun BindVolumeButtons(session: TerminalSession?, setting: VolumeButtons, onFontStep: (Int) -> Unit) {
    val keys = LocalVolumeKeys.current
    val fontStep by rememberUpdatedState(onFontStep)
    DisposableEffect(keys, session, setting) {
        if (session == null || setting == VolumeButtons.OFF) return@DisposableEffect onDispose { }
        keys.handler = { up ->
            when (val action = volumeButtonAction(setting, up)) {
                is VolumeAction.Key -> session.sendKey(action.key)
                is VolumeAction.Control -> session.sendControl(action.char)
                is VolumeAction.FontStep -> fontStep(action.step)
                null -> Unit
            }
        }
        onDispose { keys.handler = null }
    }
}

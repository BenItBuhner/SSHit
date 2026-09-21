package app.berth.android.ui.tabs

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The strip's fixed hardware shortcuts of spec C3: Ctrl+Tab / Ctrl+Shift+Tab step, Ctrl+1…8 jump,
 * Ctrl+9 is the last tab. Browser conventions that take no prefix and no remap: none of them reaches
 * a terminal usefully, so they are always the strip's. The rest of the strip's keys, Ctrl+T and
 * Ctrl+W and the chords for the switcher and the unread tab, are the chord dispatcher's
 * ([app.berth.android.ui.keyboard.HardwareShortcuts]), since the readline setting and the remaps
 * decide them.
 */
class TabShortcuts(
    private val step: (Int) -> Unit,
    /** Strip index, or -1 for the last tab. */
    private val jump: (Int) -> Unit,
) {
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed
        val digit = DIGITS.indexOf(event.key)
        return when {
            event.key == Key.Tab -> { step(if (shift) -1 else 1); true }
            digit in 0..7 && !shift -> { jump(digit); true }
            digit == 8 && !shift -> { jump(-1); true }
            else -> false
        }
    }

    private companion object {
        val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    }
}

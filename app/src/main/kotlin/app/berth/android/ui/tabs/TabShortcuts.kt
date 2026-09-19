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
 * The hardware shortcuts of spec C3: Ctrl+Tab / Ctrl+Shift+Tab step, Ctrl+1…8 jump, Ctrl+9 is the
 * last tab, Ctrl+T new tab, Ctrl+W close, Ctrl+Shift+A the switcher, Ctrl+Shift+U the most recent
 * unread tab (C22). Ctrl+Tab and the digits never reach a terminal usefully and are always taken;
 * T and W are readline keys, so with [ctrlTabKeysReachTerminal] they pass through and only the
 * Shift chords act.
 */
class TabShortcuts(
    private val step: (Int) -> Unit,
    /** Strip index, or -1 for the last tab. */
    private val jump: (Int) -> Unit,
    private val newTab: () -> Unit,
    private val closeActive: () -> Unit,
    private val switcher: () -> Unit,
    private val jumpToUnread: () -> Unit = {},
) {
    fun handle(event: KeyEvent, ctrlTabKeysReachTerminal: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val shift = event.isShiftPressed
        val digit = DIGITS.indexOf(event.key)
        return when {
            event.key == Key.Tab -> { step(if (shift) -1 else 1); true }
            digit in 0..7 && !shift -> { jump(digit); true }
            digit == 8 && !shift -> { jump(-1); true }
            event.key == Key.T && (shift || !ctrlTabKeysReachTerminal) -> { newTab(); true }
            event.key == Key.W && (shift || !ctrlTabKeysReachTerminal) -> { closeActive(); true }
            event.key == Key.A && shift -> { switcher(); true }
            event.key == Key.U && shift -> { jumpToUnread(); true }
            else -> false
        }
    }

    private companion object {
        val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
    }
}

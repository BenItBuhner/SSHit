package app.berth.android.ui.tabs

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_T
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_U
import android.view.KeyEvent.KEYCODE_W
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The hardware chords of spec C3 and C22, as the Stage's key handler sees them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabShortcutsTest {
    private val calls = ArrayList<String>()
    private val shortcuts = TabShortcuts(
        step = { calls += "step $it" },
        jump = { calls += "jump $it" },
        newTab = { calls += "new" },
        closeActive = { calls += "close" },
        switcher = { calls += "switcher" },
        jumpToUnread = { calls += "unread" },
    )

    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    @Test
    fun `Ctrl+Shift+U jumps to the unread tab, Ctrl+U stays the shell's`() {
        assertTrue(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON), ctrlTabKeysReachTerminal = false))
        assertTrue(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON), ctrlTabKeysReachTerminal = true))
        // Readline's kill-line, and a key going back up, are not the chord.
        assertFalse(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON), ctrlTabKeysReachTerminal = false))
        assertFalse(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON, ACTION_UP), ctrlTabKeysReachTerminal = false))
        assertFalse(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON or META_ALT_ON), ctrlTabKeysReachTerminal = false))
        assertEquals(listOf("unread", "unread"), calls)
    }

    @Test
    fun `the strip's other chords still land`() {
        shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON), false)
        shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON or META_SHIFT_ON), false)
        shortcuts.handle(key(KEYCODE_1, META_CTRL_ON), false)
        shortcuts.handle(key(KEYCODE_9, META_CTRL_ON), false)
        shortcuts.handle(key(KEYCODE_T, META_CTRL_ON), false)
        shortcuts.handle(key(KEYCODE_W, META_CTRL_ON), false)
        shortcuts.handle(key(KEYCODE_A, META_CTRL_ON or META_SHIFT_ON), false)
        assertEquals(listOf("step 1", "step -1", "jump 0", "jump -1", "new", "close", "switcher"), calls)
        calls.clear()
        // With Ctrl+T and Ctrl+W left to the terminal only the Shift chords act.
        assertFalse(shortcuts.handle(key(KEYCODE_T, META_CTRL_ON), true))
        assertFalse(shortcuts.handle(key(KEYCODE_W, META_CTRL_ON), true))
        assertTrue(shortcuts.handle(key(KEYCODE_T, META_CTRL_ON or META_SHIFT_ON), true))
        assertTrue(shortcuts.handle(key(KEYCODE_W, META_CTRL_ON or META_SHIFT_ON), true))
        assertEquals(listOf("new", "close"), calls)
    }
}

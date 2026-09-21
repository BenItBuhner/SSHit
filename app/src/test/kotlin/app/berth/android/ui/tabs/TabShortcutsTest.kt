package app.berth.android.ui.tabs

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_8
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_T
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_U
import android.view.KeyEvent.KEYCODE_W
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The strip's fixed hardware keys of spec C3, as the Stage's key handler sees them: the browser
 * conventions that take no prefix and no remap. The strip's other keys (Ctrl+T, Ctrl+W, the
 * switcher, the unread tab) are the chord dispatcher's now and tested with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TabShortcutsTest {
    private val calls = ArrayList<String>()
    private val shortcuts = TabShortcuts(
        step = { calls += "step $it" },
        jump = { calls += "jump $it" },
    )

    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    @Test
    fun `Ctrl+Tab steps, Ctrl+Shift+Tab steps back, Ctrl+1 to 8 jump and Ctrl+9 is the last tab`() {
        assertTrue(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON)))
        assertTrue(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON or META_SHIFT_ON)))
        assertTrue(shortcuts.handle(key(KEYCODE_1, META_CTRL_ON)))
        assertTrue(shortcuts.handle(key(KEYCODE_8, META_CTRL_ON)))
        assertTrue(shortcuts.handle(key(KEYCODE_9, META_CTRL_ON)))
        assertEquals(listOf("step 1", "step -1", "jump 0", "jump 7", "jump -1"), calls)
    }

    @Test
    fun `a key going up, a digit with Shift, or Alt or Meta joined is nobody's here`() {
        assertFalse(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON, ACTION_UP)))
        assertFalse(shortcuts.handle(key(KEYCODE_1, META_CTRL_ON or META_SHIFT_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON or META_ALT_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON or META_META_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_TAB, 0)))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `Ctrl+T, Ctrl+W and the Shift chords are not the strip's fixed keys, since the dispatcher decides them`() {
        assertFalse(shortcuts.handle(key(KEYCODE_T, META_CTRL_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_W, META_CTRL_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_T, META_CTRL_ON or META_SHIFT_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_W, META_CTRL_ON or META_SHIFT_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_A, META_CTRL_ON or META_SHIFT_ON)))
        assertFalse(shortcuts.handle(key(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON)))
        assertTrue(calls.isEmpty())
    }
}

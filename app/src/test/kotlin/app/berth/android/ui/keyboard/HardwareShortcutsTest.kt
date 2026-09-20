package app.berth.android.ui.keyboard

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_O
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_V
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import app.berth.android.ui.tabs.TabShortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Stage's chords of spec C22 beyond the strip's, as the Stage's key handler sees them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HardwareShortcutsTest {
    private val calls = ArrayList<String>()
    private val shortcuts = HardwareShortcuts(
        tabs = TabShortcuts(
            step = { calls += "step $it" },
            jump = { calls += "jump $it" },
            newTab = { calls += "new" },
            closeActive = { calls += "close" },
            switcher = { calls += "switcher" },
        ),
        stage = object : StageShortcutActions {
            override fun find() { calls += "find" }
            override fun copy() { calls += "copy" }
            override fun paste() { calls += "paste" }
            override fun toggleDeck() { calls += "deck" }
            override fun fontStep(step: Int) { calls += "font $step" }
            override fun shortcutSheet() { calls += "sheet" }
            override fun split() { calls += "split" }
            override fun focusOtherPane() { calls += "other pane" }
        },
    )

    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    @Test
    fun `Ctrl+F searches unless the readline keys are the shell's, when only Ctrl+Shift+F does`() {
        assertTrue(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON), ctrlTabKeysReachTerminal = false))
        assertTrue(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON), ctrlTabKeysReachTerminal = false))
        // Readline's forward-char reaches the terminal with Ctrl+T and Ctrl+W; the Shift chord still searches.
        assertFalse(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON), ctrlTabKeysReachTerminal = true))
        assertTrue(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON), ctrlTabKeysReachTerminal = true))
        assertEquals(listOf("find", "find", "find"), calls)
    }

    @Test
    fun `copy and paste take the Shift chords only, since Ctrl+C and Ctrl+V are the shell's`() {
        assertTrue(shortcuts.handle(key(KEYCODE_C, META_CTRL_ON or META_SHIFT_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_V, META_CTRL_ON or META_SHIFT_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_C, META_CTRL_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_V, META_CTRL_ON), false))
        assertEquals(listOf("copy", "paste"), calls)
    }

    @Test
    fun `the Deck, the font size and the sheet`() {
        assertTrue(shortcuts.handle(key(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_EQUALS, META_CTRL_ON or META_SHIFT_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_MINUS, META_CTRL_ON or META_SHIFT_ON), false))
        // A keypad has its own plus and minus, no Shift needed.
        assertTrue(shortcuts.handle(key(KEYCODE_NUMPAD_ADD, META_CTRL_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_NUMPAD_SUBTRACT, META_CTRL_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON), false))
        // Ctrl+E is readline's end-of-line, Ctrl+- and Ctrl+/ are the shell's too.
        assertFalse(shortcuts.handle(key(KEYCODE_E, META_CTRL_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_MINUS, META_CTRL_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_SLASH, META_CTRL_ON), false))
        assertEquals(listOf("deck", "font 1", "font -1", "font 1", "font -1", "sheet"), calls)
    }

    @Test
    fun `the strip's chords run first, and a key going up or with Alt joined is nobody's`() {
        assertTrue(shortcuts.handle(key(KEYCODE_TAB, META_CTRL_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON, ACTION_UP), false))
        assertFalse(shortcuts.handle(key(KEYCODE_F, META_CTRL_ON or META_ALT_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_C, META_CTRL_ON or META_SHIFT_ON or META_ALT_ON), false))
        assertEquals(listOf("step 1"), calls)
    }

    @Test
    fun `the pane chords are taken on every width, and the plain letters stay the shell's`() {
        assertTrue(shortcuts.handle(key(KEYCODE_D, META_CTRL_ON or META_SHIFT_ON), false))
        assertTrue(shortcuts.handle(key(KEYCODE_O, META_CTRL_ON or META_SHIFT_ON), false))
        // Ctrl+D is end-of-file and Ctrl+O readline's operate-and-get-next; both reach the shell.
        assertFalse(shortcuts.handle(key(KEYCODE_D, META_CTRL_ON), false))
        assertFalse(shortcuts.handle(key(KEYCODE_O, META_CTRL_ON), false))
        assertEquals(listOf("split", "other pane"), calls)
    }

    @Test
    fun `the sheet's pane rows say when they wait for a wider screen`() {
        val phone = shortcutGroups(ctrlTabKeysReachTerminal = false, panes = false).first { it.title == "Panes" }.entries
        val tablet = shortcutGroups(ctrlTabKeysReachTerminal = false, panes = true).first { it.title == "Panes" }.entries
        assertEquals(listOf("Ctrl+Shift+D", "Ctrl+Shift+O"), phone.map { it.keys })
        assertEquals(phone.map { it.keys }, tablet.map { it.keys })
        assertTrue(phone.all { "wide screen" in it.action || "when the Stage is split" in it.action })
        assertEquals(listOf("Split the Stage", "Focus the other pane"), tablet.map { it.action })
    }

    @Test
    fun `the sheet lists the readline keys on whichever side the setting puts them`() {
        val app = shortcutGroups(ctrlTabKeysReachTerminal = false)
        val shell = shortcutGroups(ctrlTabKeysReachTerminal = true)
        assertEquals(listOf("Tabs", "Stage", "Panes", "Terminal"), app.map { it.title })
        val appKeys = app.flatMap { it.entries }.map { it.keys }
        val shellKeys = shell.flatMap { it.entries }.map { it.keys }
        assertTrue("Ctrl+T" in appKeys && "Ctrl+W" in appKeys && "Ctrl+F" in appKeys)
        assertTrue("Ctrl+Shift+T" in shellKeys && "Ctrl+Shift+W" in shellKeys && "Ctrl+Shift+F" in shellKeys)
        assertFalse("Ctrl+T" in shellKeys)
        assertTrue(shell.last().entries.any { it.keys == "Ctrl+T, Ctrl+W, Ctrl+F" })
        assertFalse(app.last().entries.any { it.keys == "Ctrl+T, Ctrl+W, Ctrl+F" })
    }
}

package app.berth.android.ui.keyboard

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_ALT_RIGHT
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_CTRL_RIGHT
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_K
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_O
import android.view.KeyEvent.KEYCODE_P
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_T
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_U
import android.view.KeyEvent.KEYCODE_V
import android.view.KeyEvent.KEYCODE_W
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_CTRL_RIGHT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import app.berth.android.ui.tabs.TabShortcuts
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.LeaderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Stage's chords of spec C22, as the Stage's key handler sees them: under the default prefix,
 * under Meta and a Leader, as rebound, and in pass-through; the strip's plain Ctrl+T and Ctrl+W on
 * either side of the readline setting, and the first Ctrl+W's hint (A44, A46).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HardwareShortcutsTest {
    private val calls = ArrayList<String>()
    /** What the Stage answers to Escape: true while the focus is out of the body and has been brought back. */
    private var awayFromBody = true
    /** What the Stage answers to the first Ctrl+W: true while a live shell stands to hold the close for. */
    private var hintStands = true
    private var passThrough = false
    private val shortcuts = HardwareShortcuts(
        tabs = TabShortcuts(
            step = { calls += "step $it" },
            jump = { calls += "jump $it" },
        ),
        stage = object : StageShortcutActions {
            override fun newTab() { calls += "new" }
            override fun closeTab() { calls += "close" }
            override fun tabSwitcher() { calls += "switcher" }
            override fun jumpToUnread() { calls += "unread" }
            override fun find() { calls += "find" }
            override fun copy() { calls += "copy" }
            override fun paste() { calls += "paste" }
            override fun toggleDeck() { calls += "deck" }
            override fun fontStep(step: Int) { calls += "font $step" }
            override fun shortcutSheet() { calls += "sheet" }
            override fun split() { calls += "split" }
            override fun focusOtherPane() { calls += "other pane" }
            override fun focusStrip() { calls += "strip" }
            override fun focusDeck() { calls += "deck focus" }
            override fun returnToBody(): Boolean {
                calls += "body"
                return awayFromBody
            }
            override var passThrough: Boolean
                get() = this@HardwareShortcutsTest.passThrough
                set(value) {
                    this@HardwareShortcutsTest.passThrough = value
                    calls += if (value) "pass-through on" else "pass-through off"
                }
            override fun ctrlWHint(): Boolean {
                calls += "hint"
                return hintStands
            }
        },
    )

    private val defaults = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true))
    private val readline = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true), ctrlTabKeysReachTerminal = true)

    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    private fun handle(code: Int, meta: Int, table: ChordTable = defaults, action: Int = ACTION_DOWN) = shortcuts.handle(key(code, meta, action), table)

    // ---- the default prefix, Ctrl+Shift --------------------------------------------------------------

    @Test
    fun `only Ctrl+Shift+F searches, and plain Ctrl+F is the shell's whichever side the readline keys are on`() {
        assertTrue(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, defaults))
        assertTrue(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, readline))
        // Readline's forward-char, less's and every pager's forward key: C22 binds the Shift chord only, so
        // the plain key reaches the terminal on the default install and is not the readline setting's to move.
        assertFalse(handle(KEYCODE_F, META_CTRL_ON, defaults))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON, readline))
        assertEquals(listOf("find", "find"), calls)
    }

    @Test
    fun `copy and paste take the Shift chords only, since Ctrl+C and Ctrl+V are the shell's`() {
        assertTrue(handle(KEYCODE_C, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_V, META_CTRL_ON or META_SHIFT_ON))
        assertFalse(handle(KEYCODE_C, META_CTRL_ON))
        assertFalse(handle(KEYCODE_V, META_CTRL_ON))
        assertEquals(listOf("copy", "paste"), calls)
    }

    @Test
    fun `the Deck, the font size and the sheet`() {
        assertTrue(handle(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_EQUALS, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_MINUS, META_CTRL_ON or META_SHIFT_ON))
        // A keypad has its own plus and minus, no Shift needed.
        assertTrue(handle(KEYCODE_NUMPAD_ADD, META_CTRL_ON))
        assertTrue(handle(KEYCODE_NUMPAD_SUBTRACT, META_CTRL_ON))
        assertTrue(handle(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON))
        // Ctrl+E is readline's end-of-line, Ctrl+- and Ctrl+/ are the shell's too.
        assertFalse(handle(KEYCODE_E, META_CTRL_ON))
        assertFalse(handle(KEYCODE_MINUS, META_CTRL_ON))
        assertFalse(handle(KEYCODE_SLASH, META_CTRL_ON))
        assertEquals(listOf("deck", "font 1", "font -1", "font 1", "font -1", "sheet"), calls)
    }

    @Test
    fun `the strip's fixed keys run, and a key going up or with Alt joined is nobody's`() {
        assertTrue(handle(KEYCODE_TAB, META_CTRL_ON))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON, action = ACTION_UP))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_ALT_ON))
        assertFalse(handle(KEYCODE_C, META_CTRL_ON or META_SHIFT_ON or META_ALT_ON))
        assertEquals(listOf("step 1"), calls)
    }

    @Test
    fun `the pane chords are taken on every width, and the plain letters stay the shell's`() {
        assertTrue(handle(KEYCODE_D, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_O, META_CTRL_ON or META_SHIFT_ON))
        // Ctrl+D is end-of-file and Ctrl+O readline's operate-and-get-next; both reach the shell.
        assertFalse(handle(KEYCODE_D, META_CTRL_ON))
        assertFalse(handle(KEYCODE_O, META_CTRL_ON))
        assertEquals(listOf("split", "other pane"), calls)
    }

    @Test
    fun `the focus chords, and Escape is the Stage's only while the focus is out of the body`() {
        assertTrue(handle(KEYCODE_S, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_K, META_CTRL_ON or META_SHIFT_ON))
        // Ctrl+S is the shell's stop and Ctrl+K readline's kill-line.
        assertFalse(handle(KEYCODE_S, META_CTRL_ON))
        assertFalse(handle(KEYCODE_K, META_CTRL_ON))
        // A plain Escape from the strip or the Deck is taken; from the terminal the Stage declines it and the host gets it.
        assertTrue(handle(KEYCODE_ESCAPE, 0))
        awayFromBody = false
        assertFalse(handle(KEYCODE_ESCAPE, 0))
        // Escape with a modifier, or on its way up, is never the Stage's.
        assertFalse(handle(KEYCODE_ESCAPE, META_CTRL_ON))
        assertFalse(handle(KEYCODE_ESCAPE, META_SHIFT_ON))
        assertFalse(handle(KEYCODE_ESCAPE, 0, action = ACTION_UP))
        assertEquals(listOf("strip", "deck focus", "body", "body"), calls)
    }

    // ---- the strip's four, and Ctrl+T and Ctrl+W on either side of the readline setting ------------------

    @Test
    fun `the tabs' chords open, close, switch and jump, and Ctrl+Shift+U is the unread tab while Ctrl+U stays the shell's`() {
        assertTrue(handle(KEYCODE_T, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_W, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_A, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(handle(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON, readline))
        // Readline's unix-line-discard, a key going back up, and Alt joined are not the chord.
        assertFalse(handle(KEYCODE_U, META_CTRL_ON))
        assertFalse(handle(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON, action = ACTION_UP))
        assertFalse(handle(KEYCODE_U, META_CTRL_ON or META_SHIFT_ON or META_ALT_ON))
        assertEquals(listOf("new", "close", "switcher", "unread", "unread"), calls)
    }

    @Test
    fun `plain Ctrl+T and Ctrl+W are the strip's until the readline setting hands them to the shell, and the chords work on both sides`() {
        assertTrue(handle(KEYCODE_T, META_CTRL_ON, defaults))
        assertTrue(handle(KEYCODE_W, META_CTRL_ON, defaults))
        assertFalse(handle(KEYCODE_T, META_CTRL_ON, readline))
        assertFalse(handle(KEYCODE_W, META_CTRL_ON, readline))
        assertTrue(handle(KEYCODE_T, META_CTRL_ON or META_SHIFT_ON, readline))
        assertTrue(handle(KEYCODE_W, META_CTRL_ON or META_SHIFT_ON, readline))
        assertEquals(listOf("new", "close", "new", "close"), calls)
    }

    @Test
    fun `the first plain Ctrl+W is held back for its hint, once, and never the chord or a Ctrl+W the shell has`() {
        val fresh = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = false))
        // The hint stands in place of the close; the Stage marks it seen, which a later table carries.
        assertTrue(handle(KEYCODE_W, META_CTRL_ON, fresh))
        assertEquals(listOf("hint"), calls)
        assertTrue(handle(KEYCODE_W, META_CTRL_ON, defaults))
        assertEquals(listOf("hint", "close"), calls)
        calls.clear()
        // Nothing live on stage: the Stage declines the hint and the close goes ahead.
        hintStands = false
        assertTrue(handle(KEYCODE_W, META_CTRL_ON, fresh))
        assertEquals(listOf("hint", "close"), calls)
        calls.clear()
        // The chord is deliberate and closes without a hint; with the readline setting on, Ctrl+W is the shell's and nothing is hinted.
        assertTrue(handle(KEYCODE_W, META_CTRL_ON or META_SHIFT_ON, fresh))
        assertFalse(handle(KEYCODE_W, META_CTRL_ON, ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = false), ctrlTabKeysReachTerminal = true)))
        assertEquals(listOf("close"), calls)
    }

    // ---- remaps ------------------------------------------------------------------------------------------

    @Test
    fun `a rebound action answers its new chord and its old one falls to the terminal`() {
        val table = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true).withRemap(ChordAction.FIND, ChordKey("F", ctrl = true, alt = true)))
        assertTrue(handle(KEYCODE_F, META_CTRL_ON or META_ALT_ON, table))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, table))
        // Every other action keeps its default under the same table.
        assertTrue(handle(KEYCODE_C, META_CTRL_ON or META_SHIFT_ON, table))
        assertEquals(listOf("find", "copy"), calls)
    }

    @Test
    fun `a remap onto readline's key takes it from the shell, as the sheet warned`() {
        val table = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true).withRemap(ChordAction.FIND, ChordKey("F", ctrl = true)))
        assertTrue(handle(KEYCODE_F, META_CTRL_ON, table))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, table))
        assertEquals(listOf("find"), calls)
    }

    @Test
    fun `a remap to a function key binds it with no modifier, and a remap onto the plus key finds the keypad's plus too`() {
        val table = ChordTable(
            HardwareKeyboardSettings(ctrlWHintSeen = true)
                .withRemap(ChordAction.TOGGLE_DECK, ChordKey("F5"))
                .withRemap(ChordAction.FONT_LARGER, ChordKey("EQUALS", ctrl = true, shift = true)),
        )
        assertTrue(handle(android.view.KeyEvent.KEYCODE_F5, 0, table))
        assertTrue(handle(KEYCODE_NUMPAD_ADD, META_CTRL_ON, table))
        assertEquals(listOf("deck", "font 1"), calls)
    }

    // ---- the prefixes ----------------------------------------------------------------------------------------

    @Test
    fun `under Meta every chord is Meta's, and the Ctrl+Shift chords fall to the terminal`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.META, ctrlWHintSeen = true))
        assertTrue(handle(KEYCODE_F, META_META_ON, table))
        assertTrue(handle(KEYCODE_T, META_META_ON, table))
        assertTrue(handle(KEYCODE_SLASH, META_META_ON, table))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, table))
        // The strip's browser conventions take no prefix and are untouched.
        assertTrue(handle(KEYCODE_TAB, META_CTRL_ON, table))
        assertTrue(handle(KEYCODE_T, META_CTRL_ON, table))
        assertEquals(listOf("find", "new", "sheet", "step 1", "new"), calls)
    }

    @Test
    fun `under a Leader the key held with the chord is the prefix, and its own bit is not the chord's`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_ALT, ctrlWHintSeen = true))
        // The Leader's own press and release are the app's: swallowed, never the terminal's Alt.
        assertTrue(handle(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_ALT_RIGHT, 0, table, action = ACTION_UP))
        // Ctrl+Shift+F is nobody's now and reaches the terminal; plain Alt+F (the left Alt) is Meta+F, the shell's.
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON, table))
        assertFalse(handle(KEYCODE_F, META_ALT_ON or META_ALT_LEFT_ON, table))
        // A Leader chord nobody bound is still swallowed: the shell must never see half of the app's key.
        assertTrue(handle(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_Q, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_ALT_RIGHT, 0, table, action = ACTION_UP))
        assertEquals(listOf("find"), calls)
    }

    @Test
    fun `a tapped Leader arms the next key, a second tap or Esc lets it go, and a Right Ctrl Leader leaves the left Ctrl to the chord`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_CTRL, ctrlWHintSeen = true))
        // Tap, then F: Leader F.
        assertTrue(handle(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, 0, table, action = ACTION_UP))
        assertTrue(handle(KEYCODE_F, 0, table))
        // Once used the arm is spent: a plain F is typing.
        assertFalse(handle(KEYCODE_F, 0, table))
        // Tap, tap: nothing armed; F is typing.
        assertTrue(handle(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, 0, table, action = ACTION_UP))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, 0, table, action = ACTION_UP))
        assertFalse(handle(KEYCODE_F, 0, table))
        // Tap, Esc: let go, and the Escape is the app's rather than the host's.
        assertTrue(handle(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, 0, table, action = ACTION_UP))
        assertTrue(handle(KEYCODE_ESCAPE, 0, table))
        assertFalse(handle(KEYCODE_F, 0, table))
        // Held with the left Ctrl as well, the chord is Leader Ctrl+F, which is nobody's and swallowed; the plain Ctrl+F stays readline's.
        assertTrue(handle(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_F, META_CTRL_ON or META_CTRL_RIGHT_ON or android.view.KeyEvent.META_CTRL_LEFT_ON, table))
        assertTrue(handle(KEYCODE_CTRL_RIGHT, 0, table, action = ACTION_UP))
        assertFalse(handle(KEYCODE_F, META_CTRL_ON, table))
        assertEquals(listOf("find"), calls)
    }

    // ---- pass-through -------------------------------------------------------------------------------------------

    @Test
    fun `pass-through sends every key to the shell, the strip's and the chords included, until its own chord ends it`() {
        assertTrue(handle(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON))
        assertTrue(passThrough)
        assertFalse(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON))
        assertFalse(handle(KEYCODE_TAB, META_CTRL_ON))
        assertFalse(handle(KEYCODE_T, META_CTRL_ON))
        assertFalse(handle(KEYCODE_ESCAPE, 0))
        assertTrue(handle(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON))
        assertFalse(passThrough)
        assertTrue(handle(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON))
        assertEquals(listOf("pass-through on", "pass-through off", "find"), calls)
    }

    @Test
    fun `in pass-through under a Leader, the Leader's own keys reach the shell too, but Leader P still ends it`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_ALT, ctrlWHintSeen = true))
        assertTrue(handle(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_P, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(passThrough)
        // From here every key is the shell's, the Leader's own release included (a release is nothing to a terminal).
        assertFalse(handle(KEYCODE_ALT_RIGHT, 0, table, action = ACTION_UP))
        // The Right Alt is the keyboard's Alt again while every key is the shell's.
        assertFalse(handle(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertFalse(handle(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertTrue(handle(KEYCODE_P, META_ALT_ON or META_ALT_RIGHT_ON, table))
        assertFalse(passThrough)
        // And the Leader is the app's again the moment pass-through ends.
        assertTrue(handle(KEYCODE_ALT_RIGHT, 0, table, action = ACTION_UP))
        assertEquals(listOf("pass-through on", "pass-through off"), calls)
    }

    @Test
    fun `pass-through rebound follows its remap`() {
        val table = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true).withRemap(ChordAction.PASS_THROUGH, ChordKey("F12")))
        assertTrue(handle(android.view.KeyEvent.KEYCODE_F12, 0, table))
        assertTrue(passThrough)
        assertFalse(handle(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON, table))
        assertTrue(handle(android.view.KeyEvent.KEYCODE_F12, 0, table))
        assertFalse(passThrough)
    }

    // ---- the sheet's rows -----------------------------------------------------------------------------------------

    @Test
    fun `the sheet lists the focus chords beside the Stage's others`() {
        val stage = shortcutGroups(defaults).first { it.title == "Stage" }.entries.map { it.keys }
        assertTrue("Ctrl+Shift+S" in stage && "Ctrl+Shift+K" in stage && "Esc" in stage && "Ctrl+Shift+P" in stage)
    }

    @Test
    fun `the sheet's pane rows say when they wait for a wider screen`() {
        val phone = shortcutGroups(defaults, panes = false).first { it.title == "Panes" }.entries
        val tablet = shortcutGroups(defaults, panes = true).first { it.title == "Panes" }.entries
        assertEquals(listOf("Ctrl+Shift+D", "Ctrl+Shift+O"), phone.map { it.keys })
        assertEquals(phone.map { it.keys }, tablet.map { it.keys })
        assertTrue(phone.all { "wide screen" in it.action || "when the Stage is split" in it.action })
        assertEquals(listOf("Split the Stage in two, or back to one", "Focus the other pane"), tablet.map { it.action })
    }

    @Test
    fun `the sheet lists the readline keys on whichever side the setting puts them`() {
        val app = shortcutGroups(defaults)
        val shell = shortcutGroups(readline)
        assertEquals(listOf("Tabs", "Stage", "Panes", "Terminal"), app.map { it.title })
        val appKeys = app.flatMap { it.entries }.map { it.keys }
        val shellKeys = shell.flatMap { it.entries }.map { it.keys }
        // The plain key beside its chord on one row while the strip has it; the chord alone once the shell does.
        assertTrue("Ctrl+T${CHORD_SEPARATOR}Ctrl+Shift+T" in appKeys && "Ctrl+W${CHORD_SEPARATOR}Ctrl+Shift+W" in appKeys)
        assertTrue("Ctrl+Shift+T" in shellKeys && "Ctrl+Shift+W" in shellKeys)
        assertFalse(shellKeys.any { it.startsWith("Ctrl+T$CHORD_SEPARATOR") || it.startsWith("Ctrl+W$CHORD_SEPARATOR") })
        // The search is the Shift chord on both sides, and the plain Ctrl+F is never listed as the app's.
        assertTrue("Ctrl+Shift+F" in appKeys && "Ctrl+Shift+F" in shellKeys)
        assertFalse("Ctrl+F" in appKeys || "Ctrl+F" in shellKeys)
        assertTrue(shell.last().entries.any { it.keys == "Ctrl+T, Ctrl+W" })
        assertFalse(app.last().entries.any { it.keys == "Ctrl+T, Ctrl+W" })
    }

    @Test
    fun `a rebound row shows its chord, says what it was and what the shell lost, and a fixed row has no chord to rebind`() {
        val table = ChordTable(HardwareKeyboardSettings().withRemap(ChordAction.FIND, ChordKey("F", ctrl = true)))
        val rows = shortcutGroups(table).flatMap { it.entries }
        val find = rows.first { it.chord == ChordAction.FIND }
        assertEquals("Ctrl+F", find.keys)
        assertTrue(find.remapped)
        assertEquals("Ctrl+Shift+F", find.default)
        assertEquals("readline\u2019s forward-char", find.takes)
        val copy = rows.first { it.chord == ChordAction.COPY }
        assertFalse(copy.remapped)
        assertEquals(null, copy.takes)
        assertTrue(rows.filter { it.chord == null }.all { !it.remapped && it.default == null })
        assertEquals(ChordAction.entries.size, rows.count { it.chord != null })
    }

    @Test
    fun `the sheet's rows follow the prefix, and its notes name the Leader`() {
        val meta = shortcutGroups(ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.META)))
        assertEquals("Meta+F", meta.flatMap { it.entries }.first { it.chord == ChordAction.FIND }.keys)
        assertEquals("Ctrl+T${CHORD_SEPARATOR}Meta+T", meta.flatMap { it.entries }.first { it.chord == ChordAction.NEW_TAB }.keys)
        val leader = shortcutGroups(ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_CTRL)))
        assertEquals("Leader F", leader.flatMap { it.entries }.first { it.chord == ChordAction.FIND }.keys)
        assertTrue(leader.last().note!!.contains("Right Ctrl"))
        assertFalse(meta.last().note!!.contains("Leader"))
    }
}

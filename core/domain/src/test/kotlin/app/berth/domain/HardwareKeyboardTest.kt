package app.berth.domain

import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordConflict
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.LeaderKey
import app.berth.domain.model.VolumeButtons
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chord table of spec C22: what each prefix makes of the app's chords, how a remap replaces one,
 * and what the table refuses or warns about when a chord is the shell's, the strip's or another
 * action's. Pure model, no keyboard: the Android dispatcher's own tests drive it with key events.
 */
class HardwareKeyboardTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `the default prefix is Ctrl+Shift and every action has its key under it`() {
        val table = ChordTable(HardwareKeyboardSettings())
        assertEquals(ChordKey("F", ctrl = true, shift = true), table.chord(ChordAction.FIND))
        assertEquals(ChordKey("SLASH", ctrl = true, shift = true), table.chord(ChordAction.SHORTCUT_SHEET))
        assertEquals(ChordKey("P", ctrl = true, shift = true), table.chord(ChordAction.PASS_THROUGH))
        assertEquals("Ctrl+Shift+F", table.chord(ChordAction.FIND).label())
        assertEquals("Ctrl+Shift+/", table.chord(ChordAction.SHORTCUT_SHEET).label())
        assertEquals("Ctrl+Shift+=", table.chord(ChordAction.FONT_LARGER).label())
        assertEquals("Ctrl+Shift+\u2212", table.chord(ChordAction.FONT_SMALLER).label())
        // Spec C22's "[ / ] Previous / next group", on the brackets themselves.
        assertEquals("Ctrl+Shift+[", table.chord(ChordAction.PREVIOUS_GROUP).label())
        assertEquals("Ctrl+Shift+]", table.chord(ChordAction.NEXT_GROUP).label())
        // Eighteen actions, eighteen distinct chords: no two defaults collide.
        assertEquals(ChordAction.entries.size, table.chords.values.toSet().size)
        for (action in ChordAction.entries) assertEquals(action, table.actionFor(table.chord(action)))
    }

    @Test
    fun `the Leader prefix moves every chord at once, and the strip's Ctrl keys are not its to move`() {
        // Two prefixes: Meta is Android's (Assist, Recents, an app per letter, a different set each release) and is not offered.
        assertEquals(listOf(ChordPrefix.CTRL_SHIFT, ChordPrefix.LEADER), ChordPrefix.entries.toList())
        val leader = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER))
        assertEquals(ChordKey("T", leader = true), leader.chord(ChordAction.NEW_TAB))
        assertEquals("Leader T", leader.chord(ChordAction.NEW_TAB).label())
        assertEquals(ChordAction.NEW_TAB, leader.actionFor(ChordKey("T", leader = true)))
        assertEquals(ChordAction.NEXT_GROUP, leader.actionFor(ChordKey("RIGHT_BRACKET", leader = true)))
        assertNull(leader.actionFor(ChordKey("F", ctrl = true, shift = true)), "Ctrl+Shift+F is nobody's under the Leader")
        // The browser conventions take no prefix, so the table never lists them; the strip keeps them whatever the prefix.
        assertNull(leader.actionFor(ChordKey("TAB", ctrl = true)))
    }

    @Test
    fun `the Leader is Right Ctrl unless chosen otherwise, since Right Alt is AltGr on most layouts`() {
        assertEquals(LeaderKey.RIGHT_CTRL, HardwareKeyboardSettings().leaderKey)
        assertEquals(LeaderKey.RIGHT_CTRL, HardwareKeyboardSettings().withChordPrefix(ChordPrefix.LEADER).leaderKey)
        // Offered second, as the choice for a layout with no AltGr.
        assertEquals(listOf(LeaderKey.RIGHT_CTRL, LeaderKey.RIGHT_ALT), LeaderKey.entries.toList())
    }

    @Test
    fun `Android's chords are refused, Alt+Tab and Meta+Tab as Recents and every Meta chord as Meta's`() {
        val table = ChordTable(HardwareKeyboardSettings())
        assertEquals(ChordConflict.System("Recents"), table.conflict(ChordAction.FIND, ChordKey("TAB", alt = true)))
        assertEquals(ChordConflict.System("Recents"), table.conflict(ChordAction.FIND, ChordKey("TAB", alt = true, shift = true)))
        assertEquals(ChordConflict.System("Recents"), table.conflict(ChordAction.FIND, ChordKey("TAB", meta = true)))
        assertEquals(ChordConflict.System("Recents"), table.conflict(ChordAction.FIND, ChordKey("TAB", meta = true, ctrl = true)))
        // Ctrl+Alt+Tab is not the system's; it is the shell's Tab under modifiers.
        assertEquals(ChordConflict.Shell("Meta and Ctrl+Tab"), table.conflict(ChordAction.FIND, ChordKey("TAB", ctrl = true, alt = true)))
        // Meta and anything: the system reads it first, and which letters has changed with each release, so none is promised.
        for (key in listOf("A", "F", "N", "SLASH", "SPACE", "ENTER", "BACKSPACE", "F5")) {
            val conflict = table.conflict(ChordAction.FIND, ChordKey(key, meta = true))
            assertEquals(ChordConflict.System("the system reads Meta before any app"), conflict, "Meta+$key")
            assertTrue(conflict!!.blocks)
        }
        assertIs<ChordConflict.System>(table.conflict(ChordAction.FIND, ChordKey("F", meta = true, ctrl = true, shift = true)))
        // A Right Alt Leader held with Tab is Alt+Tab to the system; a Right Ctrl Leader with Tab is the app's to take.
        val rightAlt = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_ALT))
        val rightCtrl = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_CTRL))
        assertEquals(ChordConflict.System("Recents"), rightAlt.conflict(ChordAction.FIND, ChordKey("TAB", leader = true)))
        assertEquals(ChordConflict.System("Recents"), rightAlt.conflict(ChordAction.FIND, ChordKey("TAB", leader = true, shift = true)))
        assertNull(rightCtrl.conflict(ChordAction.FIND, ChordKey("TAB", leader = true)))
        // No default is one of them: every action's chord under either prefix is free of the system.
        for (prefix in ChordPrefix.entries) {
            val defaults = ChordTable(HardwareKeyboardSettings(chordPrefix = prefix, leaderKey = LeaderKey.RIGHT_ALT))
            for (action in ChordAction.entries) assertNull(defaults.conflict(action, defaults.chord(action)), "${defaults.chord(action).label()} under $prefix")
        }
    }

    @Test
    fun `a remap is a whole chord in the prefix's place, and the default comes back on null or on the default itself`() {
        val settings = HardwareKeyboardSettings().withRemap(ChordAction.FIND, ChordKey("N", ctrl = true))
        val table = ChordTable(settings)
        assertEquals(ChordKey("N", ctrl = true), table.chord(ChordAction.FIND))
        assertEquals(ChordAction.FIND, table.actionFor(ChordKey("N", ctrl = true)))
        assertNull(table.actionFor(ChordKey("F", ctrl = true, shift = true)), "the old chord is free")
        assertTrue(table.isRemapped(ChordAction.FIND))
        assertFalse(table.isRemapped(ChordAction.COPY))
        assertEquals(HardwareKeyboardSettings(), settings.withRemap(ChordAction.FIND, null))
        assertEquals(HardwareKeyboardSettings(), settings.withRemap(ChordAction.FIND, ChordKey("F", ctrl = true, shift = true)))
    }

    @Test
    fun `a bare key is typing, another action's chord and the strip's are refused, a signal is refused`() {
        val table = ChordTable(HardwareKeyboardSettings())
        assertEquals(ChordConflict.Typing, table.conflict(ChordAction.FIND, ChordKey("N")))
        assertEquals(ChordConflict.Typing, table.conflict(ChordAction.FIND, ChordKey("N", shift = true)))
        assertEquals(ChordConflict.Taken(ChordAction.COPY), table.conflict(ChordAction.FIND, ChordKey("C", ctrl = true, shift = true)))
        // An action's own chord is no conflict with itself.
        assertNull(table.conflict(ChordAction.FIND, ChordKey("F", ctrl = true, shift = true)))
        assertEquals(ChordConflict.Strip("Next tab"), table.conflict(ChordAction.FIND, ChordKey("TAB", ctrl = true)))
        assertEquals(ChordConflict.Strip("Previous tab"), table.conflict(ChordAction.FIND, ChordKey("TAB", ctrl = true, shift = true)))
        assertEquals(ChordConflict.Strip("Tab 3"), table.conflict(ChordAction.FIND, ChordKey("3", ctrl = true)))
        assertEquals(ChordConflict.Strip("Last tab"), table.conflict(ChordAction.FIND, ChordKey("9", ctrl = true)))
        assertEquals(ChordConflict.Strip("Close tab"), table.conflict(ChordAction.FIND, ChordKey("W", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell\u2019s interrupt"), table.conflict(ChordAction.FIND, ChordKey("C", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell\u2019s end-of-file"), table.conflict(ChordAction.FIND, ChordKey("D", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell\u2019s suspend"), table.conflict(ChordAction.FIND, ChordKey("Z", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell\u2019s quit"), table.conflict(ChordAction.FIND, ChordKey("BACKSLASH", ctrl = true)))
        for (conflict in listOf(ChordConflict.Typing, ChordConflict.Taken(ChordAction.COPY), ChordConflict.Strip("Next tab"), ChordConflict.Signal("x"))) {
            assertTrue(conflict.blocks, "$conflict blocks")
        }
    }

    @Test
    fun `the shell's own keys are named and allowed, and the free chords are free`() {
        val table = ChordTable(HardwareKeyboardSettings())
        val forwardChar = table.conflict(ChordAction.FIND, ChordKey("F", ctrl = true))
        assertEquals(ChordConflict.Shell("readline\u2019s forward-char"), forwardChar)
        assertFalse(forwardChar!!.blocks)
        assertEquals(ChordConflict.Shell("readline\u2019s set-mark"), table.conflict(ChordAction.FIND, ChordKey("SPACE", ctrl = true)))
        // Noun phrases, so the sheet can say "takes Meta+F from the shell".
        assertEquals(ChordConflict.Shell("Meta+F"), table.conflict(ChordAction.FIND, ChordKey("F", alt = true)))
        assertEquals(ChordConflict.Shell("Meta and Ctrl+F"), table.conflict(ChordAction.FIND, ChordKey("F", ctrl = true, alt = true)))
        assertEquals(ChordConflict.Shell("F5"), table.conflict(ChordAction.FIND, ChordKey("F5")))
        assertEquals(ChordConflict.Shell("Ctrl+\u2191"), table.conflict(ChordAction.FIND, ChordKey("UP", ctrl = true)))
        assertEquals(ChordConflict.Shell("Ctrl+Shift+\u2191"), table.conflict(ChordAction.FIND, ChordKey("UP", ctrl = true, shift = true)))
        // Ctrl+Shift and a letter is the app's own family; the Leader is the app's by choice.
        assertNull(table.conflict(ChordAction.FIND, ChordKey("N", ctrl = true, shift = true)))
        assertNull(ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER)).conflict(ChordAction.FIND, ChordKey("N", leader = true)))
    }

    @Test
    fun `the terminal's own control characters are named as the terminal's, not readline's`() {
        val table = ChordTable(HardwareKeyboardSettings())
        // On the wire these are Enter, Tab, backspace and Escape, and the tty's flow control; no readline binding is involved.
        assertEquals(ChordConflict.Shell("the terminal\u2019s Enter"), table.conflict(ChordAction.FIND, ChordKey("M", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s Enter"), table.conflict(ChordAction.FIND, ChordKey("J", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s Tab"), table.conflict(ChordAction.FIND, ChordKey("I", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s backspace"), table.conflict(ChordAction.FIND, ChordKey("H", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s Escape"), table.conflict(ChordAction.FIND, ChordKey("LEFT_BRACKET", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s flow control, stop output"), table.conflict(ChordAction.FIND, ChordKey("S", ctrl = true)))
        assertEquals(ChordConflict.Shell("the terminal\u2019s flow control, resume output"), table.conflict(ChordAction.FIND, ChordKey("Q", ctrl = true)))
        // Readline's stay readline's.
        assertEquals(ChordConflict.Shell("readline\u2019s kill-line"), table.conflict(ChordAction.FIND, ChordKey("K", ctrl = true)))
        assertEquals(ChordConflict.Shell("readline\u2019s character-search"), table.conflict(ChordAction.FIND, ChordKey("RIGHT_BRACKET", ctrl = true)))
    }

    @Test
    fun `Ctrl+T and Ctrl+W are the strip's until the readline setting hands them to the shell, then readline's`() {
        val strip = ChordTable(HardwareKeyboardSettings(), ctrlTabKeysReachTerminal = false)
        val shell = ChordTable(HardwareKeyboardSettings(), ctrlTabKeysReachTerminal = true)
        assertEquals(ChordConflict.Strip("New tab"), strip.conflict(ChordAction.FIND, ChordKey("T", ctrl = true)))
        assertEquals(ChordConflict.Shell("readline\u2019s transpose-chars"), shell.conflict(ChordAction.FIND, ChordKey("T", ctrl = true)))
        assertEquals(ChordConflict.Shell("readline\u2019s unix-word-rubout"), shell.conflict(ChordAction.FIND, ChordKey("W", ctrl = true)))
    }

    @Test
    fun `leaving the Leader prefix drops the Leader chords among the remaps and keeps the rest`() {
        val settings = HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER)
            .withRemap(ChordAction.FIND, ChordKey("N", leader = true))
            .withRemap(ChordAction.COPY, ChordKey("Y", ctrl = true, shift = true))
        val back = settings.withChordPrefix(ChordPrefix.CTRL_SHIFT)
        assertEquals(mapOf(ChordAction.COPY to ChordKey("Y", ctrl = true, shift = true)), back.remaps)
        assertEquals(ChordKey("F", ctrl = true, shift = true), back.chordFor(ChordAction.FIND))
        assertEquals(settings.remaps, settings.withChordPrefix(ChordPrefix.LEADER).remaps)
    }

    @Test
    fun `a prefix change drops a remap that lands on another action's new default, so no two rows share a chord`() {
        // Under the Leader, Ctrl+Shift+A is free and Copy takes it; back under Ctrl+Shift it is the tab switcher's default.
        val settings = HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER)
            .withRemap(ChordAction.COPY, ChordKey("A", ctrl = true, shift = true))
            .withRemap(ChordAction.FIND, ChordKey("N", ctrl = true))
        assertNull(ChordTable(settings).conflict(ChordAction.COPY, ChordKey("A", ctrl = true, shift = true)))
        val back = settings.withChordPrefix(ChordPrefix.CTRL_SHIFT)
        assertEquals(mapOf(ChordAction.FIND to ChordKey("N", ctrl = true)), back.remaps)
        val table = ChordTable(back)
        assertEquals(ChordAction.TAB_SWITCHER, table.actionFor(ChordKey("A", ctrl = true, shift = true)))
        assertEquals(ChordAction.entries.size, table.chords.values.toSet().size, "one chord per row")
        // The other way too: a Ctrl+Shift remap that is some action's Leader default cannot exist, but a whole chord equal to none stays.
        val leader = back.withChordPrefix(ChordPrefix.LEADER)
        assertEquals(back.remaps, leader.remaps)
    }

    @Test
    fun `the settings document round-trips with its remaps, and a document from before these fields reads with their defaults`() {
        val settings = HardwareKeyboardSettings(
            chordPrefix = ChordPrefix.LEADER,
            leaderKey = LeaderKey.RIGHT_ALT,
            remaps = mapOf(ChordAction.FIND to ChordKey("N", ctrl = true), ChordAction.SPLIT to ChordKey("BACKSLASH", leader = true)),
            ctrlWHintSeen = true,
            volumeButtons = VolumeButtons.PAGES,
        )
        val text = json.encodeToString(HardwareKeyboardSettings.serializer(), settings)
        assertTrue(""""remaps":{"FIND":{"key":"N","ctrl":true""" in text, text)
        assertEquals(settings, json.decodeFromString(HardwareKeyboardSettings.serializer(), text))
        // What #15 wrote: the three fields it knew, nothing else.
        val old = """{"altKey":"META","altKeyByHost":{"homelab":"ESC_PREFIX"},"compactDeck":false}"""
        val read = json.decodeFromString(HardwareKeyboardSettings.serializer(), old)
        assertEquals(ChordPrefix.CTRL_SHIFT, read.chordPrefix)
        assertEquals(LeaderKey.RIGHT_CTRL, read.leaderKey)
        assertTrue(read.remaps.isEmpty())
        assertFalse(read.ctrlWHintSeen)
        assertEquals(VolumeButtons.OFF, read.volumeButtons)
        assertFalse(read.compactDeck)
    }

    @Test
    fun `key names are letters, digits and the named keys, each with its label`() {
        assertTrue(ChordKey.isKey("A") && ChordKey.isKey("9") && ChordKey.isKey("SLASH") && ChordKey.isKey("F12"))
        assertFalse(ChordKey.isKey("a") || ChordKey.isKey("VOLUME_UP") || ChordKey.isKey("") || ChordKey.isKey("F13"))
        assertEquals("Ctrl+Alt+Shift+Meta+Space", ChordKey("SPACE", ctrl = true, shift = true, alt = true, meta = true).label())
        assertEquals("Leader Ctrl+\u2190", ChordKey("LEFT", ctrl = true, leader = true).label())
        assertIs<ChordConflict.Shell>(ChordTable(HardwareKeyboardSettings()).conflict(ChordAction.FIND, ChordKey("F1", shift = true)))
    }
}

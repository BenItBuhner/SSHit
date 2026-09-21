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
        // Sixteen actions, sixteen distinct chords: no two defaults collide.
        assertEquals(ChordAction.entries.size, table.chords.values.toSet().size)
        for (action in ChordAction.entries) assertEquals(action, table.actionFor(table.chord(action)))
    }

    @Test
    fun `the Meta and Leader prefixes move every chord at once, and the strip's Ctrl keys are not theirs to move`() {
        val meta = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.META))
        assertEquals(ChordKey("F", meta = true), meta.chord(ChordAction.FIND))
        assertEquals("Meta+F", meta.chord(ChordAction.FIND).label())
        assertNull(meta.actionFor(ChordKey("F", ctrl = true, shift = true)), "Ctrl+Shift+F is nobody's under Meta")
        val leader = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_CTRL))
        assertEquals(ChordKey("T", leader = true), leader.chord(ChordAction.NEW_TAB))
        assertEquals("Leader T", leader.chord(ChordAction.NEW_TAB).label())
        assertEquals(ChordAction.NEW_TAB, leader.actionFor(ChordKey("T", leader = true)))
        // The browser conventions take no prefix, so the table never lists them; the strip keeps them whatever the prefix.
        for (table in listOf(meta, leader)) assertNull(table.actionFor(ChordKey("TAB", ctrl = true)))
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
        assertEquals(ChordConflict.Signal("the shell's interrupt"), table.conflict(ChordAction.FIND, ChordKey("C", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell's end-of-file"), table.conflict(ChordAction.FIND, ChordKey("D", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell's suspend"), table.conflict(ChordAction.FIND, ChordKey("Z", ctrl = true)))
        assertEquals(ChordConflict.Signal("the shell's quit"), table.conflict(ChordAction.FIND, ChordKey("BACKSLASH", ctrl = true)))
        for (conflict in listOf(ChordConflict.Typing, ChordConflict.Taken(ChordAction.COPY), ChordConflict.Strip("Next tab"), ChordConflict.Signal("x"))) {
            assertTrue(conflict.blocks, "$conflict blocks")
        }
    }

    @Test
    fun `the shell's own keys are named and allowed, and the free chords are free`() {
        val table = ChordTable(HardwareKeyboardSettings())
        val forwardChar = table.conflict(ChordAction.FIND, ChordKey("F", ctrl = true))
        assertEquals(ChordConflict.Shell("readline's forward-char"), forwardChar)
        assertFalse(forwardChar!!.blocks)
        assertEquals(ChordConflict.Shell("readline's set-mark"), table.conflict(ChordAction.FIND, ChordKey("SPACE", ctrl = true)))
        assertEquals(ChordConflict.Shell("Meta+F to the shell"), table.conflict(ChordAction.FIND, ChordKey("F", alt = true)))
        assertEquals(ChordConflict.Shell("Meta and a control character to the shell"), table.conflict(ChordAction.FIND, ChordKey("F", ctrl = true, alt = true)))
        assertEquals(ChordConflict.Shell("the host's F5"), table.conflict(ChordAction.FIND, ChordKey("F5")))
        assertEquals(ChordConflict.Shell("the shell's Ctrl+\u2191"), table.conflict(ChordAction.FIND, ChordKey("UP", ctrl = true)))
        assertEquals(ChordConflict.Shell("the shell's Ctrl+Shift+\u2191"), table.conflict(ChordAction.FIND, ChordKey("UP", ctrl = true, shift = true)))
        // Ctrl+Shift and a letter is the app's own family; Meta is the keyboard's spare key; the Leader is the app's by choice.
        assertNull(table.conflict(ChordAction.FIND, ChordKey("N", ctrl = true, shift = true)))
        assertNull(table.conflict(ChordAction.FIND, ChordKey("N", meta = true)))
        assertNull(ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER)).conflict(ChordAction.FIND, ChordKey("N", leader = true)))
    }

    @Test
    fun `Ctrl+T and Ctrl+W are the strip's until the readline setting hands them to the shell, then readline's`() {
        val strip = ChordTable(HardwareKeyboardSettings(), ctrlTabKeysReachTerminal = false)
        val shell = ChordTable(HardwareKeyboardSettings(), ctrlTabKeysReachTerminal = true)
        assertEquals(ChordConflict.Strip("New tab"), strip.conflict(ChordAction.FIND, ChordKey("T", ctrl = true)))
        assertEquals(ChordConflict.Shell("readline's transpose-chars"), shell.conflict(ChordAction.FIND, ChordKey("T", ctrl = true)))
        assertEquals(ChordConflict.Shell("readline's unix-word-rubout"), shell.conflict(ChordAction.FIND, ChordKey("W", ctrl = true)))
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
    fun `the settings document round-trips with its remaps, and a document from before these fields reads with their defaults`() {
        val settings = HardwareKeyboardSettings(
            chordPrefix = ChordPrefix.LEADER,
            leaderKey = LeaderKey.RIGHT_CTRL,
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
        assertEquals(LeaderKey.RIGHT_ALT, read.leaderKey)
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

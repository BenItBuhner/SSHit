package app.berth.android.ui.terminal

import android.view.KeyCharacterMap
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_3
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_8
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_ALT_RIGHT
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_CTRL_RIGHT
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_M
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_CTRL_RIGHT_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.utf16CodePoint
import app.berth.android.ui.keyboard.HardwareShortcuts
import app.berth.android.ui.keyboard.KeyLayout
import app.berth.android.ui.keyboard.ShadowKeyLayout
import app.berth.android.ui.keyboard.StageShortcutActions
import app.berth.android.ui.keyboard.layoutTypesWithRightAlt
import app.berth.android.ui.tabs.TabShortcuts
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.LeaderKey
import app.berth.terminal.Mod
import app.berth.terminal.TerminalKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hardware keys reaching the terminal through [handleComposeKeyEvent] on a layout with a third
 * level: Right Alt is AltGr on a German and on a Nordic keyboard, so AltGr+Q is `@` typed on the one
 * and AltGr+2 on the other, not Meta+Q or Meta+2, while Left Alt is Meta on every layout and Right
 * Alt is Meta too on a key with nothing under it, so a US keyboard's Right Alt reaches the terminal
 * as Alt as it did. The layouts are the platform's own `.kcm` rows, answering through
 * `KeyCharacterMap` itself ([ShadowKeyLayout]), so Compose's `utf16CodePoint` says what the
 * device's would, and the Settings row's probe ([layoutTypesWithRightAlt]) reads the same map by
 * the same rule. And beside AltGr the Stage's Leader on Right Ctrl fires as it did (spec C22), a key
 * under it the Leader's whether it is held or tapped, where one chosen on Right Alt takes the third
 * level with it, the cost the Settings row names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowKeyLayout::class])
class TerminalInputAltGrTest {
    private class RecordingSink : ModifierAwareSink {
        val sent = ArrayList<String>()
        override fun onText(text: String) { sent += "text:$text" }
        override fun onKey(key: TerminalKey, modifiers: Int) { sent += "key:$key" + modifiers.named() }
        override fun onCodePoint(codePoint: Int, modifiers: Int) { sent += "chord:" + modifiers.named().removePrefix("+") + "+" + String(Character.toChars(codePoint)) }

        private fun Int.named(): String = buildString {
            if (this@named and Mod.CTRL != 0) append("+Ctrl")
            if (this@named and Mod.ALT != 0) append("+Alt")
            if (this@named and Mod.META != 0) append("+Meta")
            if (this@named and Mod.SHIFT != 0) append("+Shift")
        }
    }

    private val sink = RecordingSink()

    /** The Stage's calls: the sheet, the search and Copy, the chords pressed here. */
    private val calls = ArrayList<String>()
    private val shortcuts = HardwareShortcuts(
        tabs = TabShortcuts(step = {}, jump = {}),
        stage = object : StageShortcutActions {
            override fun newTab() {}
            override fun closeTab() {}
            override fun tabSwitcher() {}
            override fun jumpToUnread() {}
            override fun find() { calls += "find" }
            override fun copy() { calls += "copy" }
            override fun paste() {}
            override fun toggleDeck() {}
            override fun fontStep(step: Int) {}
            override fun shortcutSheet() { calls += "sheet" }
            override fun split() {}
            override fun focusOtherPane() {}
            override fun focusStrip() {}
            override fun focusDeck() {}
            override fun returnToBody(): Boolean = false
            override var passThrough: Boolean = false
            override fun ctrlWHint(): Boolean = false
        },
    )

    private val altGr = META_ALT_ON or META_ALT_RIGHT_ON
    private val leftAlt = META_ALT_ON or META_ALT_LEFT_ON
    private val shift = META_SHIFT_ON or META_SHIFT_LEFT_ON
    private val rightCtrl = META_CTRL_ON or META_CTRL_RIGHT_ON

    @Before
    fun german() {
        ShadowKeyLayout.layout = KeyLayout.GERMAN
    }

    @After
    fun us() {
        ShadowKeyLayout.layout = KeyLayout.US
    }

    private fun key(code: Int, meta: Int, action: Int = ACTION_DOWN) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    private fun type(code: Int, meta: Int, action: Int = ACTION_DOWN) = handleComposeKeyEvent(key(code, meta, action), sink)

    /** The Stage's order: its chords read the key first, and what they leave reaches the terminal. */
    private fun stage(table: ChordTable, code: Int, meta: Int, action: Int = ACTION_DOWN) {
        val event = key(code, meta, action)
        if (!shortcuts.handle(event, table)) handleComposeKeyEvent(event, sink)
    }

    @Test
    fun `on a German layout Right Alt is AltGr, and the character it reaches is typed as it is`() {
        // The fixture stands where the device's map does: the Compose event itself says `@`.
        assertEquals('@'.code, key(KEYCODE_Q, altGr).utf16CodePoint)
        // Right Alt's own press is a modifier, not input.
        assertFalse(type(KEYCODE_ALT_RIGHT, altGr))
        type(KEYCODE_Q, altGr)
        type(KEYCODE_7, altGr)
        type(KEYCODE_8, altGr)
        type(KEYCODE_9, altGr)
        type(KEYCODE_0, altGr)
        type(KEYCODE_SLASH, altGr) // ß
        type(KEYCODE_SLASH, altGr or shift) // ẞ, the layout's own `shift+ralt` row
        type(KEYCODE_PLUS, altGr) // the < key beside the left Shift
        type(KEYCODE_RIGHT_BRACKET, altGr) // the + key
        type(KEYCODE_E, altGr)
        assertEquals(
            listOf("text:@", "text:{", "text:[", "text:]", "text:}", "text:\\", "text:\u1E9E", "text:|", "text:~", "text:\u20AC"),
            sink.sent,
        )
    }

    @Test
    fun `on a Nordic layout, the Swedish and Finnish file, @ is on 2 and the brackets on the digits, a letter's third level types too, and a dead key under AltGr stays a dead key`() {
        ShadowKeyLayout.layout = KeyLayout.NORDIC
        assertEquals('@'.code, key(KEYCODE_2, altGr).utf16CodePoint)
        type(KEYCODE_2, altGr)
        type(KEYCODE_3, altGr) // £
        type(KEYCODE_4, altGr) // $
        type(KEYCODE_5, altGr) // €
        type(KEYCODE_7, altGr)
        type(KEYCODE_8, altGr)
        type(KEYCODE_9, altGr)
        type(KEYCODE_0, altGr)
        type(KEYCODE_MINUS, altGr) // the + key: \
        type(KEYCODE_PLUS, altGr) // the < key beside the left Shift: |
        type(KEYCODE_E, altGr)
        type(KEYCODE_M, altGr) // µ
        // Q, `@` on the German layout, is â here, Â with Shift: a letter's third level is the layout's too.
        type(KEYCODE_Q, altGr)
        type(KEYCODE_Q, altGr or shift)
        type(KEYCODE_SEMICOLON, altGr) // ø on the ö key
        // The dead tilde on the ¨ key is a dead key still, left to the keyboard to compose, as a dead key under Alt was before.
        assertFalse(type(KEYCODE_RIGHT_BRACKET, altGr))
        // And Left Alt is Meta on the 2 that has `@` under Right Alt.
        type(KEYCODE_2, leftAlt)
        assertEquals(
            listOf(
                "text:@", "text:\u00A3", "text:\$", "text:\u20AC", "text:{", "text:[", "text:]", "text:}", "text:\\", "text:|",
                "text:\u20AC", "text:\u00B5", "text:\u00E2", "text:\u00C2", "text:\u00F8", "chord:Alt+2",
            ),
            sink.sent,
        )
    }

    @Test
    fun `the Settings row's probe and the terminal read one rule, so a German and a Nordic keyboard type with Right Alt and their third level types, and a US keyboard's Right Alt reaches the terminal as Alt`() {
        // The probe as the Settings row asks it of a keyboard, through the platform's own map.
        fun keyboardTypesWithRightAlt(): Boolean = layoutTypesWithRightAlt(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)::get)
        for ((layout, atKey) in listOf(KeyLayout.GERMAN to KEYCODE_Q, KeyLayout.NORDIC to KEYCODE_2)) {
            ShadowKeyLayout.layout = layout
            assertTrue("$layout types with Right Alt", keyboardTypesWithRightAlt())
            sink.sent.clear()
            type(atKey, altGr)
            type(KEYCODE_7, altGr)
            type(KEYCODE_PLUS, altGr)
            assertEquals("$layout", listOf("text:@", "text:{", "text:|"), sink.sent)
        }
        ShadowKeyLayout.layout = KeyLayout.US
        assertFalse("a US keyboard does not type with Right Alt", keyboardTypesWithRightAlt())
        sink.sent.clear()
        type(KEYCODE_Q, altGr)
        type(KEYCODE_2, altGr)
        type(KEYCODE_7, altGr)
        type(KEYCODE_C, altGr) // ç under either Alt on Generic.kcm: Meta+c to a terminal still
        assertEquals(listOf("chord:Alt+q", "chord:Alt+2", "chord:Alt+7", "chord:Alt+c"), sink.sent)
    }

    @Test
    fun `Left Alt is Meta on every layout, and so is Right Alt on a key with nothing under it, and Ctrl wins over AltGr`() {
        type(KEYCODE_Q, 0)
        type(KEYCODE_Q, leftAlt)
        type(KEYCODE_Q, leftAlt or shift)
        // A, with no `ralt` row: Right Alt is the chord's Alt there, as it was.
        type(KEYCODE_A, altGr)
        type(KEYCODE_Q, META_CTRL_ON or META_CTRL_LEFT_ON)
        type(KEYCODE_Q, META_CTRL_ON or META_CTRL_LEFT_ON or altGr)
        // A key of the terminal's own goes by its key code, its modifiers with it.
        type(KEYCODE_ENTER, altGr)
        assertEquals(
            listOf("text:q", "chord:Alt+q", "chord:Alt+Shift+Q", "chord:Alt+a", "chord:Ctrl+q", "chord:Ctrl+Alt+q", "key:ENTER+Alt"),
            sink.sent,
        )
    }

    @Test
    fun `on the US layout nothing is a third level, so Right Alt is Meta as before, on C though the map has ç under either Alt and on E with its dead accent`() {
        ShadowKeyLayout.layout = KeyLayout.US
        // The map's word for Right Alt+C is ç, a Mac's Option+C; a terminal's Alt+C is Meta+C whichever Alt is held.
        assertEquals('\u00E7'.code, key(KEYCODE_C, altGr).utf16CodePoint)
        type(KEYCODE_C, 0)
        type(KEYCODE_C, altGr)
        type(KEYCODE_C, leftAlt)
        type(KEYCODE_Q, altGr)
        type(KEYCODE_E, altGr)
        assertEquals(listOf("text:c", "chord:Alt+c", "chord:Alt+c", "chord:Alt+q", "chord:Alt+e"), sink.sent)
    }

    @Test
    fun `beside AltGr a Leader on Right Ctrl fires, tapped and held, while Right Alt's chords are the terminal's`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, ctrlWHintSeen = true))
        assertEquals(LeaderKey.RIGHT_CTRL, table.settings.leaderKey)
        // AltGr+Q: the Stage leaves it, the terminal types it.
        stage(table, KEYCODE_ALT_RIGHT, altGr)
        stage(table, KEYCODE_Q, altGr)
        stage(table, KEYCODE_ALT_RIGHT, 0, ACTION_UP)
        // Right Ctrl tapped, then the / key (ß on this layout, the chords go by the key): the sheet, and the terminal hears none of it.
        stage(table, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(table, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        stage(table, KEYCODE_SLASH, 0)
        // Right Ctrl held with F: the search.
        stage(table, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(table, KEYCODE_F, rightCtrl)
        stage(table, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        // And AltGr is the layout's still.
        stage(table, KEYCODE_ALT_RIGHT, altGr)
        stage(table, KEYCODE_Q, altGr)
        stage(table, KEYCODE_ALT_RIGHT, 0, ACTION_UP)
        assertEquals(listOf("sheet", "find"), calls)
        assertEquals(listOf("text:@", "text:@"), sink.sent)
    }

    @Test
    fun `a remap onto Alt+Q fires on Left Alt and never on AltGr, so a German keyboard keeps its @, while a US keyboard's Right Alt is Alt and fires it`() {
        // Copy on Alt+Q: allowed, warned as the shell's Meta+Q. Before the reader knew the third level it fired on AltGr+Q and `@` was gone.
        val table = ChordTable(HardwareKeyboardSettings(ctrlWHintSeen = true).withRemap(ChordAction.COPY, ChordKey("Q", alt = true)))
        stage(table, KEYCODE_ALT_RIGHT, altGr)
        stage(table, KEYCODE_Q, altGr) // the layout's `@`, no chord
        stage(table, KEYCODE_ALT_RIGHT, 0, ACTION_UP)
        stage(table, KEYCODE_Q, leftAlt) // the remap
        stage(table, KEYCODE_A, altGr) // A has no third level: Right Alt is the chord's Alt there, and Alt+A is nobody's, so the terminal's Meta+a
        stage(table, KEYCODE_Q, altGr or leftAlt) // both Alts: the map answers nothing to that, so a chord, and the remap fires
        assertEquals(listOf("copy", "copy"), calls)
        assertEquals(listOf("text:@", "chord:Alt+a"), sink.sent)

        // The US layout has nothing under Right Alt, so Right Alt+Q is Alt+Q there and the remap fires, as it did.
        ShadowKeyLayout.layout = KeyLayout.US
        calls.clear(); sink.sent.clear()
        stage(table, KEYCODE_Q, altGr)
        stage(table, KEYCODE_C, altGr) // ç under either Alt is no third level: Alt+C, nobody's, the terminal's Meta+c
        assertEquals(listOf("copy"), calls)
        assertEquals(listOf("chord:Alt+c"), sink.sent)
    }

    @Test
    fun `a Right Ctrl Leader engaged, held or tapped, makes AltGr+Q Leader Alt+Q either way, swallowed when nobody's and firing a remap onto it, and the layout keeps its @ once the Leader is let go`() {
        // Copy on Alt+Q: the Leader's chord is not it. Held, the state carries the Leader's Ctrl; tapped, the arm; either way
        // the key is the Leader's before it is the layout's, nobody's here and swallowed, and the terminal hears none of it.
        val onAlt = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, ctrlWHintSeen = true).withRemap(ChordAction.COPY, ChordKey("Q", alt = true)))
        stage(onAlt, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(onAlt, KEYCODE_Q, rightCtrl or altGr) // held: Leader Alt+Q
        stage(onAlt, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        stage(onAlt, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(onAlt, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        stage(onAlt, KEYCODE_Q, altGr) // tapped: Leader Alt+Q, and the arm is spent on it
        stage(onAlt, KEYCODE_Q, altGr) // the Leader let go: the layout's @
        stage(onAlt, KEYCODE_Q, leftAlt) // Alt+Q, the remap
        assertEquals(listOf("copy"), calls)
        assertEquals(listOf("text:@"), sink.sent)

        // Copy on Leader Alt+Q, the chord the sheet captures with the Leader held beside AltGr+Q: free to bind, and it fires tapped too.
        calls.clear(); sink.sent.clear()
        val leaderAltQ = ChordKey("Q", alt = true, leader = true)
        assertEquals(null, ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER)).conflict(ChordAction.COPY, leaderAltQ))
        val onLeader = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, ctrlWHintSeen = true).withRemap(ChordAction.COPY, leaderAltQ))
        stage(onLeader, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(onLeader, KEYCODE_Q, rightCtrl or altGr)
        stage(onLeader, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        stage(onLeader, KEYCODE_CTRL_RIGHT, rightCtrl)
        stage(onLeader, KEYCODE_CTRL_RIGHT, 0, ACTION_UP)
        stage(onLeader, KEYCODE_Q, altGr)
        assertEquals(listOf("copy", "copy"), calls)
        assertEquals(emptyList<String>(), sink.sent)
    }

    @Test
    fun `a Leader chosen on Right Alt beside AltGr takes the third level with it, the cost the Settings row names`() {
        val table = ChordTable(HardwareKeyboardSettings(chordPrefix = ChordPrefix.LEADER, leaderKey = LeaderKey.RIGHT_ALT, ctrlWHintSeen = true))
        stage(table, KEYCODE_ALT_RIGHT, altGr)
        stage(table, KEYCODE_Q, altGr) // Leader Q, nobody's and swallowed: no `@`
        stage(table, KEYCODE_ALT_RIGHT, 0, ACTION_UP)
        stage(table, KEYCODE_ALT_RIGHT, altGr)
        stage(table, KEYCODE_F, altGr) // Leader F: the search
        stage(table, KEYCODE_ALT_RIGHT, 0, ACTION_UP)
        assertEquals(listOf("find"), calls)
        assertEquals(emptyList<String>(), sink.sent)
    }
}

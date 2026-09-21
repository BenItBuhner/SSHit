package app.berth.android.ui.terminal

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_0
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
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
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
import app.berth.android.ui.tabs.TabShortcuts
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.ChordTable
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.LeaderKey
import app.berth.terminal.Mod
import app.berth.terminal.TerminalKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hardware keys reaching the terminal through [handleComposeKeyEvent] on a layout with a third
 * level: Right Alt is AltGr on a German keyboard, so AltGr+Q is `@` typed and not Meta+Q, while
 * Left Alt is Meta on every layout and Right Alt is Meta too on a key with nothing under it. The
 * layouts are the platform's own `.kcm` rows, answering through `KeyCharacterMap` itself
 * ([ShadowKeyLayout]), so Compose's `utf16CodePoint` says what the device's would. And beside AltGr
 * the Stage's Leader on Right Ctrl fires as it did (spec C22), where one chosen on Right Alt takes
 * the third level with it, the cost the Settings row names.
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

    /** The Stage's calls: the sheet and the search, the two chords pressed here. */
    private val calls = ArrayList<String>()
    private val shortcuts = HardwareShortcuts(
        tabs = TabShortcuts(step = {}, jump = {}),
        stage = object : StageShortcutActions {
            override fun newTab() {}
            override fun closeTab() {}
            override fun tabSwitcher() {}
            override fun jumpToUnread() {}
            override fun find() { calls += "find" }
            override fun copy() {}
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

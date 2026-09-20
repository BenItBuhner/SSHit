package app.berth.android.ui.terminal

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import app.berth.terminal.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The soft keyboard's connection to the terminal: what Gboard and Samsung Keyboard call, in the
 * orders they call it, and what reaches the host. Every case pins "once": a word typed is a word
 * sent, never letter by letter and then whole, never twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TerminalInputConnectionTest {
    private class RecordingSink : ImeAwareSink {
        val sent = ArrayList<String>()
        override var imeConnection: TerminalInputConnection? = null
        override fun onText(text: String) { sent += "text:$text" }
        override fun onKey(key: TerminalKey, modifiers: Int) { sent += if (modifiers == 0) "key:$key" else "key:$key+$modifiers" }
    }

    private lateinit var sink: RecordingSink
    private lateinit var connection: TerminalInputConnection

    @Before
    fun setUp() {
        sink = RecordingSink()
        connection = TerminalInputConnection(View(ApplicationProvider.getApplicationContext()), sink)
        sink.imeConnection = connection
    }

    @Test
    fun `a keyboard that commits each character sends each once`() {
        connection.commitText("l", 1)
        connection.commitText("s", 1)
        connection.commitText(" ", 1)
        assertEquals(listOf("text:l", "text:s", "text: "), sink.sent)
    }

    @Test
    fun `a predictive keyboard's word is sent once, when it is committed, whatever it looked like on the way`() {
        connection.setComposingText("h", 1)
        connection.setComposingText("he", 1)
        connection.setComposingText("hel", 1)
        connection.setComposingText("he", 1) // a backspace inside the word shortens the composition
        connection.setComposingText("hex", 1)
        assertEquals(emptyList<String>(), sink.sent)
        assertTrue(connection.isComposing)
        connection.commitText("hexdump ", 1)
        assertEquals(listOf("text:hexdump "), sink.sent)
        assertFalse(connection.isComposing)
    }

    @Test
    fun `finishing a composition sends it once, and finishing again, or an empty one, sends nothing`() {
        connection.setComposingText("vim", 1)
        connection.finishComposingText()
        connection.finishComposingText()
        connection.setComposingText("", 1)
        connection.finishComposingText()
        assertEquals(listOf("text:vim"), sink.sent)
    }

    @Test
    fun `a Deck key while a word is composing sends the word first and forgets it`() {
        connection.setComposingText("ls", 1)
        // What StageInput.dispatch does before a Deck Enter.
        sink.flushComposing()
        sink.onKey(TerminalKey.ENTER)
        assertEquals(listOf("text:ls", "key:ENTER"), sink.sent)
        assertFalse(connection.isComposing)
        // The restarted keyboard starts afresh; if it were to finish the old word regardless, nothing is left to send.
        connection.finishComposingText()
        assertEquals(listOf("text:ls", "key:ENTER"), sink.sent)
    }

    @Test
    fun `a key from the keyboard itself, Enter or an arrow, sends any composing word first`() {
        connection.setComposingText("cd", 1)
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("text:cd", "key:ENTER", "key:UP"), sink.sent)
    }

    @Test
    fun `the editor action is Enter, once, with the composing word ahead of it`() {
        connection.setComposingText("top", 1)
        connection.performEditorAction(EditorInfo.IME_ACTION_NONE)
        assertEquals(listOf("text:top", "key:ENTER"), sink.sent)
    }

    @Test
    fun `deletes are Backspace and Delete keys, whether or not a word is composing`() {
        connection.deleteSurroundingText(2, 1)
        connection.setComposingText("ab", 1)
        connection.deleteSurroundingTextInCodePoints(1, 0)
        assertEquals(listOf("key:BACKSPACE", "key:BACKSPACE", "key:DELETE", "key:BACKSPACE"), sink.sent)
        // The composing word is the keyboard's to shorten, and it is still there.
        assertTrue(connection.isComposing)
    }

    @Test
    fun `newlines in committed text are Enter keys`() {
        connection.commitText("echo hi\nls\n", 1)
        assertEquals(listOf("text:echo hi", "key:ENTER", "text:ls", "key:ENTER"), sink.sent)
    }

    @Test
    fun `a string with no key of its own arrives as one multi-key event and is sent as text`() {
        connection.sendKeyEvent(KeyEvent(0L, "\u00E9\uD83D\uDE00", 0, 0))
        assertEquals(listOf("text:\u00E9\uD83D\uDE00"), sink.sent)
    }

    @Test
    fun `the keyboard never sees text to re-compose`() {
        connection.commitText("hello", 1)
        assertEquals("", connection.getTextBeforeCursor(10, 0).toString())
        assertEquals("", connection.getTextAfterCursor(10, 0).toString())
        assertEquals(null, connection.getSelectedText(0))
        assertEquals(0, connection.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
    }

    @Test
    fun `the editor info turns suggestions, autocorrect, capitalisation and learning off and keeps Enter a key`() {
        val info = EditorInfo()
        configureTerminalEditorInfo(info)
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        assertNotEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(0, info.inputType and (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE))
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN)
        assertEquals(EditorInfo.IME_ACTION_NONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertEquals(0, info.initialCapsMode)
    }
}

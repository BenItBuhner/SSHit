package app.berth.android.ui.stage

import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Share screen text hands the share sheet (spec C3's Overflow, D1's three-finger alternative):
 * the rows in view as they read, the blank rows above and below the text left out and those between
 * kept, a line the terminal wrapped joined as the host printed it, trailing blanks gone, and the
 * rows of history when the view is scrolled back; nothing when the view is blank.
 */
class ShareScreenTextTest {
    private fun term(text: String): TerminalEmulator = TerminalEmulator(20, 5, 100, TerminalListenerAdapter()).also { it.write(text) }

    @Test
    fun `the rows in view, the blank ones above and below left out and the ones between kept, each without its trailing blanks`() {
        val tools = StageTools()
        assertEquals("one\ntwo", tools.screenText(term("one\r\ntwo")))
        assertEquals("a\n\nb", tools.screenText(term("\r\na   \r\n\r\nb")))
    }

    @Test
    fun `a line the terminal wrapped is one line again, as a copy of it is`() {
        val tools = StageTools()
        assertEquals("x".repeat(30) + "\nok", tools.screenText(term("x".repeat(30) + "\r\nok")))
    }

    @Test
    fun `scrolled back, the share is the history in view, and an offset past the top is the top`() {
        val tools = StageTools()
        val t = term((0..9).joinToString("\r\n") { "line $it" })
        assertEquals("line 5\nline 6\nline 7\nline 8\nline 9", tools.screenText(t))
        tools.viewport.scrollOffset = 3
        assertEquals("line 2\nline 3\nline 4\nline 5\nline 6", tools.screenText(t))
        tools.viewport.scrollOffset = 99
        assertEquals("line 0\nline 1\nline 2\nline 3\nline 4", tools.screenText(t))
    }

    @Test
    fun `a blank view has nothing to share, so the Overflow says so instead of opening an empty sheet`() {
        val tools = StageTools()
        assertEquals("", tools.screenText(term("")))
        assertEquals("", tools.screenText(term("text\r\n\u001b[2J")))
    }
}

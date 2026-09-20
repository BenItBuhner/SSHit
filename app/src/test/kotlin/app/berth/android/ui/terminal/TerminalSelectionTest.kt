package app.berth.android.ui.terminal

import app.berth.terminal.CellPos
import app.berth.terminal.CellRange
import app.berth.terminal.SelectionMode
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A selection and a search's matches are anchored to buffer rows ([BufferAnchor]) so output does
 * not move them. The grid changing under them is the case these tests pin: a change of height
 * alone (the keyboard, the Deck) moves rows between the screen and history without re-wrapping,
 * so the selection carries through it and Copy still gives the same text; a change of width
 * re-wraps, so the selection ends rather than standing stale in the bar; the search keeps its
 * place by index either way, since a re-wrap moves cells and not logical lines.
 */
class TerminalSelectionTest {
    private fun emulator(cols: Int = 20, rows: Int = 8, scrollback: Int = 100) = TerminalEmulator(cols, rows, scrollback, TerminalListenerAdapter())

    private fun TerminalEmulator.type(lines: List<String>) = write(lines.joinToString("\r\n"))

    private fun TerminalEmulator.select(word: String, selection: TerminalSelection = TerminalSelection()): TerminalSelection {
        synchronized(lock) {
            val row = (0 until bufferRows).first { grid.line(it).toText().contains(word) }
            val col = grid.line(row).toText().indexOf(word)
            selection.start(this, CellPos(row, col + 1), SelectionMode.WORD)
        }
        return selection
    }

    @Test
    fun `a change of height alone keeps the selection and its text`() {
        val t = emulator(rows = 8)
        t.type((0 until 12).map { "line-%02d word%02d".format(it, it) })
        val sel = t.select("word09")
        assertEquals("word09", sel.text(t))
        assertEquals("6 chars", sel.summary)

        // The keyboard comes up: four rows go to history, the buffer's rows do not move.
        t.resize(20, 4)
        assertNotNull(synchronized(t.lock) { sel.current(t) })
        assertEquals("word09", sel.text(t))
        sel.dropIfStale(t)
        assertTrue(sel.active)

        // And down again: history comes back onto the screen.
        t.resize(20, 10)
        assertEquals("word09", sel.text(t))
        sel.dropIfStale(t)
        assertTrue(sel.active)
    }

    @Test
    fun `a change of width ends the selection rather than leaving the bar over nothing`() {
        val t = emulator(cols = 20, rows = 8)
        t.type((0 until 12).map { "line-%02d word%02d".format(it, it) })
        val sel = t.select("word09")
        t.resize(30, 8)
        assertNull(synchronized(t.lock) { sel.current(t) })
        assertEquals("", sel.text(t))
        sel.dropIfStale(t)
        assertFalse(sel.active)
        assertEquals("", sel.summary)
    }

    @Test
    fun `a selection reaching the blank rows below the cursor is clamped when the keyboard takes them`() {
        val t = emulator(cols = 20, rows = 8)
        t.type(listOf("alpha beta", "gamma"))
        val sel = TerminalSelection()
        synchronized(t.lock) {
            sel.start(t, CellPos(0, 0), SelectionMode.CELL)
            // Drag down onto row 5, a blank row well below the cursor's row 1.
            sel.extendTo(t, CellPos(5, 3))
        }
        // The blank rows taken on purpose come out as empty lines; only each line's trailing blanks are trimmed.
        assertEquals("alpha beta\ngamma\n\n\n\n", sel.text(t))
        // Shrinking to three rows trims blank rows from the bottom first; the range's end was on one.
        t.resize(20, 3)
        assertEquals(3, t.bufferRows)
        val now = synchronized(t.lock) { sel.current(t) }
        assertNotNull(now)
        assertEquals(2, now!!.end.row)
        assertEquals("alpha beta\ngamma\n", sel.text(t))
        sel.dropIfStale(t)
        assertTrue(sel.active)
    }

    @Test
    fun `the alternate screen ends the selection on any resize and on the switch back`() {
        val t = emulator(cols = 20, rows = 8)
        t.write("\u001b[?1049h\u001b[Hvim buffer text")
        val sel = t.select("buffer")
        assertEquals("buffer", sel.text(t))
        t.resize(20, 6)
        assertNull(synchronized(t.lock) { sel.current(t) })

        val onMain = emulator()
        onMain.type(listOf("main screen words"))
        val s2 = onMain.select("screen")
        onMain.write("\u001b[?1049h")
        assertNull(synchronized(onMain.lock) { s2.current(onMain) })
        s2.dropIfStale(onMain)
        assertFalse(s2.active)
    }

    @Test
    fun `a start evicted from the top of history is clamped to the first row, the rest of the range kept`() {
        val t = emulator(cols = 20, rows = 4, scrollback = 4)
        t.type((0 until 8).map { "row%02d".format(it) })
        // Buffer: row00..row03 in history (4), row04..row07 on screen.
        val sel = TerminalSelection()
        synchronized(t.lock) {
            sel.start(t, CellPos(2, 0), SelectionMode.LINE)
            sel.extendTo(t, CellPos(5, 0))
        }
        assertEquals("row02\nrow03\nrow04\nrow05", sel.text(t))
        // Four more rows: row00..row03 are evicted and every buffer row shifts up by four.
        t.write("\r\n" + (8 until 12).joinToString("\r\n") { "row%02d".format(it) })
        assertEquals(4L, t.linesDropped)
        val now = synchronized(t.lock) { sel.current(t) }
        assertNotNull(now)
        assertEquals(CellPos(0, 0), now!!.start)
        assertEquals("row04\nrow05", sel.text(t))
        // Grabbing the end handle keeps a start that is on the buffer.
        assertTrue(synchronized(t.lock) { sel.grab(t, SelectionHandle.END) })
        synchronized(t.lock) { sel.moveTo(t, CellPos(2, 4)) }
        assertEquals("row04\nrow05\nrow06", sel.text(t))
    }

    @Test
    fun `a search keeps its match by index through a change of width and by text through output`() {
        val t = emulator(cols = 40, rows = 6)
        t.type((0 until 12).map { "entry %02d caddy served the request fine".format(it) })
        val search = TerminalSearch()
        search.open(null, 0)
        search.query = "caddy"
        search.run(t)
        assertEquals(12, search.matches.size)
        assertEquals("12/12", search.countLabel)
        search.step(-1, t)
        search.step(-1, t)
        assertEquals("10/12", search.countLabel)

        // A re-wrap: the anchor no longer translates, the count is the same, the place is kept.
        t.resize(24, 6)
        assertNull(search.currentRange(t))
        search.run(t)
        assertEquals("10/12", search.countLabel)
        assertTrue(search.currentRange(t)!!.start.row >= 0)

        // Output, no grid change: the current match follows its text to the same index here.
        t.write("\r\nentry 12 caddy again")
        search.run(t)
        assertEquals(13, search.matches.size)
        assertEquals("10/13", search.countLabel)

        // A change of height alone leaves the anchor valid and the match where it was.
        t.resize(24, 4)
        assertNotNull(search.currentRange(t))
        search.run(t)
        assertEquals("10/13", search.countLabel)
    }

    @Test
    fun `a fresh search lands on the match nearest the prompt`() {
        val t = emulator(cols = 20, rows = 4)
        t.type(listOf("one", "two", "one", "three"))
        val search = TerminalSearch()
        search.open(null, 0)
        search.query = "one"
        search.run(t)
        assertEquals("2/2", search.countLabel)
        assertEquals(CellRange(CellPos(2, 0), CellPos(2, 2)), search.currentRange(t))
    }
}

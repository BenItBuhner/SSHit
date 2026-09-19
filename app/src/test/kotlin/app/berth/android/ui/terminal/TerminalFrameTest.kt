package app.berth.android.ui.terminal

import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TerminalFrame.rowShiftFor] predicts where the emulator's own resize will put the screen's
 * content, so the canvas can draw the frame moved there while the keyboard animates. Each case
 * predicts from a capture, then resizes the emulator for real and measures where a marked row
 * landed; the prediction has to match the row policy on both screens (blank rows below the cursor
 * go first, then the top goes to history; history comes back first on growth, never on the
 * alternate screen).
 */
class TerminalFrameTest {
    private fun emulator(rows: Int = 8, scrollback: Int = 100) = TerminalEmulator(20, rows, scrollback, TerminalListenerAdapter())

    private fun lines(count: Int) = (0 until count).joinToString("\r\n") { "line-%02d".format(it) }

    private fun TerminalEmulator.rowOf(marker: String): Int = screenText().indexOfFirst { it.startsWith(marker) }.also {
        check(it >= 0) { "$marker is not on the screen: ${screenText()}" }
    }

    /** The predicted shift for [targetRows] against the shift the resize gave the row that reads [marker]. */
    private fun assertPredicts(t: TerminalEmulator, marker: String, targetRows: Int) {
        val frame = TerminalFrame()
        frame.capture(t, scrollOffset = 0)
        val predicted = frame.rowShiftFor(targetRows)
        val before = t.rowOf(marker)
        t.resize(t.cols, targetRows)
        val after = t.rowOf(marker)
        assertEquals("shift of $marker resizing ${frame.rows} to $targetRows rows", after - before, predicted)
    }

    @Test
    fun `growing pulls history back above the content, as much as there is`() {
        val t = emulator()
        t.write(lines(12))
        assertEquals(4, t.scrollbackSize)
        assertEquals(7, t.cursorY)
        // Four rows of history for four rows of growth: the content moves down by all of it.
        assertPredicts(t, "line-11", 12)
        // Eight more rows but no history left: the picture stays put and the new rows are blank below.
        assertPredicts(t, "line-11", 20)
    }

    @Test
    fun `growing with less history than the growth pulls back only the history`() {
        val t = emulator()
        t.write(lines(10))
        assertEquals(2, t.scrollbackSize)
        assertPredicts(t, "line-09", 14)
        assertEquals(0, t.scrollbackSize)
    }

    @Test
    fun `growing without history does not move the content`() {
        val t = emulator()
        t.write(lines(3))
        assertPredicts(t, "line-02", 12)
    }

    @Test
    fun `shrinking over a blank tail does not move the content`() {
        val t = emulator()
        t.write(lines(3))
        assertEquals(2, t.cursorY)
        assertPredicts(t, "line-02", 5)
        // Down to the cursor's row: still only blank rows go.
        assertPredicts(t, "line-02", 3)
        // Past it: the blank tail is gone, so the top row goes to history and the content moves up one.
        assertPredicts(t, "line-02", 2)
        assertEquals(1, t.scrollbackSize)
    }

    @Test
    fun `shrinking over content sends the top rows to history`() {
        val t = emulator()
        t.write(lines(8))
        assertEquals(7, t.cursorY)
        assertPredicts(t, "line-07", 5)
        assertEquals(3, t.scrollbackSize)
        // Content again, now with history already there: the same policy, on top of it.
        assertPredicts(t, "line-07", 2)
        assertEquals(6, t.scrollbackSize)
    }

    @Test
    fun `shrinking with a cursor above blank rows trims only the blank rows below it`() {
        val t = emulator()
        t.write(lines(6))
        // The cursor moves up to row 1; rows 6 and 7 are blank, rows 2 to 5 are content below the cursor and are not blank.
        t.write("\u001b[2;1H")
        assertEquals(1, t.cursorY)
        // Two blank rows go, then one row of content from the top.
        assertPredicts(t, "line-05", 5)
    }

    @Test
    fun `alternate screen growth never pulls anything back`() {
        val t = emulator()
        t.write(lines(12))
        assertEquals(4, t.scrollbackSize)
        t.write("\u001b[?1049h")
        t.write(lines(3))
        assertTrue(t.isAlternateScreen)
        // The main screen's four rows of history are not the alternate screen's to show.
        assertPredicts(t, "line-02", 12)
    }

    @Test
    fun `alternate screen shrink trims the blank tail then drops from the top`() {
        val t = emulator()
        t.write("\u001b[?1049h")
        t.write(lines(3))
        assertPredicts(t, "line-02", 5)
        assertPredicts(t, "line-02", 2)
        // Filled to the bottom, every removed row comes off the top.
        t.resize(t.cols, 8)
        t.write("\u001b[2J\u001b[H")
        t.write(lines(8))
        assertPredicts(t, "line-07", 6)
    }

    @Test
    fun `no change and no capture predict nothing`() {
        val t = emulator()
        t.write(lines(12))
        val frame = TerminalFrame()
        assertEquals(0, frame.rowShiftFor(4))
        frame.capture(t, scrollOffset = 0)
        assertEquals(0, frame.rowShiftFor(8))
    }
}

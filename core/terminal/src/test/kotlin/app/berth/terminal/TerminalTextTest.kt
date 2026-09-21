package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalTextTest {
    private fun term(cols: Int = 10, rows: Int = 4, scrollback: Int = 100, text: String = ""): TerminalEmulator =
        TerminalEmulator(cols, rows, scrollback, TerminalListenerAdapter()).also { if (text.isNotEmpty()) it.write(text) }

    // ---- hit-testing and offsets ------------------------------------------------------------------

    @Test
    fun `a point maps to the cell under it and clamps past the edges`() {
        assertEquals(CellPos(2, 3), GridGeometry.cellAt(35f, 45f, 10f, 20f, cols = 80, rows = 24))
        assertEquals(CellPos(0, 0), GridGeometry.cellAt(-5f, -1f, 10f, 20f, cols = 80, rows = 24))
        assertEquals(CellPos(23, 79), GridGeometry.cellAt(9_999f, 9_999f, 10f, 20f, cols = 80, rows = 24))
        assertEquals(CellPos(0, 0), GridGeometry.cellAt(50f, 50f, 0f, 0f, cols = 80, rows = 24))
    }

    @Test
    fun `viewport rows and buffer rows convert both ways through the scroll offset`() {
        // 100 lines of history, scrolled 10 lines up: the top of the view shows history line 90.
        assertEquals(90, GridGeometry.bufferRow(0, scrollbackSize = 100, scrollOffset = 10))
        assertEquals(93, GridGeometry.bufferRow(3, scrollbackSize = 100, scrollOffset = 10))
        assertEquals(3, GridGeometry.viewRow(93, scrollbackSize = 100, scrollOffset = 10))
        // At the live screen, buffer row 100 is the screen's first row.
        assertEquals(0, GridGeometry.viewRow(100, scrollbackSize = 100, scrollOffset = 0))
        assertEquals(-1, GridGeometry.viewRow(99, scrollbackSize = 100, scrollOffset = 0))
    }

    @Test
    fun `centering a buffer row picks an offset inside the history available`() {
        assertEquals(22, GridGeometry.offsetCentering(bufferRow = 90, scrollbackSize = 100, rows = 24))
        assertEquals(100, GridGeometry.offsetCentering(bufferRow = 0, scrollbackSize = 100, rows = 24))
        assertEquals(0, GridGeometry.offsetCentering(bufferRow = 120, scrollbackSize = 100, rows = 24))
    }

    @Test
    fun `the grid's rows are history first and then the screen, matching the view`() {
        val t = term(cols = 10, rows = 2, text = (0..5).joinToString("\r\n") { "line$it" })
        assertEquals(4, t.scrollbackSize)
        assertEquals(6, t.bufferRows)
        assertEquals("line0", t.bufferLine(0).toText())
        assertEquals("line4", t.bufferLine(4).toText())
        // Scrolled two lines up, the top of the view is buffer row 2.
        val row = GridGeometry.bufferRow(0, t.scrollbackSize, 2)
        assertEquals(t.viewLine(0, 2).toText(), t.bufferLine(row).toText())
    }

    // ---- snapping -------------------------------------------------------------------------------

    @Test
    fun `long-press snaps to the word around the cell, paths included`() {
        val t = term(cols = 30, text = "ls -la /var/log/nginx here")
        assertEquals(CellRange(CellPos(0, 7), CellPos(0, 20)), TerminalText.snapToWord(t.grid, CellPos(0, 12)))
        assertEquals(CellRange(CellPos(0, 0), CellPos(0, 1)), TerminalText.snapToWord(t.grid, CellPos(0, 1)))
        assertEquals(CellRange(CellPos(0, 3), CellPos(0, 5)), TerminalText.snapToWord(t.grid, CellPos(0, 4)))
    }

    @Test
    fun `a blank cell snaps to itself`() {
        val t = term(cols = 30, text = "ab cd")
        assertEquals(CellRange(CellPos(0, 2), CellPos(0, 2)), TerminalText.snapToWord(t.grid, CellPos(0, 2)))
        assertEquals(CellRange(CellPos(0, 20), CellPos(0, 20)), TerminalText.snapToWord(t.grid, CellPos(0, 20)))
    }

    @Test
    fun `a word split by a soft wrap snaps across both rows`() {
        val t = term(cols = 10, text = "abcdefghijklmn op")
        assertTrue(t.line(0).wrapped)
        assertEquals(CellRange(CellPos(0, 0), CellPos(1, 3)), TerminalText.snapToWord(t.grid, CellPos(1, 1)))
        assertEquals(CellRange(CellPos(0, 0), CellPos(1, 3)), TerminalText.snapToWord(t.grid, CellPos(0, 8)))
    }

    @Test
    fun `line mode takes the whole logical line, every row it wraps across`() {
        val t = term(cols = 10, text = "first\r\nabcdefghijklmn\r\nlast")
        assertEquals(CellRange(CellPos(1, 0), CellPos(2, 9)), TerminalText.snapToLine(t.grid, CellPos(2, 4)))
        assertEquals(CellRange(CellPos(1, 0), CellPos(2, 9)), TerminalText.snapToLine(t.grid, CellPos(1, 0)))
        assertEquals(CellRange(CellPos(0, 0), CellPos(0, 9)), TerminalText.snapToLine(t.grid, CellPos(0, 3)))
    }

    @Test
    fun `positions clamp onto the grid and onto the head of a wide character`() {
        val t = term(cols = 10, text = "日本語")
        assertEquals(CellPos(0, 2), TerminalText.clamp(t.grid, CellPos(0, 3)))
        assertEquals(CellPos(3, 9), TerminalText.clamp(t.grid, CellPos(99, 99)))
        assertEquals(CellPos(0, 0), TerminalText.clamp(t.grid, CellPos(-4, -4)))
    }

    // ---- extraction -----------------------------------------------------------------------------

    @Test
    fun `wrapped rows come back as one logical line and hard breaks stay`() {
        val t = term(cols = 10, text = "abcdefghijklmn\r\nsecond")
        val all = CellRange(CellPos(0, 0), CellPos(2, 9))
        assertEquals("abcdefghijklmn\nsecond", TerminalText.extract(t.grid, all))
    }

    @Test
    fun `trailing blanks are trimmed on every hard line but blanks inside a wrap are kept`() {
        val t = term(cols = 10, text = "one   \r\ntwo words here\r\nend")
        assertEquals("one\ntwo words here\nend", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(3, 9))))
        // "two words " fills row 1 to the edge with its space; that space survives the rejoin.
        assertEquals("two words here", TerminalText.extract(t.grid, CellRange(CellPos(1, 0), CellPos(2, 9))))
    }

    @Test
    fun `a partial range starts and ends mid-row`() {
        val t = term(cols = 20, text = "hello brave world\r\nnext line")
        assertEquals("brave world\nnext", TerminalText.extract(t.grid, CellRange(CellPos(0, 6), CellPos(1, 3))))
    }

    @Test
    fun `wide characters are copied once whichever half the range touches`() {
        val t = term(cols = 10, text = "日本語 ok")
        assertEquals("日本語", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(0, 5))))
        assertEquals("日本語", TerminalText.extract(t.grid, CellRange(CellPos(0, 1), CellPos(0, 4))))
        assertEquals("本", TerminalText.extract(t.grid, CellRange(CellPos(0, 3), CellPos(0, 3))))
        assertEquals(CellRange(CellPos(0, 0), CellPos(0, 5)), TerminalText.snapToWord(t.grid, CellPos(0, 3)))
    }

    @Test
    fun `a wide character that did not fit at the edge leaves no gap in the copy`() {
        // 5 columns: "ab" then 日 (2 cells) fits, 本 would straddle the edge and wraps whole.
        val t = term(cols = 5, text = "ab日本")
        assertTrue(t.line(0).wrapped)
        assertEquals("ab日本", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(1, 4))))
    }

    @Test
    fun `combining marks travel with their base cell`() {
        val t = term(cols = 10, text = "e\u0301a")
        assertEquals("e\u0301a", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(0, 1))))
    }

    @Test
    fun `a range across history and the live screen reads in order`() {
        val t = term(cols = 10, rows = 2, text = (0..5).joinToString("\r\n") { "line$it" })
        assertEquals("line3\nline4\nline5", TerminalText.extract(t.grid, CellRange(CellPos(3, 0), CellPos(5, 9))))
    }

    @Test
    fun `select all spans the first to the last row with content`() {
        val t = term(cols = 10, rows = 6, text = "\r\n\r\ntop\r\nbottom")
        assertEquals(CellRange(CellPos(2, 0), CellPos(3, 9)), TerminalText.selectAll(t.grid))
        assertNull(TerminalText.selectAll(term().grid))
    }

    @Test
    fun `selections on the alternate screen use its rows`() {
        val t = term(cols = 10, rows = 3, text = "history\r\nmore\r\n\u001b[?1049h\u001b[Hpane text")
        assertTrue(t.isAlternateScreen)
        assertEquals(0, t.scrollbackSize)
        assertEquals(3, t.bufferRows)
        assertEquals(CellRange(CellPos(0, 0), CellPos(0, 3)), TerminalText.snapToWord(t.grid, CellPos(0, 1)))
        assertEquals("pane text", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(0, 9))))
    }

    @Test
    fun `characters are counted as code points`() {
        assertEquals(3, TerminalText.charCount("日本語"))
        assertEquals(1, TerminalText.charCount("\uD83D\uDE00"))
    }

    // ---- row identity through scrollback churn ----------------------------------------------------

    @Test
    fun `dropped lines count what left the top of history`() {
        val t = term(cols = 10, rows = 2, scrollback = 3, text = (0..9).joinToString("\r\n") { "L$it" })
        assertEquals(3, t.scrollbackSize)
        assertEquals(5L, t.linesDropped)
        assertEquals("L5", t.bufferLine(0).toText())
        // A row noted as buffer index plus dropped names the same text later: L7 is row 2 now.
        val remembered = 2 + t.linesDropped
        t.write("\r\nL10\r\nL11")
        assertEquals(7L, t.linesDropped)
        assertEquals("L7", t.bufferLine((remembered - t.linesDropped).toInt()).toText())
    }

    @Test
    fun `clearing history counts every line it held`() {
        val t = term(cols = 10, rows = 2, text = (0..5).joinToString("\r\n") { "line$it" })
        assertEquals(4, t.scrollbackSize)
        t.write("\u001b[3J")
        assertEquals(0, t.scrollbackSize)
        assertEquals(4L, t.linesDropped)
    }

    @Test
    fun `the cursor's logical line includes what was typed after the prompt`() {
        val t = term(cols = 10, text = "user@h:~$ git status")
        assertEquals("user@h:~$ git status", t.cursorLineText())
    }

    // ---- rectangular selection (C18, the bar's toggle) -------------------------------------------

    @Test
    fun `a block is the corners' columns cut from every row between them, one line per row`() {
        val t = term(cols = 20, text = listOf("PID   USER   CMD", "1     root   init", "42    demo   htop", "1337  demo   vim").joinToString("\r\n"))
        // The USER column, chosen top-left to bottom-right or the other way round.
        assertEquals("USER\nroot\ndemo\ndemo", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(0, 6), CellPos(3, 10))))
        assertEquals("USER\nroot\ndemo\ndemo", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(3, 10), CellPos(0, 6))))
        // Corners at the top-right and bottom-left give the same block.
        assertEquals(CellRange(CellPos(0, 6), CellPos(3, 10)), CellRange.block(CellPos(0, 10), CellPos(3, 6)))
        // Trailing blanks inside the block are trimmed, and a row with nothing in the columns is an empty line.
        assertEquals("PID\n1\n42\n1337", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(0, 0), CellPos(3, 4))))
        assertEquals("CMD\ninit\nhtop\nvim", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(0, 13), CellPos(3, 19))))
        assertEquals("\n\n\n", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(0, 18), CellPos(3, 19))))
    }

    @Test
    fun `a block does not rejoin soft-wrapped rows and takes wide characters whole`() {
        // "0123456789abcdef" wraps at ten columns; the stream rejoins it, the block keeps the rows apart.
        val t = term(cols = 10, text = "0123456789abcdef\r\n日本語 ok")
        val range = CellRange.block(CellPos(0, 2), CellPos(1, 4))
        assertEquals("0123456789abcdef", TerminalText.extract(t.grid, CellRange(CellPos(0, 0), CellPos(1, 5))))
        assertEquals("234\ncde", TerminalText.extractBlock(t.grid, range))
        // A block edge inside a wide character copies the character once.
        assertEquals("日本", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(2, 1), CellPos(2, 2))))
        assertEquals("本語", TerminalText.extractBlock(t.grid, CellRange.block(CellPos(2, 3), CellPos(2, 4))))
    }

    @Test
    fun `the block around two ranges is the smallest holding both`() {
        val a = CellRange(CellPos(2, 5), CellPos(2, 8))
        val b = CellRange(CellPos(0, 7), CellPos(0, 12))
        assertEquals(CellRange(CellPos(0, 5), CellPos(2, 12)), CellRange.blockAround(a, b))
        assertEquals(CellRange.blockAround(a, b), CellRange.blockAround(b, a))
        assertEquals(5, CellRange.blockAround(a, b).left)
        assertEquals(12, CellRange.blockAround(a, b).right)
    }
}

package app.berth.terminal

/**
 * A grid of [rows] visible lines plus, for the primary screen, a bounded scrollback of lines that
 * have scrolled off the top.
 */
class ScreenBuffer(cols: Int, rows: Int, val maxScrollback: Int) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private val lines = ArrayList<TerminalLine>(rows).apply { repeat(rows) { add(TerminalLine(cols)) } }
    private val scrollback = ArrayDeque<TerminalLine>()

    val scrollbackSize: Int get() = scrollback.size

    fun line(y: Int): TerminalLine = lines[y]

    /**
     * Line at a viewport position when the view is scrolled back by [offset] lines. Row 0 with
     * offset 0 is the top of the live screen; negative virtual rows reach into the scrollback.
     */
    fun viewLine(row: Int, offset: Int): TerminalLine {
        val virtual = row - offset
        return if (virtual >= 0) lines[virtual] else scrollback[scrollback.size + virtual]
    }

    fun scrollbackLine(index: Int): TerminalLine = scrollback[index]

    fun clearScrollback() = scrollback.clear()

    /** Scrolls [top, bottom] up by [n] lines; the vacated rows at the bottom are blank in [fillBg]. */
    fun scrollUp(top: Int, bottom: Int, n: Int, fillBg: Int, keepInScrollback: Boolean) {
        val count = n.coerceIn(0, bottom - top + 1)
        if (count == 0) return
        for (i in 0 until count) {
            val removed = lines.removeAt(top)
            if (keepInScrollback && top == 0 && maxScrollback > 0) {
                scrollback.addLast(removed)
                if (scrollback.size > maxScrollback) scrollback.removeFirst()
                lines.add(bottom, TerminalLine(cols).also { it.clear(fillBg) })
            } else {
                removed.clear(fillBg)
                lines.add(bottom, removed)
            }
        }
    }

    /** Scrolls [top, bottom] down by [n] lines; vacated rows at the top are blank in [fillBg]. */
    fun scrollDown(top: Int, bottom: Int, n: Int, fillBg: Int) {
        val count = n.coerceIn(0, bottom - top + 1)
        if (count == 0) return
        for (i in 0 until count) {
            val removed = lines.removeAt(bottom)
            removed.clear(fillBg)
            lines.add(top, removed)
        }
    }

    fun clearAll(fillBg: Int) {
        for (l in lines) l.clear(fillBg)
    }

    /**
     * Resizes the grid without reflowing text. Rows removed when shrinking are taken from the
     * bottom unless the cursor would fall off, in which case rows are pushed into scrollback from
     * the top (or dropped when scrollback is disabled). Returns the number of rows the cursor
     * must move up by.
     */
    fun resizeNoReflow(newCols: Int, newRows: Int, cursorY: Int, fillBg: Int, keepInScrollback: Boolean): Int {
        var shift = 0
        if (newCols != cols) {
            for (l in lines) l.resize(newCols, fillBg)
            for (l in scrollback) l.resize(newCols, fillBg)
            cols = newCols
        }
        if (newRows < rows) {
            var toRemove = rows - newRows
            // Prefer trimming blank lines below the cursor first.
            while (toRemove > 0 && lines.size - 1 > cursorY - shift && lines.last().isBlank()) {
                lines.removeAt(lines.size - 1)
                toRemove--
            }
            while (toRemove > 0) {
                val removed = lines.removeAt(0)
                if (keepInScrollback && maxScrollback > 0) {
                    scrollback.addLast(removed)
                    if (scrollback.size > maxScrollback) scrollback.removeFirst()
                }
                shift++
                toRemove--
            }
        } else if (newRows > rows) {
            var toAdd = newRows - rows
            // Pull history back onto the screen first, as xterm does.
            while (toAdd > 0 && keepInScrollback && scrollback.isNotEmpty()) {
                lines.add(0, scrollback.removeLast())
                shift--
                toAdd--
            }
            while (toAdd > 0) {
                lines.add(TerminalLine(cols).also { it.clear(fillBg) })
                toAdd--
            }
        }
        rows = newRows
        return shift
    }

    /**
     * Reflows the primary screen to [newCols] x [newRows], re-wrapping soft-wrapped logical lines.
     * Returns the new cursor position.
     */
    fun resizeReflow(newCols: Int, newRows: Int, cursorX: Int, cursorY: Int, fillBg: Int): Pair<Int, Int> {
        // Gather every physical line, history first.
        val all = ArrayList<TerminalLine>(scrollback.size + lines.size)
        all.addAll(scrollback)
        val cursorPhysical = scrollback.size + cursorY
        // Trailing blank lines below the cursor are not content.
        var lastContent = lines.size - 1
        while (lastContent > cursorY && lines[lastContent].isBlank() && !lines[lastContent - 1].wrapped) lastContent--
        for (i in 0..lastContent) all.add(lines[i])

        // Build logical lines as lists of physical segments, remembering where the cursor lives.
        val output = ArrayList<TerminalLine>()
        var newCursorLine = -1
        var newCursorX = cursorX.coerceIn(0, newCols - 1)
        var i = 0
        while (i < all.size) {
            var j = i
            while (j < all.size - 1 && all[j].wrapped) j++
            // Logical line is all[i..j].
            val cells = ArrayList<IntArray>() // [cp, fg, bg, attrs] per cell, wide tails skipped
            val combiningByCell = HashMap<Int, String>()
            var cursorCellIndex = -1
            for (k in i..j) {
                val l = all[k]
                val len = if (k == j) l.contentLength() else l.cols
                val cursorHere = (k == cursorPhysical)
                for (x in 0 until len) {
                    if (l.attrs[x] and Attr.WIDE_TAIL != 0) continue
                    if (cursorHere && x == cursorX) cursorCellIndex = cells.size
                    l.combining?.get(x)?.let { combiningByCell[cells.size] = it }
                    cells.add(intArrayOf(l.chars[x], l.fg[x], l.bg[x], l.attrs[x]))
                }
                // Cursor sitting past the content: remember how far beyond the last cell it was.
                if (cursorHere && cursorX >= len) cursorCellIndex = cells.size + (cursorX - len)
            }
            val logicalHasCursor = cursorPhysical in i..j
            var cursorOverflow = 0
            if (logicalHasCursor && cursorCellIndex >= cells.size) {
                cursorOverflow = cursorCellIndex - cells.size
                cursorCellIndex = cells.size
            }

            // Re-wrap into newCols.
            var current = TerminalLine(newCols).also { it.clear(fillBg) }
            var x = 0
            var placedCursor = false
            fun flush(wrapped: Boolean) {
                current.wrapped = wrapped
                output.add(current)
                current = TerminalLine(newCols).also { it.clear(fillBg) }
                x = 0
            }
            for (idx in cells.indices) {
                val c = cells[idx]
                val width = if (c[3] and Attr.WIDE != 0) 2 else 1
                if (x + width > newCols) flush(wrapped = true)
                if (logicalHasCursor && idx == cursorCellIndex && !placedCursor) {
                    newCursorLine = output.size
                    newCursorX = x
                    placedCursor = true
                }
                current.set(x, c[0], c[1], c[2], c[3])
                combiningByCell[idx]?.let { marks -> for (ch in marks.codePoints()) current.addCombining(x, ch) }
                if (width == 2 && x + 1 < newCols) current.set(x + 1, 0, c[1], c[2], Attr.WIDE_TAIL)
                x += width
            }
            if (logicalHasCursor && !placedCursor) {
                var cx = x + cursorOverflow
                var extraLines = 0
                while (cx >= newCols) {
                    cx -= newCols
                    extraLines++
                }
                if (extraLines > 0) {
                    flush(wrapped = true)
                    for (n in 1 until extraLines) flush(wrapped = true)
                }
                newCursorLine = output.size
                newCursorX = cx
            }
            flush(wrapped = false)
            i = j + 1
        }
        if (newCursorLine < 0) newCursorLine = (output.size - 1).coerceAtLeast(0)
        if (output.isEmpty()) output.add(TerminalLine(newCols).also { it.clear(fillBg) })

        // Split back into scrollback and screen so the cursor keeps its visual row where possible.
        val total = output.size
        val desiredCursorRow = cursorY.coerceIn(0, newRows - 1)
        var first = newCursorLine - desiredCursorRow
        if (first < 0) first = 0
        if (total - first < newRows) first = (total - newRows).coerceAtLeast(0)
        if (newCursorLine - first >= newRows) first = newCursorLine - newRows + 1

        scrollback.clear()
        for (k in 0 until first) {
            scrollback.addLast(output[k])
        }
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        lines.clear()
        for (k in first until minOf(total, first + newRows)) lines.add(output[k])
        while (lines.size < newRows) lines.add(TerminalLine(newCols).also { it.clear(fillBg) })
        cols = newCols
        rows = newRows
        return Pair(newCursorX.coerceIn(0, newCols - 1), (newCursorLine - first).coerceIn(0, newRows - 1))
    }
}

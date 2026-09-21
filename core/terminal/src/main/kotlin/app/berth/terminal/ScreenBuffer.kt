package app.berth.terminal

/**
 * A grid of [rows] visible lines plus, for the primary screen, a bounded scrollback of lines that
 * have scrolled off the top.
 */
class ScreenBuffer(cols: Int, rows: Int, maxScrollback: Int) {
    var cols: Int = cols
        private set
    var rows: Int = rows
        private set

    private val lines = ArrayList<TerminalLine>(rows).apply { repeat(rows) { add(TerminalLine(cols)) } }
    private val scrollback = ArrayDeque<TerminalLine>()

    /** How many lines history keeps; lowering it evicts the oldest at once, as if they had scrolled past. */
    var maxScrollback: Int = maxScrollback.coerceAtLeast(0)
        set(value) {
            field = value.coerceAtLeast(0)
            while (scrollback.size > field) evictOldest()
        }

    val scrollbackSize: Int get() = scrollback.size

    /**
     * Lines that have left the top of history since this buffer was made: evicted past
     * [maxScrollback] or cleared by ED 3. A row remembered as its buffer index plus this count
     * names the same text after any amount of output, which is what lets a selection or a search
     * match survive scrollback churn.
     */
    var dropped: Long = 0L
        private set

    /** History plus screen as one run of rows. */
    val bufferRows: Int get() = scrollback.size + lines.size

    fun line(y: Int): TerminalLine = lines[y]

    /** Row [row] of [bufferRows]: the oldest history line is 0, the screen's top row is [scrollbackSize]. */
    fun bufferLine(row: Int): TerminalLine = if (row < scrollback.size) scrollback[row] else lines[row - scrollback.size]

    /**
     * Line at a viewport position when the view is scrolled back by [offset] lines. Row 0 with
     * offset 0 is the top of the live screen; negative virtual rows reach into the scrollback.
     */
    fun viewLine(row: Int, offset: Int): TerminalLine {
        val virtual = row - offset
        return if (virtual >= 0) lines[virtual] else scrollback[scrollback.size + virtual]
    }

    fun scrollbackLine(index: Int): TerminalLine = scrollback[index]

    fun clearScrollback() {
        dropped += scrollback.size
        scrollback.clear()
    }

    private fun evictOldest() {
        scrollback.removeFirst()
        dropped++
    }

    /** Scrolls [top, bottom] up by [n] lines; the vacated rows at the bottom are blank in [fillBg]. */
    fun scrollUp(top: Int, bottom: Int, n: Int, fillBg: Int, keepInScrollback: Boolean) {
        val count = n.coerceIn(0, bottom - top + 1)
        if (count == 0) return
        for (i in 0 until count) {
            val removed = lines.removeAt(top)
            if (keepInScrollback && top == 0 && maxScrollback > 0) {
                scrollback.addLast(removed)
                if (scrollback.size > maxScrollback) evictOldest()
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
                    if (scrollback.size > maxScrollback) evictOldest()
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
     *
     * A change of height alone re-wraps nothing, so it takes the row adjustment of [resizeNoReflow]
     * with history kept; that is the resize the keyboard and the Deck cause as they animate, and it
     * has to be cheap enough to run on the UI thread with thousands of lines of history.
     */
    fun resizeReflow(newCols: Int, newRows: Int, cursorX: Int, cursorY: Int, fillBg: Int): Pair<Int, Int> {
        if (newCols == cols) {
            val shift = resizeNoReflow(newCols, newRows, cursorY, fillBg, keepInScrollback = true)
            return Pair(cursorX.coerceIn(0, newCols - 1), (cursorY - shift).coerceIn(0, newRows - 1))
        }

        // Gather every physical line, history first.
        val all = ArrayList<TerminalLine>(scrollback.size + lines.size)
        all.addAll(scrollback)
        val cursorPhysical = scrollback.size + cursorY
        // Trailing blank lines below the cursor are not content.
        var lastContent = lines.size - 1
        while (lastContent > cursorY && lines[lastContent].isBlank() && !lines[lastContent - 1].wrapped) lastContent--
        for (i in 0..lastContent) all.add(lines[i])

        // Build logical lines as lists of physical segments, remembering where the cursor lives.
        val output = ArrayList<TerminalLine>(all.size + 16)
        var newCursorLine = -1
        var newCursorX = cursorX.coerceIn(0, newCols - 1)
        val cells = ReflowCells(newCols * 2)
        var i = 0
        while (i < all.size) {
            var j = i
            while (j < all.size - 1 && all[j].wrapped) j++
            val logicalHasCursor = cursorPhysical in i..j

            // A line of its own that already fits keeps its arrays; most of history is such lines.
            if (i == j) {
                val single = all[i]
                if (single.contentLength() <= newCols && (!logicalHasCursor || cursorX < newCols)) {
                    single.resize(newCols, fillBg)
                    single.wrapped = false
                    if (logicalHasCursor) {
                        newCursorLine = output.size
                        newCursorX = cursorX
                    }
                    output.add(single)
                    i++
                    continue
                }
            }

            // Logical line is all[i..j]: its cells, wide tails skipped, into the scratch arrays.
            cells.reset()
            var combiningByCell: HashMap<Int, String>? = null
            var cursorCellIndex = -1
            for (k in i..j) {
                val l = all[k]
                val len = if (k == j) l.contentLength() else l.cols
                val cursorHere = (k == cursorPhysical)
                cells.ensure(cells.size + len)
                val marks = l.combining
                val links = l.links
                for (x in 0 until len) {
                    if (l.attrs[x] and Attr.WIDE_TAIL != 0) continue
                    if (cursorHere && x == cursorX) cursorCellIndex = cells.size
                    marks?.get(x)?.let { m -> (combiningByCell ?: HashMap<Int, String>().also { combiningByCell = it })[cells.size] = m }
                    cells.add(l.chars[x], l.fg[x], l.bg[x], l.attrs[x], links?.get(x) ?: 0)
                }
                // Cursor sitting past the content: remember how far beyond the last cell it was.
                if (cursorHere && cursorX >= len) cursorCellIndex = cells.size + (cursorX - len)
            }
            var cursorOverflow = 0
            if (logicalHasCursor && cursorCellIndex >= cells.size) {
                cursorOverflow = cursorCellIndex - cells.size
                cursorCellIndex = cells.size
            }

            // Re-wrap into newCols; a row is allocated when its first cell lands.
            var current: TerminalLine? = null
            var x = 0
            var placedCursor = false
            fun row(): TerminalLine = current ?: TerminalLine(newCols).also { it.clear(fillBg); current = it }
            fun flush(wrapped: Boolean) {
                val line = row()
                line.wrapped = wrapped
                output.add(line)
                current = null
                x = 0
            }
            for (idx in 0 until cells.size) {
                val attrs = cells.attrs[idx]
                val width = if (attrs and Attr.WIDE != 0) 2 else 1
                if (x + width > newCols) flush(wrapped = true)
                if (logicalHasCursor && idx == cursorCellIndex && !placedCursor) {
                    newCursorLine = output.size
                    newCursorX = x
                    placedCursor = true
                }
                val line = row()
                val link = cells.links[idx]
                line.set(x, cells.chars[idx], cells.fg[idx], cells.bg[idx], attrs, link)
                combiningByCell?.get(idx)?.let { marks -> for (ch in marks.codePoints()) line.addCombining(x, ch) }
                if (width == 2 && x + 1 < newCols) line.set(x + 1, 0, cells.fg[idx], cells.bg[idx], Attr.WIDE_TAIL, link)
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
        while (scrollback.size > maxScrollback) evictOldest()
        lines.clear()
        for (k in first until minOf(total, first + newRows)) lines.add(output[k])
        while (lines.size < newRows) lines.add(TerminalLine(newCols).also { it.clear(fillBg) })
        cols = newCols
        rows = newRows
        return Pair(newCursorX.coerceIn(0, newCols - 1), (newCursorLine - first).coerceIn(0, newRows - 1))
    }
}

/** The cells of one logical line during a reflow, as parallel arrays that grow and are reused. */
private class ReflowCells(capacity: Int) {
    var chars = IntArray(capacity)
    var fg = IntArray(capacity)
    var bg = IntArray(capacity)
    var attrs = IntArray(capacity)
    var links = IntArray(capacity)
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun ensure(capacity: Int) {
        if (capacity <= chars.size) return
        val n = maxOf(capacity, chars.size * 2)
        chars = chars.copyOf(n)
        fg = fg.copyOf(n)
        bg = bg.copyOf(n)
        attrs = attrs.copyOf(n)
        links = links.copyOf(n)
    }

    fun add(cp: Int, f: Int, b: Int, a: Int, link: Int) {
        chars[size] = cp
        fg[size] = f
        bg[size] = b
        attrs[size] = a
        links[size] = link
        size++
    }
}

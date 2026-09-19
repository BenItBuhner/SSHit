package app.berth.terminal

/**
 * A cell in buffer coordinates: [row] counts from the oldest line of scrollback down through the
 * live screen, [col] from the left edge. Buffer rows stay put while output scrolls the screen, so a
 * selection or a search match anchored this way follows its text into history.
 */
data class CellPos(val row: Int, val col: Int) : Comparable<CellPos> {
    override fun compareTo(other: CellPos): Int = if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/** An inclusive run of cells in reading order; [start] never comes after [end]. */
data class CellRange(val start: CellPos, val end: CellPos) {
    init {
        require(start <= end) { "range start $start after end $end" }
    }

    val rowCount: Int get() = end.row - start.row + 1

    /** Whether [pos] lies inside the range in reading order. */
    operator fun contains(pos: CellPos): Boolean = pos >= start && pos <= end

    /** Whether [row] is one of the rows the range touches. */
    fun touches(row: Int): Boolean = row in start.row..end.row

    /** First column highlighted on [row], or null when the row is outside the range. */
    fun firstCol(row: Int): Int? = if (!touches(row)) null else if (row == start.row) start.col else 0

    /** Last column highlighted on [row] for a grid [cols] wide, or null when the row is outside the range. */
    fun lastCol(row: Int, cols: Int): Int? = if (!touches(row)) null else if (row == end.row) end.col else cols - 1

    fun shiftRows(delta: Int): CellRange = CellRange(CellPos(start.row + delta, start.col), CellPos(end.row + delta, end.col))

    companion object {
        /** The range between two cells in either order. */
        fun of(a: CellPos, b: CellPos): CellRange = if (a <= b) CellRange(a, b) else CellRange(b, a)
    }
}

/** How a selection grows from where it started: by cell, by whole word or by whole logical line. */
enum class SelectionMode { CELL, WORD, LINE }

/** The rows of a terminal buffer as one sequence: scrollback first, then the screen. */
interface TextGrid {
    val cols: Int
    val rowCount: Int
    fun line(row: Int): TerminalLine
}

/**
 * Text operations over a [TextGrid]: hit-testing and snapping for selection, and extraction that
 * rejoins soft-wrapped rows into the logical lines the host printed. Callers reading a live
 * emulator hold its lock for the duration of a call.
 */
object TerminalText {
    /** The head cell of the character at [col]: the column itself unless it is the tail half of a wide character. */
    fun headCol(line: TerminalLine, col: Int): Int {
        val c = col.coerceIn(0, line.cols - 1)
        return if (c > 0 && line.attrs[c] and Attr.WIDE_TAIL != 0) c - 1 else c
    }

    /** The last cell of the character whose head is at [col]: its tail when it is wide. */
    fun tailCol(line: TerminalLine, col: Int): Int {
        val c = col.coerceIn(0, line.cols - 1)
        return if (line.attrs[c] and Attr.WIDE != 0 && c + 1 < line.cols) c + 1 else c
    }

    /** Clamps a position onto the grid and onto the head of a wide character. */
    fun clamp(grid: TextGrid, pos: CellPos): CellPos {
        if (grid.rowCount == 0) return CellPos(0, 0)
        val row = pos.row.coerceIn(0, grid.rowCount - 1)
        return CellPos(row, headCol(grid.line(row), pos.col))
    }

    /** First physical row of the logical line that [row] belongs to: walks up while the row above continues into it. */
    fun logicalStart(grid: TextGrid, row: Int): Int {
        var r = row.coerceIn(0, grid.rowCount - 1)
        while (r > 0 && grid.line(r - 1).wrapped) r--
        return r
    }

    /** Last physical row of the logical line that [row] belongs to: walks down while the row continues. */
    fun logicalEnd(grid: TextGrid, row: Int): Int {
        var r = row.coerceIn(0, grid.rowCount - 1)
        while (r < grid.rowCount - 1 && grid.line(r).wrapped) r++
        return r
    }

    /** Whether the code point is part of a word for double-tap and long-press snapping: letters, digits and path or URL punctuation. */
    fun isWordChar(codePoint: Int): Boolean = when {
        codePoint <= 0x20 -> false
        codePoint == 0x7F -> false
        Character.isLetterOrDigit(codePoint) -> true
        else -> codePoint < 0x80 && WORD_PUNCTUATION.indexOf(codePoint.toChar()) >= 0
    }

    private fun isWordCell(line: TerminalLine, col: Int): Boolean {
        val cp = line.chars[col]
        return cp != 0 && isWordChar(cp)
    }

    private fun isBlankCell(line: TerminalLine, col: Int): Boolean {
        val cp = line.chars[col]
        return cp == 0 || cp == 0x20
    }

    /**
     * The word around [pos], following it across soft wraps so a long path split over two rows
     * comes back whole. A cell that is not part of a word (a blank, a bracket) selects itself.
     */
    fun snapToWord(grid: TextGrid, pos: CellPos): CellRange {
        val p = clamp(grid, pos)
        val line = grid.line(p.row)
        if (!isWordCell(line, p.col)) return CellRange(p, CellPos(p.row, tailCol(line, p.col)))

        var sr = p.row
        var sc = p.col
        while (true) {
            val l = grid.line(sr)
            if (sc > 0) {
                val prev = headCol(l, sc - 1)
                if (!isWordCell(l, prev)) break
                sc = prev
            } else {
                if (sr == 0 || !grid.line(sr - 1).wrapped) break
                val above = grid.line(sr - 1)
                val last = headCol(above, above.cols - 1)
                if (!isWordCell(above, last)) break
                sr--
                sc = last
            }
        }

        var er = p.row
        var ec = p.col
        while (true) {
            val l = grid.line(er)
            val next = tailCol(l, ec) + 1
            if (next < l.cols) {
                if (!isWordCell(l, next)) break
                ec = next
            } else {
                if (er >= grid.rowCount - 1 || !l.wrapped) break
                val below = grid.line(er + 1)
                if (below.cols == 0 || !isWordCell(below, 0)) break
                er++
                ec = 0
            }
        }
        return CellRange(CellPos(sr, sc), CellPos(er, tailCol(grid.line(er), ec)))
    }

    /** The whole logical line through [pos]: every physical row it wraps across, edge to edge. */
    fun snapToLine(grid: TextGrid, pos: CellPos): CellRange {
        val row = pos.row.coerceIn(0, grid.rowCount - 1)
        return CellRange(CellPos(logicalStart(grid, row), 0), CellPos(logicalEnd(grid, row), grid.cols - 1))
    }

    /** Everything from the first row with content to the last, or null when the buffer is blank. */
    fun selectAll(grid: TextGrid): CellRange? {
        var first = 0
        while (first < grid.rowCount && grid.line(first).isBlank()) first++
        if (first == grid.rowCount) return null
        var last = grid.rowCount - 1
        while (last > first && grid.line(last).isBlank()) last--
        return CellRange(CellPos(first, 0), CellPos(last, grid.cols - 1))
    }

    /**
     * The text of [range]. Rows that soft-wrap into the next are joined without a line break and
     * keep their printed blanks (a wrapped row is full to the edge, the only gap being the padding
     * before a wide character that did not fit); every other row loses its trailing blanks and
     * ends with a newline unless it is the last. Wide characters contribute their head cell once.
     */
    fun extract(grid: TextGrid, range: CellRange): String {
        val sb = StringBuilder()
        val lastRow = minOf(range.end.row, grid.rowCount - 1)
        for (row in range.start.row.coerceAtLeast(0)..lastRow) {
            val line = grid.line(row)
            val from = if (row == range.start.row) headCol(line, range.start.col) else 0
            val through = if (row == range.end.row) range.end.col.coerceIn(0, line.cols - 1) else line.cols - 1
            val continues = row < range.end.row && line.wrapped
            var end = minOf(through + 1, line.cols)
            if (continues) {
                end = minOf(end, line.contentLength())
            } else {
                while (end > from && isBlankCell(line, end - 1)) end--
            }
            var x = from
            while (x < end) {
                if (line.attrs[x] and Attr.WIDE_TAIL != 0) {
                    x++
                    continue
                }
                sb.append(line.cellText(x))
                x++
            }
            if (!continues && row < lastRow) sb.append('\n')
        }
        return sb.toString()
    }

    /** Characters in [text] as a person counts them: code points, not UTF-16 units. */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    private const val WORD_PUNCTUATION = "_-./~:@%+=?&#$\\*"
}

/**
 * Where a point on the canvas lands on the grid and how viewport rows relate to buffer rows when
 * the view is scrolled [scrollOffset] lines into a history of [scrollbackSize] lines.
 */
object GridGeometry {
    /** The cell under a point, clamped onto the grid so a finger past an edge still resolves to the nearest cell. */
    fun cellAt(x: Float, y: Float, cellWidth: Float, cellHeight: Float, cols: Int, rows: Int): CellPos {
        val col = if (cellWidth <= 0f) 0 else kotlin.math.floor(x / cellWidth).toInt().coerceIn(0, (cols - 1).coerceAtLeast(0))
        val row = if (cellHeight <= 0f) 0 else kotlin.math.floor(y / cellHeight).toInt().coerceIn(0, (rows - 1).coerceAtLeast(0))
        return CellPos(row, col)
    }

    /** Buffer row shown at viewport [viewRow]. */
    fun bufferRow(viewRow: Int, scrollbackSize: Int, scrollOffset: Int): Int = scrollbackSize - scrollOffset + viewRow

    /** Viewport row that shows buffer row [bufferRow]; may fall outside 0 until rows. */
    fun viewRow(bufferRow: Int, scrollbackSize: Int, scrollOffset: Int): Int = bufferRow - scrollbackSize + scrollOffset

    /** The scroll offset that puts [bufferRow] at the middle of a viewport [rows] tall, clamped to the history available. */
    fun offsetCentering(bufferRow: Int, scrollbackSize: Int, rows: Int): Int =
        (scrollbackSize - bufferRow + rows / 2).coerceIn(0, scrollbackSize)
}

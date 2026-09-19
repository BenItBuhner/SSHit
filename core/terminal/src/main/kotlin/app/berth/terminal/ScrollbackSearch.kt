package app.berth.terminal

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Finds text in a terminal buffer, history included. Each logical line is searched as one string,
 * so a match may run across a soft wrap and is reported as the cells it covers. Matches come back
 * in reading order and never overlap.
 */
object ScrollbackSearch {
    /**
     * Every occurrence of [query] in [grid], case-folded unless [caseSensitive], as a regular
     * expression when [regex] (a pattern that does not compile matches nothing). Stops after
     * [limit] matches so a one-letter query over a long history stays bounded.
     */
    fun find(grid: TextGrid, query: String, caseSensitive: Boolean = false, regex: Boolean = false, limit: Int = MAX_MATCHES): List<CellRange> {
        if (query.isEmpty() || grid.rowCount == 0) return emptyList()
        val pattern: Pattern? = if (regex) {
            try {
                Pattern.compile(query, if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (_: PatternSyntaxException) {
                return emptyList()
            }
        } else null

        val out = ArrayList<CellRange>()
        val text = StringBuilder()
        var rows = IntArray(grid.cols * 4)
        var cols = IntArray(grid.cols * 4)
        var row = 0
        while (row < grid.rowCount && out.size < limit) {
            val last = TerminalText.logicalEnd(grid, row)
            text.setLength(0)
            for (r in row..last) {
                val line = grid.line(r)
                val len = if (r == last) line.contentLength() else line.cols
                var x = 0
                while (x < len) {
                    if (line.attrs[x] and Attr.WIDE_TAIL != 0) {
                        x++
                        continue
                    }
                    val cell = line.cellText(x)
                    val need = text.length + cell.length
                    if (need > rows.size) {
                        val n = maxOf(need, rows.size * 2)
                        rows = rows.copyOf(n)
                        cols = cols.copyOf(n)
                    }
                    for (i in 0 until cell.length) {
                        rows[text.length + i] = r
                        cols[text.length + i] = x
                    }
                    text.append(cell)
                    x++
                }
            }
            if (text.isNotEmpty()) {
                if (pattern != null) {
                    val m = pattern.matcher(text)
                    while (m.find() && out.size < limit) {
                        if (m.end() == m.start()) {
                            if (m.end() >= text.length) break
                            m.region(m.end() + 1, text.length)
                            continue
                        }
                        out.add(rangeOf(grid, rows, cols, m.start(), m.end()))
                    }
                } else {
                    var from = 0
                    while (from <= text.length - query.length && out.size < limit) {
                        val at = text.indexOf(query, from, ignoreCase = !caseSensitive)
                        if (at < 0) break
                        out.add(rangeOf(grid, rows, cols, at, at + query.length))
                        from = at + query.length
                    }
                }
            }
            row = last + 1
        }
        return out
    }

    private fun rangeOf(grid: TextGrid, rows: IntArray, cols: IntArray, start: Int, endExclusive: Int): CellRange {
        val endRow = rows[endExclusive - 1]
        val endCol = TerminalText.tailCol(grid.line(endRow), cols[endExclusive - 1])
        return CellRange(CellPos(rows[start], cols[start]), CellPos(endRow, endCol))
    }

    const val MAX_MATCHES = 5_000
}

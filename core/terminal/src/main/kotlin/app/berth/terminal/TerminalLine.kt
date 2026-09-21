package app.berth.terminal

/**
 * One row of cells. Parallel arrays keep the hot path allocation-free; a code point of 0 means the
 * cell is empty and renders as a space in its background color.
 */
class TerminalLine(cols: Int) {
    var chars: IntArray = IntArray(cols)
        private set
    var fg: IntArray = IntArray(cols)
        private set
    var bg: IntArray = IntArray(cols)
        private set
    var attrs: IntArray = IntArray(cols)
        private set

    /** True when this row continues on the next row, i.e. it was soft-wrapped while printing. */
    var wrapped: Boolean = false

    /** Combining marks attached to a cell, keyed by column. Allocated lazily; almost always null. */
    var combining: HashMap<Int, String>? = null
        private set

    /**
     * The OSC 8 hyperlink each cell was printed under, as an id the emulator's [LinkRegistry]
     * resolves to its URL; 0 for none. Allocated the first time a link lands on the row, so a
     * buffer without links carries no extra memory and the hot path pays one null check.
     */
    var links: IntArray? = null
        private set

    val cols: Int get() = chars.size

    fun set(x: Int, codePoint: Int, fg: Int, bg: Int, attrs: Int, link: Int = 0) {
        chars[x] = codePoint
        this.fg[x] = fg
        this.bg[x] = bg
        this.attrs[x] = attrs
        combining?.remove(x)
        val l = links
        if (l != null) l[x] = link else if (link != 0) links = IntArray(cols).also { it[x] = link }
    }

    /** The link id of the cell at [x], 0 for none. */
    fun linkAt(x: Int): Int = links?.get(x) ?: 0

    fun clearCell(x: Int, bg: Int) {
        chars[x] = 0
        fg[x] = TermColor.COLOR_DEFAULT
        this.bg[x] = bg
        attrs[x] = 0
        combining?.remove(x)
        links?.let { it[x] = 0 }
    }

    fun clear(bg: Int) {
        chars.fill(0)
        fg.fill(TermColor.COLOR_DEFAULT)
        this.bg.fill(bg)
        attrs.fill(0)
        combining = null
        links = null
        wrapped = false
    }

    fun clearRange(from: Int, toExclusive: Int, bg: Int) {
        val end = toExclusive.coerceAtMost(cols)
        val start = from.coerceAtLeast(0)
        if (start >= end) return
        chars.fill(0, start, end)
        fg.fill(TermColor.COLOR_DEFAULT, start, end)
        this.bg.fill(bg, start, end)
        attrs.fill(0, start, end)
        combining?.let { map -> for (x in start until end) map.remove(x) }
        links?.fill(0, start, end)
    }

    fun addCombining(x: Int, codePoint: Int) {
        val map = combining ?: HashMap<Int, String>().also { combining = it }
        val existing = map[x] ?: ""
        map[x] = existing + String(Character.toChars(codePoint))
    }

    /** Text of the cell, including any combining marks, or a space when empty. */
    fun cellText(x: Int): String {
        val cp = chars[x]
        if (cp == 0) return " "
        val base = String(Character.toChars(cp))
        val marks = combining?.get(x) ?: return base
        return base + marks
    }

    /** Copies cells [from, from + count) to [to, to + count) within this line, handling overlap. */
    fun moveCells(from: Int, to: Int, count: Int) {
        if (count <= 0) return
        System.arraycopy(chars, from, chars, to, count)
        System.arraycopy(fg, from, fg, to, count)
        System.arraycopy(bg, from, bg, to, count)
        System.arraycopy(attrs, from, attrs, to, count)
        links?.let { System.arraycopy(it, from, it, to, count) }
        val map = combining
        if (map != null && map.isNotEmpty()) {
            val moved = HashMap<Int, String>()
            for ((k, v) in map) {
                if (k in from until from + count) moved[k - from + to] = v else if (k !in to until to + count) moved[k] = v
            }
            combining = moved
        }
    }

    fun copyFrom(other: TerminalLine) {
        if (other.cols != cols) resize(other.cols, TermColor.COLOR_DEFAULT)
        System.arraycopy(other.chars, 0, chars, 0, cols)
        System.arraycopy(other.fg, 0, fg, 0, cols)
        System.arraycopy(other.bg, 0, bg, 0, cols)
        System.arraycopy(other.attrs, 0, attrs, 0, cols)
        wrapped = other.wrapped
        combining = other.combining?.let { HashMap(it) }
        // A frame captured over and over reuses its rows' link arrays rather than allocating per capture.
        val theirs = other.links
        links = if (theirs == null) null else (links ?: IntArray(cols)).also { System.arraycopy(theirs, 0, it, 0, cols) }
    }

    fun resize(newCols: Int, fillBg: Int) {
        if (newCols == cols) return
        val n = minOf(cols, newCols)
        chars = chars.copyOf(newCols).also { if (newCols > n) it.fill(0, n, newCols) }
        fg = fg.copyOf(newCols).also { if (newCols > n) it.fill(TermColor.COLOR_DEFAULT, n, newCols) }
        bg = bg.copyOf(newCols).also { if (newCols > n) it.fill(fillBg, n, newCols) }
        attrs = attrs.copyOf(newCols).also { if (newCols > n) it.fill(0, n, newCols) }
        links = links?.copyOf(newCols)
        combining?.let { map -> map.keys.filter { it >= newCols }.forEach { map.remove(it) } }
        // A wide character split by the new edge loses its head.
        if (newCols > 0 && attrs[newCols - 1] and Attr.WIDE != 0) clearCell(newCols - 1, bg[newCols - 1])
    }

    /** Index one past the last non-empty cell, or 0 if the line is blank. */
    fun contentLength(): Int {
        var i = cols
        while (i > 0 && chars[i - 1] == 0 && attrs[i - 1] and Attr.WIDE_TAIL == 0) i--
        return i
    }

    fun isBlank(): Boolean = contentLength() == 0

    /** Plain-text view of the line for tests and clipboard, trailing blanks trimmed. */
    fun toText(): String {
        val sb = StringBuilder()
        val len = contentLength()
        var x = 0
        while (x < len) {
            if (attrs[x] and Attr.WIDE_TAIL != 0) {
                x++
                continue
            }
            sb.append(cellText(x))
            x++
        }
        return sb.toString()
    }
}

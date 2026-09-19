package app.berth.android.ui.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.berth.terminal.CellPos
import app.berth.terminal.CellRange
import app.berth.terminal.GridGeometry
import app.berth.terminal.ScrollbackSearch
import app.berth.terminal.SelectionMode
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalText

/** Which end of a selection a finger holds. */
enum class SelectionHandle { START, END }

/**
 * Where a selection or a search match sits: in the rows of the buffer as it was when [dropped]
 * lines had left the top of history, on the main or the [alternate] screen of a grid [cols] by
 * [rows]. Output scrolling the screen appends to history and leaves buffer rows where they are; a
 * line evicted from the top moves them all up by one, which [dropped] accounts for; a resize
 * re-wraps history and a switch of screens shows other text, so either invalidates the anchor.
 */
data class BufferAnchor(val dropped: Long, val alternate: Boolean, val cols: Int, val rows: Int) {
    /** [range], made under this anchor, in the rows of a buffer that has now dropped [linesDropped] lines; null once invalid. */
    fun translate(range: CellRange, linesDropped: Long, alternate: Boolean, cols: Int, rows: Int): CellRange? {
        if (alternate != this.alternate || cols != this.cols || rows != this.rows) return null
        val shift = (dropped - linesDropped).toInt()
        if (range.end.row + shift < 0) return null
        return if (shift == 0) range else range.shiftRows(shift)
    }

    companion object {
        /** The anchor for the emulator's buffer now; the caller holds its lock. */
        fun of(emulator: TerminalEmulator): BufferAnchor =
            BufferAnchor(emulator.linesDropped, emulator.isAlternateScreen, emulator.cols, emulator.rows)
    }
}

/**
 * One terminal's text selection (spec C18): a [CellRange] over the buffer plus the mode a gesture
 * grows it in. The range is anchored to buffer rows (see [BufferAnchor]) so new output does not
 * move it off its text and it stays until dismissed. The text itself is read at copy time, since
 * a program repainting the live screen can change what the cells hold.
 */
class TerminalSelection {
    var range by mutableStateOf<CellRange?>(null)
        private set
    var anchor: BufferAnchor? = null
        private set
    var mode: SelectionMode = SelectionMode.CELL
        private set

    /** The end a finger is dragging, while it is. */
    var dragging by mutableStateOf<SelectionHandle?>(null)

    /** How much Copy would give, for the bar: `24 chars` for one line, `3 lines` for more; a path wrapped over two rows is still one line. */
    var summary by mutableStateOf("")
        private set

    /** The part a gesture keeps while it extends the other: the word or line first chosen, or the handle not held. */
    private var fixed: CellRange? = null

    val active: Boolean get() = range != null

    /** Begins a selection at [pos] (buffer rows) snapped for [mode]; the caller holds the emulator's lock. */
    fun start(emulator: TerminalEmulator, pos: CellPos, mode: SelectionMode) {
        val grid = emulator.grid
        val first = when (mode) {
            SelectionMode.WORD -> TerminalText.snapToWord(grid, pos)
            SelectionMode.LINE -> TerminalText.snapToLine(grid, pos)
            SelectionMode.CELL -> TerminalText.clamp(grid, pos).let { CellRange(it, CellPos(it.row, TerminalText.tailCol(grid.line(it.row), it.col))) }
        }
        this.mode = mode
        fixed = first
        apply(emulator, first)
    }

    /** Grows the selection begun with [start] to cover [pos] as well, snapping the same way; the caller holds the lock. */
    fun extendTo(emulator: TerminalEmulator, pos: CellPos) {
        val grid = emulator.grid
        val base = fixed ?: return
        val other = when (mode) {
            SelectionMode.WORD -> TerminalText.snapToWord(grid, pos)
            SelectionMode.LINE -> TerminalText.snapToLine(grid, pos)
            SelectionMode.CELL -> TerminalText.clamp(grid, pos).let { CellRange(it, CellPos(it.row, TerminalText.tailCol(grid.line(it.row), it.col))) }
        }
        apply(emulator, CellRange(minOf(base.start, other.start), maxOf(base.end, other.end)))
    }

    /**
     * Starts moving [handle]: the other end stays put and [moveTo] places this one, swapping ends
     * when the finger crosses. Returns false when the selection is no longer valid on this buffer.
     */
    fun grab(emulator: TerminalEmulator, handle: SelectionHandle): Boolean {
        val now = current(emulator) ?: return false
        mode = SelectionMode.CELL
        val keep = if (handle == SelectionHandle.START) now.end else now.start
        fixed = CellRange(keep, keep)
        dragging = handle
        return true
    }

    /** Puts the held handle at [pos]; the caller holds the lock. */
    fun moveTo(emulator: TerminalEmulator, pos: CellPos) {
        val grid = emulator.grid
        val keep = fixed?.start ?: return
        val p = TerminalText.clamp(grid, pos)
        val range = if (p <= keep) {
            CellRange(p, CellPos(keep.row, TerminalText.tailCol(grid.line(keep.row), keep.col)))
        } else {
            CellRange(keep, CellPos(p.row, TerminalText.tailCol(grid.line(p.row), p.col)))
        }
        dragging = if (p <= keep) SelectionHandle.START else SelectionHandle.END
        apply(emulator, range)
    }

    fun release() {
        dragging = null
    }

    /** Selects every row with content; the caller holds the lock. Clears when the buffer is blank. */
    fun selectAll(emulator: TerminalEmulator) {
        val all = TerminalText.selectAll(emulator.grid)
        if (all == null) {
            clear()
            return
        }
        mode = SelectionMode.LINE
        fixed = all
        apply(emulator, all)
    }

    fun clear() {
        range = null
        anchor = null
        fixed = null
        dragging = null
        summary = ""
    }

    /** The range in the rows of the emulator's buffer now, or null when it was invalidated; the caller holds the lock. */
    fun current(emulator: TerminalEmulator): CellRange? {
        val r = range ?: return null
        val a = anchor ?: return null
        val now = a.translate(r, emulator.linesDropped, emulator.isAlternateScreen, emulator.cols, emulator.rows) ?: return null
        return if (now.end.row < emulator.bufferRows) now else null
    }

    /** The selected text, wrapped rows rejoined and trailing blanks trimmed; empty once the selection is stale. */
    fun text(emulator: TerminalEmulator): String = synchronized(emulator.lock) {
        val now = current(emulator) ?: return ""
        TerminalText.extract(emulator.grid, now)
    }

    private fun apply(emulator: TerminalEmulator, range: CellRange) {
        anchor = BufferAnchor.of(emulator)
        this.range = range
        val text = TerminalText.extract(emulator.grid, range)
        val lines = text.count { it == '\n' } + 1
        summary = if (lines == 1) {
            val n = TerminalText.charCount(text)
            if (n == 1) "1 char" else "$n chars"
        } else {
            "$lines lines"
        }
    }
}

/**
 * Scrollback search (spec C17): the query and its toggles, the matches as buffer ranges under one
 * [BufferAnchor], and which is current. The scroll offset from before the search opened is kept so
 * closing puts the view back where it was.
 */
class TerminalSearch {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var regex by mutableStateOf(false)
    var matches by mutableStateOf<List<CellRange>>(emptyList())
        private set
    var anchor: BufferAnchor? = null
        private set
    var current by mutableIntStateOf(-1)
        private set

    /** Where the view was when the search opened, restored on close. */
    var savedOffset: Int? = null
        private set

    /** Bumps when the caller should run the search again (the toggles, the query, new output). */
    var generation by mutableIntStateOf(0)
        private set

    fun open(prefill: String?, scrollOffset: Int) {
        if (!open) savedOffset = scrollOffset
        if (prefill != null) query = prefill
        open = true
        generation++
    }

    /** Closes the bar; the caller restores the viewport to [savedOffset]. Returns that offset. */
    fun close(): Int? {
        val back = savedOffset
        open = false
        matches = emptyList()
        anchor = null
        current = -1
        savedOffset = null
        return back
    }

    fun invalidate() {
        generation++
    }

    /**
     * Runs the search over the emulator's buffer, holding its lock for the pass. The current match
     * stays on the same text when it is still among the results, else on the nearest after it.
     */
    fun run(emulator: TerminalEmulator) {
        val q = query
        if (!open || q.isEmpty()) {
            matches = emptyList()
            anchor = null
            current = -1
            return
        }
        val previous = currentRange(emulator)
        val (found, at) = synchronized(emulator.lock) {
            ScrollbackSearch.find(emulator.grid, q, caseSensitive, regex) to BufferAnchor.of(emulator)
        }
        matches = found
        anchor = at
        current = when {
            found.isEmpty() -> -1
            previous == null -> found.lastIndex
            else -> found.indexOfFirst { it.start >= previous.start }.let { if (it < 0) found.lastIndex else it }
        }
    }

    /** Moves to the next ([delta] 1) or previous (-1) match, wrapping; returns its range in the buffer's rows now. */
    fun step(delta: Int, emulator: TerminalEmulator): CellRange? {
        val n = matches.size
        if (n == 0) return null
        current = ((current + delta) % n + n) % n
        return currentRange(emulator)
    }

    /** The current match in the rows of the emulator's buffer now, or null. */
    fun currentRange(emulator: TerminalEmulator): CellRange? {
        val a = anchor ?: return null
        val m = matches.getOrNull(current) ?: return null
        return a.translate(m, emulator.linesDropped, emulator.isAlternateScreen, emulator.cols, emulator.rows)
    }

    /** `3/12` for the bar, `0` with no matches, blank with no query. */
    val countLabel: String get() = when {
        query.isEmpty() -> ""
        matches.isEmpty() -> "0"
        else -> "${current + 1}/${matches.size}"
    }
}

/**
 * The buffer cell under a point on the canvas for the view scrolled [scrollOffset] lines into
 * history, clamped onto the grid and onto the head of a wide character; the caller holds the lock.
 */
fun bufferCellAt(emulator: TerminalEmulator, paints: TerminalPaints, scrollOffset: Int, at: Offset): CellPos {
    val view = GridGeometry.cellAt(at.x, at.y, paints.cellWidth, paints.cellHeight, emulator.cols, emulator.rows)
    val offset = scrollOffset.coerceIn(0, emulator.scrollbackSize)
    val row = GridGeometry.bufferRow(view.row, emulator.scrollbackSize, offset).coerceIn(0, (emulator.bufferRows - 1).coerceAtLeast(0))
    return TerminalText.clamp(emulator.grid, CellPos(row, view.col))
}

/** The scroll offset that shows buffer row [row], or the current one when it is already in view. */
fun offsetShowing(emulator: TerminalEmulator, row: Int, scrollOffset: Int): Int {
    val sb = emulator.scrollbackSize
    val offset = scrollOffset.coerceIn(0, sb)
    val view = GridGeometry.viewRow(row, sb, offset)
    return if (view in 0 until emulator.rows) offset else GridGeometry.offsetCentering(row, sb, emulator.rows)
}

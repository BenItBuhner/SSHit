package app.berth.android.ui.terminal

import app.berth.terminal.CellRange
import app.berth.terminal.CursorStyle
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalLine

/**
 * A copy of what one draw of the screen needs: the visible rows' cells, the cursor, the palette and
 * the view's offset into history. [capture] takes it under the emulator's lock in one short pass of
 * array copies, so the frame is then drawn with the lock released and a network read arriving
 * mid-draw can neither tear the picture nor stall the UI thread for the length of a draw. The row
 * store is reused between captures; nothing is allocated once the geometry is stable.
 */
class TerminalFrame {
    var cols: Int = 0
        private set
    var rows: Int = 0
        private set

    /** The offset into history actually shown, clamped to what the emulator had. */
    var offset: Int = 0
        private set
    var cursorX: Int = 0
        private set
    var cursorY: Int = 0
        private set
    var cursorVisible: Boolean = false
        private set
    var cursorStyle: CursorStyle = CursorStyle.DEFAULT
        private set
    var reverseVideo: Boolean = false
        private set
    var scrollbackSize: Int = 0
        private set
    var alternateScreen: Boolean = false
        private set

    /** Lines gone from the top of history at the capture; places a [BufferAnchor]ed range on this frame's rows. */
    var linesDropped: Long = 0L
        private set

    /** The palette as 0xRRGGBB at the time of the capture; OSC 4 can change it between frames. */
    val palette: IntArray = IntArray(256)

    /** [TerminalEmulator] screen version this was captured at, when the caller tracks one; -1 otherwise. */
    var version: Long = -1
        private set

    private var lines: Array<TerminalLine> = emptyArray()

    fun line(y: Int): TerminalLine = lines[y]

    /**
     * Copies the emulator's screen scrolled [scrollOffset] lines into history, tagged [version].
     * Returns the offset used, clamped to the available scrollback.
     */
    fun capture(emulator: TerminalEmulator, scrollOffset: Int, version: Long = -1): Int {
        synchronized(emulator.lock) {
            val r = emulator.rows
            val c = emulator.cols
            if (lines.size != r) {
                val old = lines
                lines = Array(r) { i -> if (i < old.size) old[i] else TerminalLine(c) }
            }
            val used = scrollOffset.coerceIn(0, emulator.scrollbackSize)
            for (y in 0 until r) lines[y].copyFrom(emulator.viewLine(y, used))
            emulator.palette.copyInto(palette)
            cols = c
            rows = r
            offset = used
            cursorX = emulator.cursorX
            cursorY = emulator.cursorY
            cursorVisible = emulator.cursorVisible
            cursorStyle = emulator.cursorStyle
            reverseVideo = emulator.reverseVideo
            scrollbackSize = emulator.scrollbackSize
            alternateScreen = emulator.isAlternateScreen
            linesDropped = emulator.linesDropped
            this.version = version
            return used
        }
    }

    /**
     * Takes a copy of [other], rows and all, in the same short pass of array copies a capture makes;
     * for a second reader of the frame on screen (the accessibility layer) that must keep reading it
     * after the buffers swap and the worker starts writing the next capture over it.
     */
    fun copyFrom(other: TerminalFrame) {
        if (lines.size != other.rows) {
            val old = lines
            lines = Array(other.rows) { i -> if (i < old.size) old[i] else TerminalLine(other.cols) }
        }
        for (y in 0 until other.rows) lines[y].copyFrom(other.line(y))
        other.palette.copyInto(palette)
        cols = other.cols
        rows = other.rows
        offset = other.offset
        cursorX = other.cursorX
        cursorY = other.cursorY
        cursorVisible = other.cursorVisible
        cursorStyle = other.cursorStyle
        reverseVideo = other.reverseVideo
        scrollbackSize = other.scrollbackSize
        alternateScreen = other.alternateScreen
        linesDropped = other.linesDropped
        version = other.version
    }

    /**
     * A range made under [anchor] in this frame's view rows (0 is the top row drawn), or null when
     * the anchor no longer fits the buffer or nothing of the range is in view.
     */
    fun viewRange(range: CellRange, anchor: BufferAnchor): CellRange? {
        val now = anchor.translate(range, linesDropped, alternateScreen, cols, rows) ?: return null
        val top = scrollbackSize - offset
        val shifted = now.shiftRows(-top)
        return if (shifted.end.row < 0 || shifted.start.row >= rows) null else shifted
    }

    /**
     * How many rows the live screen's content will move when the grid is resized to [targetRows]:
     * the shift the emulator's row adjustment produces, positive when history comes back above
     * the content on growing, negative when rows above the cursor go to history on shrinking.
     * Blank rows below the cursor go first, so a short screen shrinking does not move at all. Drawing
     * the frame moved by this while the canvas animates shows the picture the resize will settle on.
     */
    fun rowShiftFor(targetRows: Int): Int {
        if (rows == 0 || targetRows == rows) return 0
        if (targetRows > rows) return if (alternateScreen) 0 else minOf(targetRows - rows, scrollbackSize)
        var toRemove = rows - targetRows
        var last = rows - 1
        while (toRemove > 0 && last > cursorY && lines[last].isBlank()) {
            last--
            toRemove--
        }
        return -toRemove
    }
}

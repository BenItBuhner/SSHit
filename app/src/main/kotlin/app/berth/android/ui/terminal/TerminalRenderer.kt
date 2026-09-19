package app.berth.android.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.Attr
import app.berth.terminal.CellRange
import app.berth.terminal.CursorShape
import app.berth.terminal.TermColor
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalLine
import kotlin.math.roundToInt

/**
 * What one draw highlights over a frame, in the frame's view rows: the selection in the theme's
 * `selection` colour, search matches in the same, the current match in the accent (spec C17, C18).
 * Colours are ARGB as given, so the current match can sit translucent over any theme. One instance
 * is reused between draws; [matches] holds only the ranges that touch the rows in view.
 */
class FrameOverlay {
    var selection: CellRange? = null
    var selectionColor: Int = 0
    val matches = ArrayList<CellRange>()
    var matchColor: Int = 0
    var current: CellRange? = null
    var currentColor: Int = 0

    fun clear() {
        selection = null
        matches.clear()
        current = null
    }

    val isEmpty: Boolean get() = selection == null && matches.isEmpty() && current == null
}

/**
 * Draws terminal frames cell by cell. Shared by the Stage canvas and the theme editor's preview so
 * a theme looks in the editor exactly as it will on stage.
 */
object TerminalRenderer {
    /** For the emulator overload: one capture buffer, since drawing happens on the UI thread. */
    private val scratch = TerminalFrame()

    /**
     * Draws the screen of [emulator] scrolled [scrollOffset] lines into history onto [nc], filling
     * [width] x [height]. Copies the screen under [TerminalEmulator.lock], then draws the copy.
     * Returns the offset actually used, clamped to the available scrollback.
     */
    fun draw(
        nc: Canvas,
        emulator: TerminalEmulator,
        paints: TerminalPaints,
        theme: TerminalTheme,
        boldAsBright: Boolean,
        width: Float,
        height: Float,
        scrollOffset: Int = 0,
        showCursor: Boolean = true,
        focused: Boolean = true,
    ): Int = synchronized(scratch) {
        val used = scratch.capture(emulator, scrollOffset)
        draw(nc, scratch, paints, theme, boldAsBright, width, height, showCursor, focused)
        used
    }

    /**
     * Draws a captured [frame] onto [nc], filling [width] x [height]: backgrounds in runs, then the
     * [overlay]'s selection and search highlights, then text in runs of one style, then the cursor
     * when the frame shows the live screen.
     */
    fun draw(
        nc: Canvas,
        frame: TerminalFrame,
        paints: TerminalPaints,
        theme: TerminalTheme,
        boldAsBright: Boolean,
        width: Float,
        height: Float,
        showCursor: Boolean = true,
        focused: Boolean = true,
        overlay: FrameOverlay? = null,
    ) {
        val palette = frame.palette
        val cw = paints.cellWidth
        val ch = paints.cellHeight
        val boldRgb = theme.bold
        val rows = frame.rows
        val cols = frame.cols
        val reverse = frame.reverseVideo
        val screenBg = if (reverse) theme.foreground else theme.background
        val screenFg = if (reverse) theme.background else theme.foreground
        paints.fill.color = opaque(screenBg)
        nc.drawRect(0f, 0f, width, height, paints.fill)
        val sb = StringBuilder()
        for (y in 0 until rows) {
            val line = frame.line(y)
            val top = y * ch
            val lineCols = minOf(cols, line.cols)

            // Backgrounds, in runs.
            var x = 0
            while (x < lineCols) {
                val bg = cellBg(line.fg[x], line.bg[x], line.attrs[x], palette, screenFg, screenBg)
                var end = x + 1
                while (end < lineCols && cellBg(line.fg[end], line.bg[end], line.attrs[end], palette, screenFg, screenBg) == bg) end++
                if (bg != screenBg) {
                    paints.fill.color = opaque(bg)
                    nc.drawRect(x * cw, top, end * cw, top + ch, paints.fill)
                }
                x = end
            }

            // Selection and search highlights sit between the cell backgrounds and the glyphs, so the
            // text keeps its colour over them (spec: selection text unchanged).
            if (overlay != null) {
                overlay.selection?.let { fillRange(nc, it, y, cols, cw, ch, top, overlay.selectionColor, paints) }
                for (m in overlay.matches) if (m.touches(y)) fillRange(nc, m, y, cols, cw, ch, top, overlay.matchColor, paints)
                overlay.current?.let { if (it.touches(y)) fillRange(nc, it, y, cols, cw, ch, top, overlay.currentColor, paints) }
            }

            // Text, in runs of the same style made of plain single-width characters.
            x = 0
            while (x < lineCols) {
                val cp = line.chars[x]
                val attrs = line.attrs[x]
                if (cp == 0 || attrs and Attr.WIDE_TAIL != 0 || attrs and Attr.INVISIBLE != 0) {
                    x++
                    continue
                }
                val fg = cellFg(line.fg[x], line.bg[x], attrs, palette, screenFg, screenBg, boldAsBright, boldRgb)
                val styleKey = attrs and (Attr.BOLD or Attr.ITALIC or Attr.UNDERLINE or Attr.STRIKETHROUGH)
                val paint = paints.forAttrs(attrs)
                paint.color = opaque(fg)
                sb.setLength(0)
                var end = x
                val simple = isSimple(cp, line, x)
                if (simple) {
                    while (end < lineCols) {
                        val c2 = line.chars[end]
                        val a2 = line.attrs[end]
                        if (a2 and Attr.WIDE_TAIL != 0 || a2 and Attr.INVISIBLE != 0) break
                        if (a2 and (Attr.BOLD or Attr.ITALIC or Attr.UNDERLINE or Attr.STRIKETHROUGH) != styleKey) break
                        if (cellFg(line.fg[end], line.bg[end], a2, palette, screenFg, screenBg, boldAsBright, boldRgb) != fg) break
                        if (c2 == 0) {
                            sb.append(' ')
                            end++
                            continue
                        }
                        if (!isSimple(c2, line, end)) break
                        sb.append(c2.toChar())
                        end++
                    }
                    // Trailing blanks carry no glyphs; trim them from the draw call.
                    var len = sb.length
                    while (len > 0 && sb[len - 1] == ' ') len--
                    if (len > 0) nc.drawText(sb, 0, len, x * cw, top + paints.baseline, paint)
                } else {
                    val text = line.cellText(x)
                    val wide = attrs and Attr.WIDE != 0
                    nc.drawText(text, x * cw, top + paints.baseline, paint)
                    end = x + if (wide) 2 else 1
                }
                if (attrs and Attr.UNDERLINE != 0) {
                    paints.line.color = opaque(fg)
                    val uy = top + paints.baseline + paints.line.strokeWidth * 1.5f
                    nc.drawLine(x * cw, uy, end * cw, uy, paints.line)
                }
                if (attrs and Attr.STRIKETHROUGH != 0) {
                    paints.line.color = opaque(fg)
                    val sy = top + ch * 0.55f
                    nc.drawLine(x * cw, sy, end * cw, sy, paints.line)
                }
                x = maxOf(end, x + 1)
            }
        }

        // Cursor, only on the live screen.
        if (showCursor && frame.offset == 0 && frame.cursorVisible) {
            val cx = frame.cursorX
            val cy = frame.cursorY
            if (cy in 0 until rows && cx in 0 until cols) {
                val left = cx * cw
                val top = cy * ch
                val line = frame.line(cy)
                val wide = cx < line.cols && line.attrs[cx] and Attr.WIDE != 0
                val w = if (wide) cw * 2 else cw
                val shape = frame.cursorStyle.shape
                paints.fill.color = opaque(theme.cursor)
                when {
                    !focused -> {
                        paints.line.color = opaque(theme.cursor)
                        val s = paints.line.strokeWidth
                        nc.drawRect(left + s / 2, top + s / 2, left + w - s / 2, top + ch - s / 2, paints.line.apply { style = Paint.Style.STROKE })
                        paints.line.style = Paint.Style.FILL
                    }
                    shape == CursorShape.BLOCK -> {
                        nc.drawRect(left, top, left + w, top + ch, paints.fill)
                        val cp = if (cx < line.cols) line.chars[cx] else 0
                        if (cp != 0) {
                            val paint = paints.forAttrs(line.attrs[cx])
                            paint.color = opaque(theme.cursorText)
                            nc.drawText(line.cellText(cx), left, top + paints.baseline, paint)
                        }
                    }
                    shape == CursorShape.UNDERLINE -> nc.drawRect(left, top + ch - paints.line.strokeWidth * 2, left + w, top + ch, paints.fill)
                    else -> nc.drawRect(left, top, left + paints.line.strokeWidth * 1.5f, top + ch, paints.fill)
                }
            }
        }
    }

    /**
     * The colour a viewer sees at cell ([col], [row]) of the live screen: the glyph's colour when
     * there is one, otherwise the cell's background. Used by the colour sheet's "pick from preview".
     */
    fun sampleColor(emulator: TerminalEmulator, theme: TerminalTheme, boldAsBright: Boolean, col: Int, row: Int): Int? {
        synchronized(emulator.lock) {
            if (row !in 0 until emulator.rows || col !in 0 until emulator.cols) return null
            val line = emulator.line(row)
            if (col >= line.cols) return theme.background
            val attrs = line.attrs[col]
            val cp = line.chars[col]
            val screenBg = if (emulator.reverseVideo) theme.foreground else theme.background
            val screenFg = if (emulator.reverseVideo) theme.background else theme.foreground
            return if (cp == 0 || cp == 0x20) {
                cellBg(line.fg[col], line.bg[col], attrs, emulator.palette, screenFg, screenBg)
            } else {
                cellFg(line.fg[col], line.bg[col], attrs, emulator.palette, screenFg, screenBg, boldAsBright, theme.bold)
            }
        }
    }

    private fun fillRange(nc: Canvas, range: CellRange, y: Int, cols: Int, cw: Float, ch: Float, top: Float, color: Int, paints: TerminalPaints) {
        val a = range.firstCol(y) ?: return
        val b = range.lastCol(y, cols) ?: return
        if (b < a) return
        paints.fill.color = color
        nc.drawRect(a * cw, top, (b + 1).coerceAtMost(cols) * cw, top + ch, paints.fill)
    }

    private fun isSimple(cp: Int, line: TerminalLine, x: Int): Boolean =
        cp in 0x20..0x7E || (cp in 0xA0..0x24FF && line.attrs[x] and Attr.WIDE == 0 && line.combining?.containsKey(x) != true)

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    private fun resolve(color: Int, isFg: Boolean, attrs: Int, palette: IntArray, screenFg: Int, screenBg: Int, boldAsBright: Boolean): Int =
        when (TermColor.kind(color)) {
            TermColor.KIND_INDEXED -> {
                var i = TermColor.index(color)
                if (isFg && boldAsBright && attrs and Attr.BOLD != 0 && i < 8) i += 8
                palette[i]
            }
            TermColor.KIND_RGB -> TermColor.rgbValue(color)
            else -> if (isFg) screenFg else screenBg
        }

    private fun cellBg(fg: Int, bg: Int, attrs: Int, palette: IntArray, screenFg: Int, screenBg: Int): Int {
        val inverse = attrs and Attr.INVERSE != 0
        return if (inverse) resolve(fg, true, attrs, palette, screenFg, screenBg, false) else resolve(bg, false, attrs, palette, screenFg, screenBg, false)
    }

    /** Foreground after inverse, dim and the theme's bold colour (which applies to default-coloured bold text). */
    private fun cellFg(fg: Int, bg: Int, attrs: Int, palette: IntArray, screenFg: Int, screenBg: Int, boldAsBright: Boolean, boldRgb: Int?): Int {
        val inverse = attrs and Attr.INVERSE != 0
        var c = if (inverse) {
            resolve(bg, false, attrs, palette, screenFg, screenBg, false)
        } else if (boldRgb != null && attrs and Attr.BOLD != 0 && TermColor.kind(fg) != TermColor.KIND_INDEXED && TermColor.kind(fg) != TermColor.KIND_RGB) {
            boldRgb and 0xFFFFFF
        } else {
            resolve(fg, true, attrs, palette, screenFg, screenBg, boldAsBright)
        }
        if (attrs and Attr.DIM != 0) {
            val back = if (inverse) resolve(fg, true, attrs, palette, screenFg, screenBg, false) else resolve(bg, false, attrs, palette, screenFg, screenBg, false)
            c = mix(c, back, 0.4f)
        }
        return c
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

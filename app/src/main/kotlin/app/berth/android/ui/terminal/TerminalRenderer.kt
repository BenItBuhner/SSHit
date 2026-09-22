package app.berth.android.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.Attr
import app.berth.terminal.CellRange
import app.berth.terminal.CursorShape
import app.berth.terminal.TermColor
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalLine
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * What one draw highlights over a frame, in the frame's view rows: the selection in the theme's
 * `selection` colour, search matches in the same, the current match in the accent with its glyphs
 * in [currentFg] (spec C17, C18). Glyphs keep their own colour over the selection and the plain
 * matches; the current match is the one place they are recoloured, so that a green prompt or a
 * coloured listing under the accent still reads. One instance is reused between draws; [matches]
 * holds only the ranges that touch the rows in view.
 */
class FrameOverlay {
    var selection: CellRange? = null

    /** Whether [selection] is a block between its corners rather than a stream from start to end (spec C18). */
    var selectionRectangular: Boolean = false
    var selectionColor: Int = 0
    val matches = ArrayList<CellRange>()
    var matchColor: Int = 0
    var current: CellRange? = null
    var currentColor: Int = 0

    /** The glyph colour inside [current], as 0xRRGGBB: the theme's background, which the accent is chosen to read against. */
    var currentFg: Int = 0

    /**
     * The OSC 8 link a finger is down on, by id, underlined in [linkColor] (the theme's `links`)
     * for as long as it is held (spec A60; links are underlined on hover or press only). 0 for none.
     */
    var pressedLink: Int = 0
    var linkColor: Int = 0

    fun clear() {
        selection = null
        selectionRectangular = false
        matches.clear()
        current = null
        pressedLink = 0
    }

    val isEmpty: Boolean get() = selection == null && matches.isEmpty() && current == null && pressedLink == 0
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
        val glyph = CellGlyph()
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
                overlay.selection?.let { fillRange(nc, it, y, cols, cw, ch, top, overlay.selectionColor, paints, overlay.selectionRectangular) }
                for (m in overlay.matches) if (m.touches(y)) fillRange(nc, m, y, cols, cw, ch, top, overlay.matchColor, paints)
                overlay.current?.let { if (it.touches(y)) fillRange(nc, it, y, cols, cw, ch, top, overlay.currentColor, paints) }
            }

            // The columns of the current search match on this row, if any: glyphs there take the
            // overlay's colour, and a run breaks at the match's edges.
            val curFrom = overlay?.current?.firstCol(y) ?: Int.MAX_VALUE
            val curTo = overlay?.current?.lastCol(y, cols) ?: Int.MIN_VALUE
            val curFg = (overlay?.currentFg ?: 0) and 0xFFFFFF
            fun fgAt(col: Int, attrs: Int): Int =
                if (col in curFrom..curTo) curFg
                else cellFg(line.fg[col], line.bg[col], attrs, palette, screenFg, screenBg, boldAsBright, boldRgb)

            // Text, in runs of the same style made of plain single-width characters.
            x = 0
            while (x < lineCols) {
                val cp = line.chars[x]
                val attrs = line.attrs[x]
                if (cp == 0 || attrs and Attr.WIDE_TAIL != 0 || attrs and Attr.INVISIBLE != 0) {
                    x++
                    continue
                }
                val fg = fgAt(x, attrs)
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
                        if (fgAt(end, a2) != fg) break
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
                    glyph.of(line, x)
                    val wide = attrs and Attr.WIDE != 0
                    if (!wide && isPrivateUse(cp)) {
                        val bg = cellBg(line.fg[x], line.bg[x], attrs, palette, screenFg, screenBg)
                        val next = x + 1
                        val roomy = next < lineCols && (line.chars[next] == 0 || line.chars[next] == 0x20) &&
                            line.attrs[next] and Attr.WIDE_TAIL == 0 &&
                            cellBg(line.fg[next], line.bg[next], line.attrs[next], palette, screenFg, screenBg) == bg &&
                            !(showCursor && frame.offset == 0 && frame.cursorVisible && frame.cursorY == y && frame.cursorX == next)
                        drawPrivateUse(nc, glyph, cp, x * cw, top, cw, ch, if (roomy) 2 else 1, paints.baseline, paint)
                    } else {
                        nc.drawText(glyph.chars, 0, glyph.length, x * cw, top + paints.baseline, paint)
                    }
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

            // The link under a finger: one underline in the theme's links colour per run of its
            // cells on this row, over whatever the cells' own underline was.
            val pressed = overlay?.pressedLink ?: 0
            if (pressed != 0 && line.links != null) {
                paints.line.color = opaque(overlay!!.linkColor)
                val uy = top + paints.baseline + paints.line.strokeWidth * 1.5f
                x = 0
                while (x < lineCols) {
                    if (line.linkAt(x) != pressed) {
                        x++
                        continue
                    }
                    var end = x + 1
                    while (end < lineCols && line.linkAt(end) == pressed) end++
                    nc.drawLine(x * cw, uy, end * cw, uy, paints.line)
                    x = end
                }
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
                            glyph.of(line, cx)
                            if (!wide && isPrivateUse(cp)) drawPrivateUse(nc, glyph, cp, left, top, cw, ch, 1, paints.baseline, paint)
                            else nc.drawText(glyph.chars, 0, glyph.length, left, top + paints.baseline, paint)
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

    /** Fills the cells of [range] on view row [y]: the block's columns on every row when [rectangular], else the stream's. */
    private fun fillRange(nc: Canvas, range: CellRange, y: Int, cols: Int, cw: Float, ch: Float, top: Float, color: Int, paints: TerminalPaints, rectangular: Boolean = false) {
        if (!range.touches(y)) return
        val a = if (rectangular) range.left else range.firstCol(y) ?: return
        val b = if (rectangular) range.right else range.lastCol(y, cols) ?: return
        if (b < a) return
        paints.fill.color = color
        nc.drawRect(a * cw, top, (b + 1).coerceAtMost(cols) * cw, top + ch, paints.fill)
    }

    /** A glyph's advance and ink at a paint's size, measured once per paint and code point. */
    private class GlyphFit(val advance: Float, val ink: Rect)

    private val fits = WeakHashMap<Paint, HashMap<Int, GlyphFit>>()

    private fun fitOf(paint: Paint, cp: Int, glyph: CellGlyph): GlyphFit = synchronized(fits) {
        fits.getOrPut(paint) { HashMap() }.getOrPut(cp) {
            val ink = Rect()
            paint.getTextBounds(glyph.chars, 0, glyph.length, ink)
            GlyphFit(paint.measureText(glyph.chars, 0, glyph.length), ink)
        }
    }

    private fun isPrivateUse(cp: Int): Boolean = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD

    /**
     * Draws a private-use glyph into its cell. A family's own (a patched Nerd Font's, JetBrains
     * Mono's Powerline arrows) is a cell wide and draws as it is. The symbols fallback's are an em
     * wide, most of two cells, and would cover the next glyph: its Powerline dividers are stretched
     * over the cell, one pixel past each side so a segment meets the next without a seam, and its
     * icons are scaled down about their centre to fit [room] cells, two when the next is a blank
     * of the same background, as kitty lets them.
     */
    private fun drawPrivateUse(nc: Canvas, glyph: CellGlyph, cp: Int, left: Float, top: Float, cw: Float, ch: Float, room: Int, baseline: Float, paint: Paint) {
        val fit = fitOf(paint, cp, glyph)
        val ink = fit.ink
        if (fit.advance <= cw * 1.05f || ink.isEmpty) {
            nc.drawText(glyph.chars, 0, glyph.length, left, top + baseline, paint)
            return
        }
        nc.save()
        if (cp in 0xE0B0..0xE0D7) {
            val sx = (cw + 2f) / ink.width()
            val sy = ch / ink.height()
            nc.translate(left - 1f - ink.left * sx, top - ink.top * sy)
            nc.scale(sx, sy)
        } else {
            val avail = cw * room
            val s = minOf(1f, avail / maxOf(fit.advance, ink.width().toFloat()))
            val cx = ink.exactCenterX()
            val cy = ink.exactCenterY()
            nc.translate(left + avail / 2f - s * cx, top + baseline + cy - s * cy)
            nc.scale(s, s)
        }
        nc.drawText(glyph.chars, 0, glyph.length, 0f, 0f, paint)
        nc.restore()
    }

    private fun isSimple(cp: Int, line: TerminalLine, x: Int): Boolean =
        line.combining?.containsKey(x) != true && (cp in 0x20..0x7E || (cp in 0xA0..0x24FF && line.attrs[x] and Attr.WIDE == 0))

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

/**
 * One cell's text as [TerminalLine.cellText] gives it (the code point and any combining marks),
 * written into a buffer reused across the cells of a draw. Box drawing, block elements and braille
 * are drawn a cell at a time; a screen of them made a string for every cell of every frame.
 */
private class CellGlyph {
    var chars = CharArray(4)
        private set
    var length = 0
        private set

    fun of(line: TerminalLine, x: Int) {
        val cp = line.chars[x]
        if (cp == 0) {
            chars[0] = ' '
            length = 1
            return
        }
        val marks = line.combining?.get(x)
        val need = 2 + (marks?.length ?: 0)
        if (chars.size < need) chars = CharArray(need)
        length = Character.toChars(cp, chars, 0)
        if (marks != null) {
            marks.toCharArray(chars, length)
            length += marks.length
        }
    }
}

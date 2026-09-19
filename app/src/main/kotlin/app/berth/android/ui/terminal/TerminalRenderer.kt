package app.berth.android.ui.terminal

import android.graphics.Canvas
import android.graphics.Paint
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.Attr
import app.berth.terminal.CursorShape
import app.berth.terminal.TermColor
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalLine
import kotlin.math.roundToInt

/**
 * Draws terminal frames cell by cell. Shared by the Stage canvas and the theme editor's preview so
 * a theme looks in the editor exactly as it will on stage.
 */
object TerminalRenderer {
    /**
     * Draws the screen of [emulator] scrolled [scrollOffset] lines into history onto [nc], filling
     * [width] x [height]. Takes [TerminalEmulator.lock] for the duration of the frame. Returns the
     * offset actually used, clamped to the available scrollback.
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
    ): Int {
        val palette = emulator.palette
        val cw = paints.cellWidth
        val ch = paints.cellHeight
        val boldRgb = theme.bold
        synchronized(emulator.lock) {
            val rows = emulator.rows
            val cols = emulator.cols
            val offset = scrollOffset.coerceIn(0, emulator.scrollbackSize)
            val reverse = emulator.reverseVideo
            val screenBg = if (reverse) theme.foreground else theme.background
            val screenFg = if (reverse) theme.background else theme.foreground
            paints.fill.color = opaque(screenBg)
            nc.drawRect(0f, 0f, width, height, paints.fill)
            val sb = StringBuilder()
            for (y in 0 until rows) {
                val line = emulator.viewLine(y, offset)
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
            if (showCursor && offset == 0 && emulator.cursorVisible) {
                val cx = emulator.cursorX
                val cy = emulator.cursorY
                if (cy in 0 until rows && cx in 0 until cols) {
                    val left = cx * cw
                    val top = cy * ch
                    val line = emulator.line(cy)
                    val wide = line.attrs[cx] and Attr.WIDE != 0
                    val w = if (wide) cw * 2 else cw
                    val shape = emulator.cursorStyle.shape
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
                            val cp = line.chars[cx]
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
            return offset
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

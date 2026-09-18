package app.berth.android.ui.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import app.berth.android.R
import app.berth.android.session.TerminalSession
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.Attr
import app.berth.terminal.CursorShape
import app.berth.terminal.MouseButton
import app.berth.terminal.MouseTracking
import app.berth.terminal.TermColor
import app.berth.terminal.TerminalKey
import kotlin.math.abs
import kotlin.math.roundToInt

/** Cell geometry and the four text paints for one font configuration. */
class TerminalPaints(context: Context, font: TerminalFont, density: Float, fontScale: Float) {
    val regular = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val bold = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val italic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val boldItalic = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val fill = Paint()
    val line = Paint(Paint.ANTI_ALIAS_FLAG)
    val cellWidth: Float
    val cellHeight: Float
    val baseline: Float

    init {
        val faces = typefaces(context, font.family)
        val px = font.sizeSp.coerceIn(TerminalFont.MIN_SIZE_SP, TerminalFont.MAX_SIZE_SP) * density * fontScale
        regular.typeface = faces[0]
        bold.typeface = faces[1]
        italic.typeface = faces[2]
        boldItalic.typeface = faces[3]
        listOf(regular, bold, italic, boldItalic).forEach {
            it.textSize = px
            it.fontFeatureSettings = if (font.ligatures) "liga, calt" else "-liga, -calt"
        }
        cellWidth = regular.measureText("M")
        val fm = regular.fontMetrics
        val glyphHeight = fm.descent - fm.ascent
        cellHeight = (px * font.lineHeight).roundToInt().toFloat().coerceAtLeast(glyphHeight)
        baseline = (cellHeight - glyphHeight) / 2f - fm.ascent
        line.strokeWidth = (density * 1.25f).coerceAtLeast(1f)
    }

    fun forAttrs(attrs: Int): Paint {
        val b = attrs and Attr.BOLD != 0
        val i = attrs and Attr.ITALIC != 0
        return when {
            b && i -> boldItalic
            b -> bold
            i -> italic
            else -> regular
        }
    }

    private fun typefaces(context: Context, family: String): List<Typeface> {
        fun res(id: Int, fallback: Typeface): Typeface = runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback
        return when (family) {
            "JetBrains Mono" -> listOf(
                res(R.font.jetbrains_mono_regular, Typeface.MONOSPACE),
                res(R.font.jetbrains_mono_bold, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)),
                res(R.font.jetbrains_mono_italic, Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)),
                res(R.font.jetbrains_mono_bold_italic, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)),
            )
            else -> listOf(
                Typeface.MONOSPACE,
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
            )
        }
    }
}

/** How far the view is scrolled into history, in lines; 0 is the live screen. */
class TerminalViewport {
    var scrollOffset by mutableIntStateOf(0)
    var cols by mutableIntStateOf(80)
    var rows by mutableIntStateOf(24)
}

/**
 * Draws a [TerminalSession]'s screen cell by cell on a Canvas, sizes the PTY to the available
 * space, and owns the touch gestures: drag scrolls history (or sends wheel events to full-screen
 * apps), pinch changes the font size, tap focuses and shows the keyboard.
 */
@Composable
fun TerminalCanvas(
    session: TerminalSession,
    theme: TerminalTheme,
    font: TerminalFont,
    sink: TerminalInputSink,
    viewport: TerminalViewport,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showCursor: Boolean = true,
    onFontSizeStep: (Int) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val paints = remember(font, density.density, density.fontScale) { TerminalPaints(context, font, density.density, density.fontScale) }
    val version by session.screenVersion.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentSink by rememberUpdatedState(sink)
    val emulator = session.emulator

    LaunchedEffect(session.id, theme.id) {
        emulator.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
    }
    // A thin trailing accessor so the draw lambda reads the version and subscribes to it.
    val palette = emulator.palette
    val defaultBg = theme.background
    val defaultFg = theme.foreground
    val stepPx = with(density) { 40.dp.toPx() }

    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val cols = (size.width / paints.cellWidth).toInt()
                val rows = (size.height / paints.cellHeight).toInt()
                if (cols >= 2 && rows >= 2) {
                    viewport.cols = cols
                    viewport.rows = rows
                    emulator.cellWidthPx = paints.cellWidth.roundToInt()
                    emulator.cellHeightPx = paints.cellHeight.roundToInt()
                    session.resize(cols, rows)
                }
            }
            .terminalInput(sink)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { handleComposeKeyEvent(it, currentSink) }
            .pointerInput(session.id, paints) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    var mode = GestureMode.NONE
                    var lastY = down.position.y
                    var acc = 0f
                    var lastDist = 0f
                    var zoomAcc = 0f
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (mode == GestureMode.NONE) {
                                val col = (down.position.x / paints.cellWidth).toInt()
                                val row = (down.position.y / paints.cellHeight).toInt()
                                onTap()
                                focusRequester.requestFocus()
                                keyboard?.show()
                                if (emulator.mouseTracking != MouseTracking.NONE) {
                                    emulator.encodeMouse(MouseButton.LEFT, col, row)?.let(session::send)
                                    emulator.encodeMouse(MouseButton.LEFT, col, row, release = true)?.let(session::send)
                                }
                            }
                            break
                        }
                        if (pressed.size >= 2) {
                            val dist = (pressed[0].position - pressed[1].position).getDistance()
                            if (mode != GestureMode.PINCH) {
                                mode = GestureMode.PINCH
                                lastDist = dist
                            } else {
                                zoomAcc += dist - lastDist
                                lastDist = dist
                                while (zoomAcc > stepPx) { onFontSizeStep(1); zoomAcc -= stepPx }
                                while (zoomAcc < -stepPx) { onFontSizeStep(-1); zoomAcc += stepPx }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed[0]
                        when (mode) {
                            GestureMode.PINCH, GestureMode.HORIZONTAL -> Unit
                            GestureMode.NONE -> {
                                if (abs(c.position.y - down.position.y) > slop) {
                                    mode = GestureMode.SCROLL
                                    lastY = c.position.y
                                    c.consume()
                                } else if (abs(c.position.x - down.position.x) > slop) {
                                    mode = GestureMode.HORIZONTAL
                                }
                            }
                            GestureMode.SCROLL -> {
                                acc += c.position.y - lastY
                                lastY = c.position.y
                                val lines = (acc / paints.cellHeight).toInt()
                                if (lines != 0) {
                                    acc -= lines * paints.cellHeight
                                    scrollBy(session, viewport, lines, (c.position.x / paints.cellWidth).toInt(), (c.position.y / paints.cellHeight).toInt())
                                }
                                c.consume()
                            }
                        }
                    }
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") version
        drawRect(Color(0xFF000000.toInt() or defaultBg))
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val cw = paints.cellWidth
            val ch = paints.cellHeight
            synchronized(emulator.lock) {
                val rows = emulator.rows
                val cols = emulator.cols
                val offset = viewport.scrollOffset.coerceIn(0, emulator.scrollbackSize)
                if (offset != viewport.scrollOffset) viewport.scrollOffset = offset
                val reverse = emulator.reverseVideo
                val screenBg = if (reverse) defaultFg else defaultBg
                val screenFg = if (reverse) defaultBg else defaultFg
                if (reverse) {
                    paints.fill.color = opaque(screenBg)
                    nc.drawRect(0f, 0f, size.width, size.height, paints.fill)
                }
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
                        val fg = cellFg(line.fg[x], line.bg[x], attrs, palette, screenFg, screenBg, font.boldAsBright)
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
                                if (cellFg(line.fg[end], line.bg[end], a2, palette, screenFg, screenBg, font.boldAsBright) != fg) break
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
            }
        }
    }
}

private enum class GestureMode { NONE, SCROLL, HORIZONTAL, PINCH }

/** Scrolls history when there is any; otherwise gives full-screen applications wheel or arrow events. */
private fun scrollBy(session: TerminalSession, viewport: TerminalViewport, lines: Int, col: Int, row: Int) {
    val em = session.emulator
    if (em.mouseTracking != MouseTracking.NONE) {
        val button = if (lines > 0) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN
        repeat(abs(lines)) { em.encodeMouse(button, col, row)?.let(session::send) }
        return
    }
    if (em.isAlternateScreen) {
        val key = if (lines > 0) TerminalKey.UP else TerminalKey.DOWN
        repeat(abs(lines)) { session.sendKey(key) }
        return
    }
    viewport.scrollOffset = (viewport.scrollOffset + lines).coerceIn(0, em.scrollbackSize)
}

private fun isSimple(cp: Int, line: app.berth.terminal.TerminalLine, x: Int): Boolean =
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

private fun cellFg(fg: Int, bg: Int, attrs: Int, palette: IntArray, screenFg: Int, screenBg: Int, boldAsBright: Boolean): Int {
    val inverse = attrs and Attr.INVERSE != 0
    var c = if (inverse) resolve(bg, false, attrs, palette, screenFg, screenBg, false) else resolve(fg, true, attrs, palette, screenFg, screenBg, boldAsBright)
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

/** Position of a cell for a point inside the canvas; used by the Stage for tap-to-place features later. */
fun TerminalPaints.cellAt(offset: Offset): Pair<Int, Int> = (offset.x / cellWidth).toInt() to (offset.y / cellHeight).toInt()

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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
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
import app.berth.terminal.MouseButton
import app.berth.terminal.MouseTracking
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
    onTwoFingerSwipe: ((forward: Boolean) -> Unit)? = null,
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

    // Keyed on the theme itself so live edits from the theme editor reach the Stage behind it.
    LaunchedEffect(session.id, theme) {
        emulator.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
    }
    val stepPx = with(density) { 40.dp.toPx() }
    val swipeTravelPx = with(density) { 24.dp.toPx() }
    val swipeSpanPx = with(density) { 12.dp.toPx() }
    val currentSwipe by rememberUpdatedState(onTwoFingerSwipe)

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
                    var zoomed = false
                    // Two-finger swipe (spec C3, Switching): where each finger went down and their span then.
                    val starts = HashMap<PointerId, Offset>()
                    var startSpan = 0f
                    var swipeForward = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            if (mode == GestureMode.SWIPE) currentSwipe?.invoke(swipeForward)
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
                            val a = pressed[0]
                            val b = pressed[1]
                            val dist = (a.position - b.position).getDistance()
                            if (mode != GestureMode.PINCH && mode != GestureMode.SWIPE) {
                                mode = GestureMode.PINCH
                                lastDist = dist
                                startSpan = dist
                                starts.clear()
                                starts[a.id] = a.position
                                starts[b.id] = b.position
                            } else if (mode == GestureMode.PINCH) {
                                // Both fingers travelling the same way with the span held is a swipe, not a pinch;
                                // once a zoom step has fired the gesture stays a pinch.
                                val da = starts[a.id]?.let { a.position.x - it.x }
                                val db = starts[b.id]?.let { b.position.x - it.x }
                                val together = da != null && db != null && (da > 0) == (db > 0) &&
                                    minOf(abs(da), abs(db)) >= swipeTravelPx && abs(dist - startSpan) < swipeSpanPx
                                if (!zoomed && currentSwipe != null && together) {
                                    mode = GestureMode.SWIPE
                                    swipeForward = da!! < 0
                                } else {
                                    zoomAcc += dist - lastDist
                                    lastDist = dist
                                    while (zoomAcc > stepPx) { onFontSizeStep(1); zoomAcc -= stepPx; zoomed = true }
                                    while (zoomAcc < -stepPx) { onFontSizeStep(-1); zoomAcc += stepPx; zoomed = true }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed[0]
                        when (mode) {
                            GestureMode.PINCH, GestureMode.HORIZONTAL, GestureMode.SWIPE -> Unit
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
        drawIntoCanvas { canvas ->
            val used = TerminalRenderer.draw(
                nc = canvas.nativeCanvas,
                emulator = emulator,
                paints = paints,
                theme = theme,
                boldAsBright = font.boldAsBright,
                width = size.width,
                height = size.height,
                scrollOffset = viewport.scrollOffset,
                showCursor = showCursor,
                focused = focused,
            )
            if (used != viewport.scrollOffset) viewport.scrollOffset = used
        }
    }
}

private enum class GestureMode { NONE, SCROLL, HORIZONTAL, PINCH, SWIPE }

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

/** Position of a cell for a point inside the canvas; used by the Stage for tap-to-place features later. */
fun TerminalPaints.cellAt(offset: Offset): Pair<Int, Int> = (offset.x / cellWidth).toInt() to (offset.y / cellHeight).toInt()

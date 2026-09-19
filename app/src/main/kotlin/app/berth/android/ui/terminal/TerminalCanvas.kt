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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import app.berth.android.R
import app.berth.android.session.TerminalSession
import app.berth.android.ui.theme.Berth
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.Attr
import app.berth.terminal.CellRange
import app.berth.terminal.MouseButton
import app.berth.terminal.MouseTracking
import app.berth.terminal.SelectionMode
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cell geometry and the four text paints for one font configuration. Typefaces come from a
 * process-wide cache by family, so a pinch that steps the size a dozen times never reads a font
 * file twice; instances themselves are cached by [TerminalPaintsCache] and shared.
 */
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
        val faces = TypefaceCache.forFamily(context, font.family)
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
}

/** The regular, bold, italic and bold-italic faces of each family, read from resources once per process. */
private object TypefaceCache {
    private val faces = HashMap<String, List<Typeface>>()

    @Synchronized
    fun forFamily(context: Context, family: String): List<Typeface> = faces.getOrPut(family) { load(context.applicationContext, family) }

    private fun load(context: Context, family: String): List<Typeface> {
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

/**
 * [TerminalPaints] by font, density and font scale, most recently used kept: a pinch steps the
 * size up and back down through sizes already measured, and every canvas at one size shares one
 * set. Paints are only ever used on the UI thread, so sharing them is safe.
 */
object TerminalPaintsCache {
    private data class Key(val font: TerminalFont, val density: Float, val fontScale: Float)

    private val lru = object : LinkedHashMap<Key, TerminalPaints>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TerminalPaints>): Boolean = size > CAPACITY
    }

    @Synchronized
    fun get(context: Context, font: TerminalFont, density: Float, fontScale: Float): TerminalPaints =
        lru.getOrPut(Key(font, density, fontScale)) { TerminalPaints(context, font, density, fontScale) }

    private const val CAPACITY = 12
}

/** The cached [TerminalPaints] for [font] at the current density. */
@Composable
fun rememberTerminalPaints(font: TerminalFont): TerminalPaints {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(font, density.density, density.fontScale) { TerminalPaintsCache.get(context, font, density.density, density.fontScale) }
}

/** How far the view is scrolled into history, in lines; 0 is the live screen. */
class TerminalViewport {
    var scrollOffset by mutableIntStateOf(0)
    var cols by mutableIntStateOf(80)
    var rows by mutableIntStateOf(24)
}

/**
 * Two [TerminalFrame]s: the front one is what the canvas draws, the back one is where the next
 * capture lands, off the UI thread, before the two are swapped between draws. The UI thread never
 * waits on the emulator's lock this way; a network read arriving mid-capture delays the next frame,
 * not the one on screen.
 */
class FrameBuffers {
    var front: TerminalFrame = TerminalFrame()
        private set
    var back: TerminalFrame = TerminalFrame()
        private set

    fun swap() {
        val t = front
        front = back
        back = t
    }
}

/**
 * Draws a [TerminalSession]'s screen cell by cell on a Canvas, sizes the PTY to the available
 * space, and owns the touch gestures: drag scrolls history (or sends wheel events to full-screen
 * apps), pinch changes the font size, tap focuses and shows the keyboard, long-press selects a
 * word and places handles, double-tap selects a word, double-tap and drag selects lines, a
 * two-finger tap pastes (spec C18, D1).
 *
 * Output never recomposes the canvas: frames are captured on a worker as the screen version
 * changes and a tick state read in the draw scope alone invalidates the drawing.
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
    onTwoFingerTap: (() -> Unit)? = null,
    selection: TerminalSelection? = null,
    search: TerminalSearch? = null,
    onSelectionStarted: () -> Unit = {},
) {
    val density = LocalDensity.current
    val paints = rememberTerminalPaints(font)
    val paintsState = rememberUpdatedState(paints)
    val keyboard = LocalSoftwareKeyboardController.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val currentSink by rememberUpdatedState(sink)
    val emulator = session.emulator
    val frames = remember(session.id) { FrameBuffers() }
    var frameTick by remember(session.id) { mutableIntStateOf(0) }
    val overlay = remember { FrameOverlay() }
    val accent = Berth.colors.accent.toArgb()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Keyed on the theme itself so live edits from the theme editor reach the Stage behind it.
    LaunchedEffect(session.id, theme) {
        emulator.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
    }
    // The grid follows the canvas, the font and the session. The first size for a session lands at
    // once; later ones settle first, because the keyboard and the Deck animate the canvas through a
    // dozen sizes and a pinch steps the font through as many, and only the last is worth a resize
    // of the PTY and a reflow of history.
    LaunchedEffect(session.id) {
        var first = true
        snapshotFlow { canvasSize to paintsState.value }.filter { it.first.width > 0 && it.first.height > 0 }.collectLatest { (size, p) ->
            if (!first) delay(RESIZE_SETTLE_MS)
            first = false
            val cols = (size.width / p.cellWidth).toInt()
            val rows = (size.height / p.cellHeight).toInt()
            if (cols >= 2 && rows >= 2) {
                viewport.cols = cols
                viewport.rows = rows
                emulator.cellWidthPx = p.cellWidth.roundToInt()
                emulator.cellHeightPx = p.cellHeight.roundToInt()
                session.resize(cols, rows)
            }
        }
    }
    // Frames: every change of the screen or of the view's offset is captured into the back buffer
    // on a worker, then swapped in and the draw invalidated. Conflation folds a burst of output into
    // as many captures as the UI thread can draw.
    LaunchedEffect(session.id) {
        combine(session.screenVersion, snapshotFlow { viewport.scrollOffset }) { v, o -> v to o }
            .conflate()
            .collect { (version, wanted) ->
                val used = withContext(Dispatchers.Default) { frames.back.capture(emulator, wanted, version) }
                frames.swap()
                if (used != wanted) viewport.scrollOffset = used
                frameTick++
            }
    }
    // A search follows the buffer: new output while the bar is open re-runs it, settled a little.
    if (search != null) {
        LaunchedEffect(session.id, search) {
            combine(session.screenVersion, snapshotFlow { search.generation }) { _, g -> g }
                .collectLatest {
                    if (!search.open) return@collectLatest
                    delay(SEARCH_SETTLE_MS)
                    withContext(Dispatchers.Default) { search.run(emulator) }
                }
        }
    }
    val stepPx = with(density) { 40.dp.toPx() }
    val swipeTravelPx = with(density) { 24.dp.toPx() }
    val swipeSpanPx = with(density) { 12.dp.toPx() }
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }
    val handleReachPx = with(density) { HANDLE_REACH.toPx() }
    val currentSwipe by rememberUpdatedState(onTwoFingerSwipe)
    val currentTwoFingerTap by rememberUpdatedState(onTwoFingerTap)
    val currentSelectionStarted by rememberUpdatedState(onSelectionStarted)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentFontStep by rememberUpdatedState(onFontSizeStep)
    val currentSelection by rememberUpdatedState(selection)

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Terminal" }
            .onSizeChanged { canvasSize = it }
            .terminalInput(sink)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { handleComposeKeyEvent(it, currentSink) }
            // Keyed on the session alone: a pinch changes the paints a dozen times and the gesture
            // must not restart under the fingers.
            .pointerInput(session.id) {
                var lastTapUp = 0L
                var lastTapAt = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val p = paintsState.value
                    val sel = currentSelection
                    val slop = viewConfiguration.touchSlop
                    val place: (Offset) -> Unit = { at ->
                        synchronized(emulator.lock) { sel?.let { it.extendTo(emulator, bufferCellAt(emulator, p, viewport.scrollOffset, at)) } }
                    }

                    // A handle under the finger: drag that end of the selection.
                    val handle = if (sel != null) handleAt(sel, frames.front, down.position, p, handleRadiusPx, handleReachPx) else null
                    if (sel != null && handle != null && synchronized(emulator.lock) { sel.grab(emulator, handle) }) {
                        down.consume()
                        dragSelection(emulator, viewport, size.height.toFloat()) { at ->
                            synchronized(emulator.lock) { sel.moveTo(emulator, bufferCellAt(emulator, p, viewport.scrollOffset, at)) }
                        }
                        sel.release()
                        return@awaitEachGesture
                    }

                    // The second tap of a double tap: a release selects the word, a drag selects lines.
                    val secondTap = lastTapUp != 0L && down.uptimeMillis - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis &&
                        (down.position - lastTapAt).getDistance() <= slop * 2
                    lastTapUp = 0L

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
                    var twoTravel = 0f
                    while (true) {
                        val event = if (mode == GestureMode.NONE && sel != null) {
                            val left = viewConfiguration.longPressTimeoutMillis - (currentEventUptime() - down.uptimeMillis)
                            withTimeoutOrNull(left.coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Main) }
                        } else {
                            awaitPointerEvent(PointerEventPass.Main)
                        }
                        if (event == null) {
                            // Long-press: a word selection at the pressed cell, then a drag grows it by words.
                            synchronized(emulator.lock) { sel!!.start(emulator, bufferCellAt(emulator, p, viewport.scrollOffset, down.position), SelectionMode.WORD) }
                            currentSelectionStarted()
                            dragSelection(emulator, viewport, size.height.toFloat(), place)
                            return@awaitEachGesture
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            val up = event.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                            when (mode) {
                                GestureMode.SWIPE -> currentSwipe?.invoke(swipeForward)
                                GestureMode.PINCH -> if (!zoomed && twoTravel < slop && up - down.uptimeMillis < TAP_MS) currentTwoFingerTap?.invoke()
                                GestureMode.NONE -> {
                                    when {
                                        // A tap away from the handles dismisses a selection and does nothing else.
                                        sel?.active == true -> sel.clear()
                                        secondTap && sel != null -> {
                                            synchronized(emulator.lock) { sel.start(emulator, bufferCellAt(emulator, p, viewport.scrollOffset, down.position), SelectionMode.WORD) }
                                            currentSelectionStarted()
                                        }
                                        else -> {
                                            lastTapUp = up
                                            lastTapAt = down.position
                                            val col = (down.position.x / p.cellWidth).toInt()
                                            val row = (down.position.y / p.cellHeight).toInt()
                                            currentOnTap()
                                            focusRequester.requestFocus()
                                            keyboard?.show()
                                            if (emulator.mouseTracking != MouseTracking.NONE) {
                                                emulator.encodeMouse(MouseButton.LEFT, col, row)?.let(session::send)
                                                emulator.encodeMouse(MouseButton.LEFT, col, row, release = true)?.let(session::send)
                                            }
                                        }
                                    }
                                }
                                else -> Unit
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
                                twoTravel = maxOf(twoTravel, starts[a.id]?.let { (a.position - it).getDistance() } ?: 0f, starts[b.id]?.let { (b.position - it).getDistance() } ?: 0f)
                                val together = da != null && db != null && (da > 0) == (db > 0) &&
                                    minOf(abs(da), abs(db)) >= swipeTravelPx && abs(dist - startSpan) < swipeSpanPx
                                if (!zoomed && currentSwipe != null && together && da != null) {
                                    mode = GestureMode.SWIPE
                                    swipeForward = da < 0
                                } else {
                                    zoomAcc += dist - lastDist
                                    lastDist = dist
                                    while (zoomAcc > stepPx) { currentFontStep(1); zoomAcc -= stepPx; zoomed = true }
                                    while (zoomAcc < -stepPx) { currentFontStep(-1); zoomAcc += stepPx; zoomed = true }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val c = pressed[0]
                        when (mode) {
                            GestureMode.PINCH, GestureMode.HORIZONTAL, GestureMode.SWIPE -> Unit
                            GestureMode.NONE -> {
                                val dx = c.position.x - down.position.x
                                val dy = c.position.y - down.position.y
                                if (abs(dx) > slop || abs(dy) > slop) {
                                    if (secondTap && sel != null) {
                                        // Double-tap and drag: whole lines from the tapped one to the finger.
                                        synchronized(emulator.lock) { sel.start(emulator, bufferCellAt(emulator, p, viewport.scrollOffset, down.position), SelectionMode.LINE) }
                                        currentSelectionStarted()
                                        c.consume()
                                        dragSelection(emulator, viewport, size.height.toFloat(), place)
                                        return@awaitEachGesture
                                    }
                                    if (abs(dy) > slop) {
                                        mode = GestureMode.SCROLL
                                        lastY = c.position.y
                                        c.consume()
                                    } else {
                                        mode = GestureMode.HORIZONTAL
                                    }
                                }
                            }
                            GestureMode.SCROLL -> {
                                acc += c.position.y - lastY
                                lastY = c.position.y
                                val lines = (acc / p.cellHeight).toInt()
                                if (lines != 0) {
                                    acc -= lines * p.cellHeight
                                    scrollBy(session, viewport, lines, (c.position.x / p.cellWidth).toInt(), (c.position.y / p.cellHeight).toInt())
                                }
                                c.consume()
                            }
                        }
                    }
                }
            },
    ) {
        drawIntoCanvas { canvas ->
            // Read here and nowhere in composition: output invalidates this draw and nothing above it.
            @Suppress("UNUSED_VARIABLE") val tick = frameTick
            val frame = frames.front
            // The very first draw of a session has no captured frame yet; one synchronous capture
            // avoids a blank flash on staging, after which the worker keeps the buffers filled.
            if (frame.rows == 0) frame.capture(emulator, viewport.scrollOffset, -1)
            val nc = canvas.nativeCanvas
            val ch = paints.cellHeight
            // Selection and search highlights for the rows in view, in this frame's row space.
            overlay.clear()
            selection?.range?.let { r -> selection.anchor?.let { a -> overlay.selection = frame.viewRange(r, a) } }
            overlay.selectionColor = opaqueRgb(theme.selection)
            if (search != null && search.open) {
                val a = search.anchor
                val ms = search.matches
                val cur = search.current
                if (a != null) {
                    for ((i, m) in ms.withIndex()) {
                        val v = frame.viewRange(m, a) ?: continue
                        if (i == cur) overlay.current = v else overlay.matches.add(v)
                    }
                }
                overlay.matchColor = opaqueRgb(theme.selection)
                overlay.currentColor = (accent and 0xFFFFFF) or (CURRENT_MATCH_ALPHA shl 24)
            }
            val ov = if (overlay.isEmpty) null else overlay
            // While the canvas is animating to a new height and the grid has not settled yet, the rows
            // are drawn where the coming resize will put them, so the picture slides with the keyboard
            // or the Deck instead of clipping at the bottom and jumping when the grid catches up.
            val targetRows = (size.height / ch).toInt()
            val shift = if (frame.offset == 0 && targetRows >= 2) frame.rowShiftFor(targetRows) else 0
            if (shift != 0) {
                paints.fill.color = 0xFF000000.toInt() or ((if (frame.reverseVideo) theme.foreground else theme.background) and 0xFFFFFF)
                nc.drawRect(0f, 0f, size.width, size.height, paints.fill)
                val saved = nc.save()
                nc.translate(0f, shift * ch)
                TerminalRenderer.draw(nc, frame, paints, theme, font.boldAsBright, size.width, frame.rows * ch, showCursor, focused, ov)
                overlay.selection?.let { drawHandles(nc, it, frame, paints, accent, handleRadiusPx) }
                nc.restoreToCount(saved)
            } else {
                TerminalRenderer.draw(nc, frame, paints, theme, font.boldAsBright, size.width, size.height, showCursor, focused, ov)
                overlay.selection?.let { drawHandles(nc, it, frame, paints, accent, handleRadiusPx) }
            }
        }
    }
}

/** How long the canvas must hold a size before the grid follows it; a few frames of any animation. */
private const val RESIZE_SETTLE_MS = 80L

/** How long new output rests before an open search runs again over the buffer. */
private const val SEARCH_SETTLE_MS = 150L

/** A two-finger press released within this, without travel, is a tap (paste with preview). */
private const val TAP_MS = 300L

/** While a selection drag holds the finger past an edge, history scrolls one line per this. */
private const val AUTOSCROLL_MS = 60L

/** The current match is the accent at this alpha over the cell, so the glyph stays legible on any theme. */
private const val CURRENT_MATCH_ALPHA = 0x99

private val HANDLE_RADIUS = 9.dp
private val HANDLE_REACH = 24.dp

private enum class GestureMode { NONE, SCROLL, HORIZONTAL, PINCH, SWIPE }

private fun opaqueRgb(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

/** The last event's time when one is in flight, else now; for the long-press deadline across several events. */
private fun AwaitPointerEventScope.currentEventUptime(): Long =
    currentEvent.changes.firstOrNull()?.uptimeMillis ?: android.os.SystemClock.uptimeMillis()

/**
 * Follows one finger with [place] until it lifts. Held past the top or bottom edge, the view scrolls
 * a line that way every [AUTOSCROLL_MS] and the selection follows, so a drag reaches into history.
 */
private suspend fun AwaitPointerEventScope.dragSelection(
    emulator: TerminalEmulator,
    viewport: TerminalViewport,
    height: Float,
    place: (Offset) -> Unit,
) {
    var last: Offset? = null
    while (true) {
        val held = last
        val outside = held != null && (held.y < 0f || held.y > height)
        val event = if (outside) withTimeoutOrNull(AUTOSCROLL_MS) { awaitPointerEvent(PointerEventPass.Main) } else awaitPointerEvent(PointerEventPass.Main)
        if (event == null) {
            val at = held!!
            val by = if (at.y < 0f) 1 else -1
            val next = (viewport.scrollOffset + by).coerceIn(0, emulator.scrollbackSize)
            if (next != viewport.scrollOffset) {
                viewport.scrollOffset = next
                place(at)
            }
            continue
        }
        val c = event.changes.firstOrNull { it.pressed } ?: break
        c.consume()
        last = c.position
        place(c.position)
    }
}

/** Where the selection's handles hang for [frame]: the start cell's bottom-left corner and the end cell's bottom-right, or null off screen. */
private fun handleCenters(range: CellRange, frame: TerminalFrame, paints: TerminalPaints, radius: Float): Pair<Offset?, Offset?> {
    val cw = paints.cellWidth
    val ch = paints.cellHeight
    val start = if (range.start.row in 0 until frame.rows) Offset(range.start.col * cw, (range.start.row + 1) * ch + radius) else null
    val end = if (range.end.row in 0 until frame.rows) Offset((range.end.col + 1) * cw, (range.end.row + 1) * ch + radius) else null
    return start to end
}

private fun handleAt(selection: TerminalSelection, frame: TerminalFrame, at: Offset, paints: TerminalPaints, radius: Float, reach: Float): SelectionHandle? {
    val range = selection.range ?: return null
    val anchor = selection.anchor ?: return null
    val view = frame.viewRange(range, anchor) ?: return null
    val (s, e) = handleCenters(view, frame, paints, radius)
    val ds = s?.let { (at - it).getDistance() } ?: Float.MAX_VALUE
    val de = e?.let { (at - it).getDistance() } ?: Float.MAX_VALUE
    return when {
        ds > reach && de > reach -> null
        ds <= de -> SelectionHandle.START
        else -> SelectionHandle.END
    }
}

/** Two accent teardrops under the selection's ends: a disc with a square shoulder toward the text, the classic shape, no magnifier. */
private fun drawHandles(nc: android.graphics.Canvas, view: CellRange, frame: TerminalFrame, paints: TerminalPaints, color: Int, radius: Float) {
    val (s, e) = handleCenters(view, frame, paints, radius)
    paints.fill.color = opaqueRgb(color)
    s?.let {
        nc.drawCircle(it.x, it.y, radius, paints.fill)
        nc.drawRect(it.x - radius, it.y - radius, it.x, it.y, paints.fill)
    }
    e?.let {
        nc.drawCircle(it.x, it.y, radius, paints.fill)
        nc.drawRect(it.x, it.y - radius, it.x + radius, it.y, paints.fill)
    }
}

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

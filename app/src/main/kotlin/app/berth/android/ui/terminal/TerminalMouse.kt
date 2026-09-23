package app.berth.android.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import app.berth.android.session.TerminalSession
import app.berth.domain.model.SessionState
import app.berth.terminal.CellPos
import app.berth.terminal.Mod
import app.berth.terminal.MouseButton
import app.berth.terminal.MouseTracking
import app.berth.terminal.SelectionMode
import app.berth.terminal.TerminalEmulator
import kotlin.math.abs

/** What the mouse on a canvas reaches beyond the session: the Stage's callbacks and the canvas's own state. */
internal class MouseHooks(
    val selection: () -> TerminalSelection?,
    val onSelectionStarted: () -> Unit,
    /** A left click that is not the application's and not on a link: focus and the keyboard, as a tap. */
    val onClick: () -> Unit,
    val onLinkClick: () -> ((LinkTap) -> Unit)?,
    /** A right click the application does not take; null leaves it doing nothing (Settings › Gestures). */
    val onSecondaryClick: () -> (() -> Unit)?,
    /** The link under the pointer, underlined while it is there, and the pointer's icon over the cell. */
    val onHover: (link: Int, icon: PointerIcon) -> Unit,
)

/**
 * A mouse or a trackpad on the terminal, the pointer Compose reports as [PointerType.Mouse] (a
 * trackpad's finger included). While the application tracks the mouse (DECSET 9, 1000, 1002, 1003)
 * its buttons, the wheel and a trackpad's two-finger swipe are the application's, as the
 * [TerminalSession.emulator]'s mode takes them: drags only under 1002 and 1003, and motion with no
 * button held only under 1003, one report per cell. Shift keeps them the terminal's, as in xterm.
 * Otherwise the wheel scrolls history three lines a notch (arrows on the alternate screen), a
 * left drag selects, a double click a word and a triple click a line, a click on an OSC 8 link opens
 * it, and a right click is [MouseHooks.onSecondaryClick]. Touches never come here.
 *
 * A tab that is not Live hears nothing from the mouse, whatever mode its frame was left in: a
 * pointer drifting over a detached tab must not reconnect it the way a key does, so the wheel
 * and a swipe move its history only and a click is a click of the terminal's own.
 */
internal suspend fun PointerInputScope.terminalMouse(
    session: TerminalSession,
    viewport: TerminalViewport,
    frames: FrameBuffers,
    paints: () -> TerminalPaints,
    hooks: MouseHooks,
) = awaitPointerEventScope {
    val emulator = session.emulator
    // Buttons held after the last event, and those of them whose press the application was sent,
    // so it gets their drags and releases too, whatever the modifiers are by then.
    var held = 0
    var reported = 0
    // The cell of the last motion report, so a pointer crossing a cell reports it once.
    var reportedAt = -1L
    var wheel = 0f
    var pan = 0f
    var drag: MouseDrag? = null
    var lastPressAt = 0L
    var lastPressCell = -1L
    var clicks = 0
    while (true) {
        val d = drag
        val outside = d != null && d.selecting && (d.at.y < 0f || d.at.y > size.height)
        val event = if (outside) withTimeoutOrNull(AUTOSCROLL_MS) { awaitPointerEvent(PointerEventPass.Main) } else awaitPointerEvent(PointerEventPass.Main)
        if (event == null) {
            // Held past the top or bottom edge: history scrolls a line that way and the selection follows.
            val by = if (d!!.at.y < 0f) 1 else -1
            val next = (viewport.scrollOffset + by).coerceIn(0, emulator.scrollbackSize)
            if (next != viewport.scrollOffset) {
                viewport.scrollOffset = next
                d.extend(emulator, paints(), viewport)
            }
            continue
        }
        val c = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
        val p = paints()
        val col = (c.position.x / p.cellWidth).toInt().coerceAtLeast(0)
        val row = (c.position.y / p.cellHeight).toInt().coerceAtLeast(0)
        val cell = pack(col, row)
        val mods = modifiersOf(event.keyboardModifiers)
        val live = session.state == SessionState.LIVE
        if (!live) reported = 0
        val tracking = live && emulator.mouseTracking != MouseTracking.NONE && !event.keyboardModifiers.isShiftPressed
        when (event.type) {
            PointerEventType.Scroll -> {
                // Compose's delta is negative for a notch away from the user, which here is into history.
                wheel -= c.scrollDelta.y
                val notches = wheel.toInt()
                if (notches != 0) {
                    wheel -= notches
                    if (live) wheelBy(session, viewport, notches, col, row, mods, tracking) else scrollHistory(viewport, emulator, notches * WHEEL_LINES)
                }
                c.consume()
                continue
            }
            PointerEventType.PanStart, PointerEventType.PanMove, PointerEventType.PanEnd -> {
                // A trackpad's two-finger swipe: its offset is the distance to scroll, the drag's opposite.
                pan -= c.panOffset.y + c.historical.fold(0f) { sum, h -> sum + h.panOffset.y }
                val lines = (pan / p.cellHeight).toInt()
                if (lines != 0) {
                    pan -= lines * p.cellHeight
                    if (live) scrollBy(session, viewport, lines, col, row, report = tracking) else scrollHistory(viewport, emulator, lines)
                }
                if (event.type == PointerEventType.PanEnd) pan = 0f
                c.consume()
                continue
            }
            PointerEventType.Exit -> {
                reportedAt = -1L
                hooks.onHover(0, PointerIcon.Text)
                continue
            }
            else -> Unit
        }

        val now = buttonsOf(event)
        val pressedNow = now and held.inv()
        val releasedNow = held and now.inv()
        held = now

        for (i in BUTTONS.indices) {
            val bit = 1 shl i
            if (pressedNow and bit == 0) continue
            c.consume()
            if (tracking) {
                hooks.selection()?.takeIf { it.active }?.clear()
                emulator.encodeMouse(BUTTONS[i], col, row, mods)?.let {
                    session.send(it)
                    reported = reported or bit
                    reportedAt = cell
                }
                if (i == LEFT) hooks.onClick()
                continue
            }
            if (i != LEFT) continue
            // A second press on the same cell within the double-tap window is a double click, a third a triple.
            clicks = if (c.uptimeMillis - lastPressAt <= viewConfiguration.doubleTapTimeoutMillis && cell == lastPressCell) clicks % 3 + 1 else 1
            lastPressAt = c.uptimeMillis
            lastPressCell = cell
            val sel = hooks.selection()
            val start = synchronized(emulator.lock) { bufferCellAt(emulator, p, viewport.scrollOffset, c.position) }
            val next = MouseDrag(sel, start, cell, c.position)
            if (sel != null && clicks > 1) {
                synchronized(emulator.lock) { sel.start(emulator, start, if (clicks == 2) SelectionMode.WORD else SelectionMode.LINE) }
                next.selecting = true
                hooks.onSelectionStarted()
            }
            drag = next
        }

        if (pressedNow == 0 && releasedNow == 0 && (event.type == PointerEventType.Move || event.type == PointerEventType.Enter)) {
            val dragging = drag
            when {
                dragging != null && held and (1 shl LEFT) != 0 -> {
                    dragging.at = c.position
                    val sel = dragging.selection
                    if (!dragging.selecting && cell != dragging.cell && sel != null) {
                        synchronized(emulator.lock) { sel.start(emulator, dragging.start, SelectionMode.CELL) }
                        dragging.selecting = true
                        hooks.onSelectionStarted()
                    }
                    if (dragging.selecting) dragging.extend(emulator, p, viewport)
                    c.consume()
                }
                reported != 0 -> {
                    if (cell != reportedAt) {
                        val button = BUTTONS[Integer.numberOfTrailingZeros(reported)]
                        emulator.encodeMouse(button, col, row, mods, motion = true)?.let(session::send)
                        reportedAt = cell
                    }
                    c.consume()
                }
                held == 0 && tracking -> {
                    // Hover: only 1003 asks for it; 1000 and 1002 hear nothing until a button goes down.
                    if (cell != reportedAt && emulator.mouseTracking == MouseTracking.ANY_EVENT) {
                        emulator.encodeMouse(MouseButton.RELEASE, col, row, mods, motion = true)?.let(session::send)
                        reportedAt = cell
                    }
                    hooks.onHover(0, PointerIcon.Default)
                }
                held == 0 -> {
                    val link = if (hooks.onLinkClick() != null && emulator.mouseTracking == MouseTracking.NONE) frames.front.linkAt(row, col) else 0
                    hooks.onHover(link, if (link != 0) PointerIcon.Hand else PointerIcon.Text)
                }
            }
        }

        for (i in BUTTONS.indices) {
            val bit = 1 shl i
            if (releasedNow and bit == 0) continue
            c.consume()
            if (reported and bit != 0) {
                reported = reported and bit.inv()
                emulator.encodeMouse(BUTTONS[i], col, row, mods, release = true)?.let(session::send)
                continue
            }
            when (i) {
                LEFT -> {
                    val done = drag
                    drag = null
                    if (done == null || done.selecting) continue
                    // A click: it dismisses a selection, opens a link, or focuses the terminal.
                    val sel = hooks.selection()
                    val linkTap = hooks.onLinkClick()
                    val link = if (linkTap != null && emulator.mouseTracking == MouseTracking.NONE) frames.front.linkAt(row, col) else 0
                    val url = if (link != 0) emulator.links.url(link) else null
                    when {
                        sel?.active == true -> sel.clear()
                        url != null && linkTap != null -> linkTap(LinkTap(url, frames.front.linkText(row, col)))
                        else -> hooks.onClick()
                    }
                }
                RIGHT -> if (!tracking) hooks.onSecondaryClick()?.invoke()
            }
        }
    }
}

/** A left-button drag of the terminal's own: the cell it went down on, and whether it has become a selection. */
private class MouseDrag(val selection: TerminalSelection?, val start: CellPos, val cell: Long, var at: Offset) {
    var selecting = false

    fun extend(emulator: TerminalEmulator, paints: TerminalPaints, viewport: TerminalViewport) {
        val sel = selection ?: return
        synchronized(emulator.lock) { sel.extendTo(emulator, bufferCellAt(emulator, paints, viewport.scrollOffset, at)) }
    }
}

/**
 * [notches] of the wheel, positive toward history: one wheel report a notch when the application
 * [tracking] the mouse takes wheels (X10 does not), else [WHEEL_LINES] a notch through [scrollBy].
 */
private fun wheelBy(session: TerminalSession, viewport: TerminalViewport, notches: Int, col: Int, row: Int, mods: Int, tracking: Boolean) {
    if (tracking) {
        val bytes = session.emulator.encodeMouse(if (notches > 0) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN, col, row, mods)
        if (bytes != null) {
            repeat(abs(notches)) { session.send(bytes) }
            return
        }
    }
    scrollBy(session, viewport, notches * WHEEL_LINES, col, row, report = false)
}

/** [lines] of history, positive toward it, and nothing sent: the wheel and a swipe on a tab that is not Live. */
private fun scrollHistory(viewport: TerminalViewport, emulator: TerminalEmulator, lines: Int) {
    viewport.scrollOffset = (viewport.scrollOffset + lines).coerceIn(0, emulator.scrollbackSize)
}

/** The held buttons as bits, in [BUTTONS] order. */
private fun buttonsOf(event: PointerEvent): Int {
    val b = event.buttons
    return (if (b.isPrimaryPressed) 1 shl LEFT else 0) or (if (b.isTertiaryPressed) 1 shl MIDDLE else 0) or (if (b.isSecondaryPressed) 1 shl RIGHT else 0)
}

private fun modifiersOf(k: PointerKeyboardModifiers): Int =
    (if (k.isShiftPressed) Mod.SHIFT else 0) or (if (k.isAltPressed || k.isMetaPressed) Mod.ALT else 0) or (if (k.isCtrlPressed) Mod.CTRL else 0)

private fun pack(col: Int, row: Int): Long = (row.toLong() shl 32) or col.toLong()

private const val LEFT = 0
private const val MIDDLE = 1
private const val RIGHT = 2
private val BUTTONS = intArrayOf(MouseButton.LEFT, MouseButton.MIDDLE, MouseButton.RIGHT)

/** Lines of history a notch of the wheel scrolls, and arrows it sends on the alternate screen. */
internal const val WHEEL_LINES = 3

package app.berth.android.ui.terminal

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.tripleClick
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.MouseTracking
import app.berth.terminal.TerminalKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * A mouse and a trackpad on the terminal canvas, driven through Compose's mouse injection and, for
 * a trackpad's two-finger swipe, the motion events Android sends for one. Nobody has the mouse:
 * the wheel scrolls history three lines a notch, a drag selects cells, a double click a word, a
 * triple click a line, a click dismisses a selection or opens the link under it, hovering a link
 * underlines it, and a right click is the paste the Stage wires. The application has the mouse:
 * each is its report, as its mode asks (drags under 1002 and 1003, hover under 1003 only, one
 * report a cell), and Shift keeps them the terminal's. Touches are the touch handler's, untouched.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalMouseTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()
    private val sent = CopyOnWriteArrayList<String>()
    private val font = TerminalFont()

    private class Heard {
        var taps = 0
        var secondary = 0
        val links = ArrayList<LinkTap>()
        var selections = 0
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
    }

    private fun paints(): TerminalPaints {
        val res = context.resources
        return TerminalPaintsCache.get(context, font, res.displayMetrics.density, 1f)
    }

    private fun cellCenter(row: Int, col: Int): Offset {
        val p = paints()
        return Offset((col + 0.5f) * p.cellWidth, (row + 0.5f) * p.cellHeight)
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    /** The canvas alone over a live tab whose screen is cleared and given [text], shown once [until] is on it, every mouse hook wired to [heard]. */
    private fun canvas(heard: Heard, text: String, until: String, secondaryWired: Boolean = true): Triple<TerminalSession, TerminalViewport, TerminalSelection> {
        val session = StageFixture.liveHomelab().also { sessions += it }
        session.sendObserver = { sent += String(it, Charsets.UTF_8) }
        val viewport = TerminalViewport()
        val selection = TerminalSelection()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val sink = remember {
                        object : TerminalInputSink {
                            override fun onText(text: String) = Unit
                            override fun onKey(key: TerminalKey, modifiers: Int) = Unit
                        }
                    }
                    TerminalCanvas(
                        session = session,
                        theme = TerminalTheme.BERTH_DARK,
                        font = font,
                        sink = sink,
                        viewport = viewport,
                        modifier = Modifier.fillMaxSize(),
                        onTap = { heard.taps++ },
                        onLinkTap = { heard.links += it },
                        onSecondaryClick = if (secondaryWired) ({ heard.secondary++ }) else null,
                        selection = selection,
                        onSelectionStarted = { heard.selections++ },
                    )
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        val p = paints()
        compose.waitUntil(5_000) {
            val size = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().size
            session.emulator.cols == (size.width / p.cellWidth).toInt() && session.emulator.rows == (size.height / p.cellHeight).toInt()
        }
        session.emulator.write("\u001b[2J\u001b[H$text")
        compose.waitUntil(5_000) { synchronized(session.emulator.lock) { session.emulator.screenText() }.joinToString("\n").contains(until) }
        settle()
        sent.clear()
        return Triple(session, viewport, selection)
    }

    private fun mode(session: TerminalSession, set: String, wanted: MouseTracking) {
        session.emulator.write(set)
        compose.waitUntil(5_000) { synchronized(session.emulator.lock) { session.emulator.mouseTracking } == wanted }
        settle()
        sent.clear()
    }

    private val lines = (1..120).joinToString("\r\n") { "line $it" }

    @Test
    fun `the wheel scrolls history three lines a notch, and is the application's wheel once it takes the mouse, unless Shift is held`() {
        val heard = Heard()
        val (session, viewport, _) = canvas(heard, lines, until = "line 120")
        val node = compose.onNodeWithTag(TerminalTag)
        node.performMouseInput { moveTo(cellCenter(4, 10)); scroll(-1f) }
        compose.waitUntil(5_000) { viewport.scrollOffset == WHEEL_LINES }
        node.performMouseInput { scroll(-2f) }
        compose.waitUntil(5_000) { viewport.scrollOffset == 3 * WHEEL_LINES }
        node.performMouseInput { scroll(3f) }
        compose.waitUntil(5_000) { viewport.scrollOffset == 0 }
        assertTrue("nothing went to the remote: $sent", sent.isEmpty())

        // X10 reports presses and nothing else: its wheel is still the terminal's.
        mode(session, "\u001b[?9h", MouseTracking.X10)
        node.performMouseInput { scroll(-1f) }
        compose.waitUntil(5_000) { viewport.scrollOffset == WHEEL_LINES }
        node.performMouseInput { scroll(1f) }
        compose.waitUntil(5_000) { viewport.scrollOffset == 0 }
        assertTrue("X10 was sent no wheel: $sent", sent.isEmpty())

        // 1000 with SGR: a report a notch, at the pointer's cell, and history stays where it is.
        mode(session, "\u001b[?9l\u001b[?1000h\u001b[?1006h", MouseTracking.NORMAL)
        node.performMouseInput { scroll(-2f) }
        compose.waitUntil(5_000) { sent.size == 2 }
        node.performMouseInput { scroll(1f) }
        compose.waitUntil(5_000) { sent.size == 3 }
        assertEquals(listOf("\u001b[<64;11;5M", "\u001b[<64;11;5M", "\u001b[<65;11;5M"), sent.toList())
        assertEquals(0, viewport.scrollOffset)

        // Shift keeps the wheel the terminal's.
        sent.clear()
        node.performMultiModalInput {
            key { keyDown(Key.ShiftLeft) }
            mouse { scroll(-1f) }
            key { keyUp(Key.ShiftLeft) }
        }
        compose.waitUntil(5_000) { viewport.scrollOffset == WHEEL_LINES }
        assertTrue("Shift's wheel went nowhere remote: $sent", sent.isEmpty())
    }

    @Test
    fun `on the alternate screen with nobody taking the mouse the wheel is arrows`() {
        val heard = Heard()
        val (session, viewport, _) = canvas(heard, "\u001b[?1049h\u001b[?1hfull screen", until = "full screen")
        compose.onNodeWithTag(TerminalTag).performMouseInput { moveTo(cellCenter(4, 10)); scroll(-1f) }
        compose.waitUntil(5_000) { sent.size == WHEEL_LINES }
        assertEquals(List(WHEEL_LINES) { "\u001bOA" }, sent.toList())
        assertEquals(0, viewport.scrollOffset)
        assertTrue(session.emulator.isAlternateScreen)
    }

    @Test
    fun `in mouse mode a press and release are the application's, drags only under 1002, hover only under 1003, once a cell`() {
        val heard = Heard()
        val (session, _, selection) = canvas(heard, lines, until = "line 120")
        val node = compose.onNodeWithTag(TerminalTag)

        // 1000: press and release, nothing between, wherever the pointer went.
        mode(session, "\u001b[?1000h\u001b[?1006h", MouseTracking.NORMAL)
        node.performMouseInput {
            moveTo(cellCenter(5, 10))
            press()
            moveTo(cellCenter(5, 12))
            moveTo(cellCenter(6, 14))
            release()
            moveTo(cellCenter(8, 2))
        }
        compose.waitUntil(5_000) { sent.size >= 2 }
        settle()
        assertEquals(listOf("\u001b[<0;11;6M", "\u001b[<0;15;7m"), sent.toList())
        assertFalse("the application's drag is no selection", selection.active)
        assertEquals("a click in mouse mode focuses the terminal, as a tap", 1, heard.taps)

        // 1002: the drag's motion too, a report a cell, and still no hover.
        mode(session, "\u001b[?1002h", MouseTracking.BUTTON_EVENT)
        node.performMouseInput {
            moveTo(cellCenter(5, 10))
            press()
            moveBy(Offset(1f, 0f))
            moveTo(cellCenter(5, 12))
            moveBy(Offset(1f, 1f))
            release()
            moveTo(cellCenter(9, 9))
        }
        compose.waitUntil(5_000) { sent.size >= 3 }
        settle()
        assertEquals(listOf("\u001b[<0;11;6M", "\u001b[<32;13;6M", "\u001b[<0;13;6m"), sent.toList())

        // 1003: motion with no button held as well, once a cell.
        mode(session, "\u001b[?1003h", MouseTracking.ANY_EVENT)
        node.performMouseInput {
            moveTo(cellCenter(7, 3))
            moveBy(Offset(2f, 0f))
            moveTo(cellCenter(7, 4))
        }
        compose.waitUntil(5_000) { sent.size >= 2 }
        settle()
        assertEquals(listOf("\u001b[<35;4;8M", "\u001b[<35;5;8M"), sent.toList())

        // The right and middle buttons are the application's too, and no paste.
        sent.clear()
        node.performMouseInput {
            moveTo(cellCenter(7, 4))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
            press(MouseButton.Tertiary)
            release(MouseButton.Tertiary)
        }
        compose.waitUntil(5_000) { sent.size >= 4 }
        settle()
        assertEquals(listOf("\u001b[<2;5;8M", "\u001b[<2;5;8m", "\u001b[<1;5;8M", "\u001b[<1;5;8m"), sent.toList())
        assertEquals(0, heard.secondary)

        // Shift keeps a drag the terminal's: a selection, nothing sent.
        sent.clear()
        node.performMultiModalInput {
            key { keyDown(Key.ShiftLeft) }
            mouse {
                moveTo(cellCenter(2, 0))
                press()
                moveTo(cellCenter(2, 5))
                release()
            }
            key { keyUp(Key.ShiftLeft) }
        }
        compose.waitUntil(5_000) { selection.active }
        settle()
        assertTrue("Shift's drag sent nothing but the hover: $sent", sent.none { !it.startsWith("\u001b[<35;") })
    }

    @Test
    fun `with nobody taking the mouse a drag selects, a double click a word, a triple click a line, and a click dismisses`() {
        val heard = Heard()
        val (session, _, selection) = canvas(heard, "\u001b[3;1Halpha beta gamma\u001b[6;1H", until = "alpha beta")
        val node = compose.onNodeWithTag(TerminalTag)
        val text = { selection.text(session.emulator) }

        node.performMouseInput {
            moveTo(cellCenter(2, 0))
            press()
            moveTo(cellCenter(2, 4))
            moveTo(cellCenter(2, 8))
            release()
        }
        compose.waitUntil(5_000) { selection.active }
        assertEquals("alpha bet", text())
        assertEquals(1, heard.selections)
        assertEquals("a drag is no click", 0, heard.taps)

        // A click away dismisses it and does nothing else.
        node.performMouseInput { click(cellCenter(10, 3)) }
        compose.waitUntil(5_000) { !selection.active }
        assertEquals(0, heard.taps)
        // And another is a click: the terminal's focus and keyboard, as a tap.
        compose.mainClock.advanceTimeBy(1_000)
        node.performMouseInput { click(cellCenter(12, 3)) }
        compose.waitUntil(5_000) { heard.taps == 1 }

        compose.mainClock.advanceTimeBy(1_000)
        node.performMouseInput { doubleClick(cellCenter(2, 7)) }
        compose.waitUntil(5_000) { selection.active }
        assertEquals("beta", text())

        compose.mainClock.advanceTimeBy(1_000)
        node.performMouseInput { click(cellCenter(10, 3)) }
        compose.waitUntil(5_000) { !selection.active }
        compose.mainClock.advanceTimeBy(1_000)
        node.performMouseInput { tripleClick(cellCenter(2, 12)) }
        compose.waitUntil(5_000) { selection.active && text() == "alpha beta gamma" }
        assertTrue("nothing went to the remote: $sent", sent.isEmpty())
    }

    @Test
    fun `a right click is the Stage's paste when wired, on its release`() {
        val heard = Heard()
        canvas(heard, lines, until = "line 120")
        compose.onNodeWithTag(TerminalTag).performMouseInput { moveTo(cellCenter(4, 4)); press(MouseButton.Secondary) }
        settle()
        assertEquals("the paste waits for the release", 0, heard.secondary)
        compose.onNodeWithTag(TerminalTag).performMouseInput { release(MouseButton.Secondary) }
        compose.waitUntil(5_000) { heard.secondary == 1 }
        assertEquals(0, heard.taps)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a right click with the paste turned off in Settings does nothing`() {
        val heard = Heard()
        canvas(heard, lines, until = "line 120", secondaryWired = false)
        compose.onNodeWithTag(TerminalTag).performMouseInput { moveTo(cellCenter(4, 4)); press(MouseButton.Secondary); release(MouseButton.Secondary) }
        settle()
        assertEquals(0, heard.secondary)
        assertEquals(0, heard.taps)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `hovering a link underlines it in the links colour, and a click opens it`() {
        val heard = Heard()
        val url = "https://caddyserver.com/docs/"
        val (session, _, _) = canvas(heard, "\u001b[3;1Hsee \u001b]8;;$url\u001b\\the docs\u001b]8;;\u001b\\ for more", until = "the docs")
        val node = compose.onNodeWithTag(TerminalTag)
        val p = paints()
        val links = TerminalTheme.BERTH_DARK.links and 0xFFFFFF
        fun underlined(): Boolean {
            val map = node.captureToImage().toPixelMap()
            val y0 = (2 * p.cellHeight).toInt()
            val y1 = (3 * p.cellHeight).toInt()
            val x0 = (4 * p.cellWidth).toInt()
            val x1 = (12 * p.cellWidth).toInt()
            return (y0 until y1).any { y -> (x0 until x1).count { x -> near(rgb(map[x, y]), links) } > (x1 - x0) / 2 }
        }
        assertFalse("no underline before the pointer comes", underlined())
        node.performMouseInput { moveTo(cellCenter(2, 6)) }
        compose.waitUntil(5_000) { underlined() }
        node.performMouseInput { moveTo(cellCenter(8, 6)) }
        compose.waitUntil(5_000) { !underlined() }

        node.performMouseInput { click(cellCenter(2, 7)) }
        compose.waitUntil(5_000) { heard.links.size == 1 }
        assertEquals(LinkTap(url, "the docs"), heard.links.single())
        assertEquals(0, heard.taps)
        assertTrue(sent.isEmpty())
        assertEquals(MouseTracking.NONE, session.emulator.mouseTracking)
    }

    private fun near(a: Int, b: Int): Boolean =
        (0..16 step 8).all { shift -> abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) <= 24 }

    private fun rgb(c: androidx.compose.ui.graphics.Color): Int =
        ((c.red * 255 + 0.5f).toInt() shl 16) or ((c.green * 255 + 0.5f).toInt() shl 8) or (c.blue * 255 + 0.5f).toInt()

    /** A trackpad's two-finger swipe down by [dy] pixels, as Android sends one: a fake finger classified as a swipe, the distance to scroll on its axis. */
    private fun trackpadSwipe(dy: Float, steps: Int = 6) {
        val root = compose.activity.window.decorView
        val node = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode()
        val at = node.positionInWindow + Offset(node.size.width / 2f, node.size.height / 2f)
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val t0 = SystemClock.uptimeMillis()
        var y = at.y
        fun send(action: Int, t: Long, distance: Float) {
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = at.x; this.y = y; setAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, distance) })
            val ev = MotionEvent.obtain(t0, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0, 0, MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE)!!
            compose.runOnUiThread { root.dispatchTouchEvent(ev) }
            ev.recycle()
        }
        send(MotionEvent.ACTION_DOWN, t0, 0f)
        repeat(steps) { i ->
            y += dy / steps
            // The axis is the distance to scroll, the fingers' travel reversed.
            send(MotionEvent.ACTION_MOVE, t0 + 16L * (i + 1), -dy / steps)
        }
        send(MotionEvent.ACTION_UP, t0 + 16L * (steps + 1), 0f)
        settle()
    }

    @Test
    fun `a trackpad's two-finger swipe scrolls history as a finger's drag does, and is wheel reports in mouse mode`() {
        val heard = Heard()
        val (session, viewport, _) = canvas(heard, lines, until = "line 120")
        val ch = paints().cellHeight
        trackpadSwipe(dy = ch * 4.5f)
        compose.waitUntil(5_000) { viewport.scrollOffset == 4 }
        trackpadSwipe(dy = -ch * 4f)
        compose.waitUntil(5_000) { viewport.scrollOffset == 0 }
        assertTrue(sent.isEmpty())
        assertEquals("the swipe is no tap", 0, heard.taps)

        mode(session, "\u001b[?1000h\u001b[?1006h", MouseTracking.NORMAL)
        trackpadSwipe(dy = ch * 2f)
        compose.waitUntil(5_000) { sent.size == 2 }
        assertTrue(sent.toString(), sent.all { it.startsWith("\u001b[<64;") })
        assertEquals(0, viewport.scrollOffset)
    }
}

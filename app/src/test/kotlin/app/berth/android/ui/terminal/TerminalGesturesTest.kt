package app.berth.android.ui.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.StageTools
import app.berth.android.ui.stage.resolveLook
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DoubleTapAction
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.PinchAction
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TapAction
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalSettings
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.ThreeFingerTapAction
import app.berth.domain.model.TwoFingerTapAction
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import app.berth.terminal.MouseTracking
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The canvas gestures spec D1 still owed, driven for real: a three-finger tap, a two-finger
 * double-tap, and a one-finger horizontal drag. On the canvas alone, what it reports and what it
 * keeps quiet: three fingers down and up together are one three-finger tap and never a paste, a
 * reset or a zoom; two two-finger taps within the double-tap window are one reset and neither
 * pastes; one two-finger tap pastes once the window has passed and not before, and at once when no
 * reset is wired, the first tap's lift telling the finger it landed while the window runs (review
 * #16); a pinch and a two-finger swipe are what they were; and a tap on an OSC 8 link is the link's
 * until the application takes the mouse, when it is the application's click. Through the Stage, where each
 * gesture lands: the three-finger tap hides the Deck and shows it again (the Deck's own state stays
 * in the Stage, not in Deck.kt), the two-finger double-tap takes a host's own font size away, and
 * the drag sends nothing while Settings has it off and arrows once it is on, which on a detached
 * tab is the first key that reconnects it (review #15). Settings › Gestures' alternatives (spec D1):
 * on the canvas, a double tap that sends Tab or is two taps and selects nothing either way, and a
 * tap that reports and clicks nothing yet still starts a double tap; on the Stage, a two-finger tap
 * that opens the New tab sheet or does nothing, a three-finger tap that shares the rows in view or
 * does nothing, and a pinch that leaves the size alone. A right click on the Stage is the paste
 * through its gate: the preview for a multi-line clipboard on a live tab, nothing with the paste
 * turned off, the program's report while it has the mouse, and Not connected on a detached tab
 * without reconnecting it. Against the local sshd the drag moves the shell's cursor a cell per cell
 * of travel, left and back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalGesturesTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        graph.close()
    }

    // ---- geometry and waiting -------------------------------------------------------------------------

    /** The saved host the Stage under test takes its look from; a canvas alone is drawn at the app's font whatever its session's host. */
    private var stagedHostId: String? = null

    /**
     * The paints of the font the terminal under test is drawn at: the app's, and on the Stage the saved
     * host's size and family over it as the Stage resolves them. The Stage draws the record's snapshot
     * until the hosts flow brings it the saved host, so a host's own size lands a frame or more late.
     */
    private fun paints(): TerminalPaints {
        val res = context.resources
        val app = runBlocking { graph.settings.terminalFont.first() }
        val saved = stagedHostId?.let { id -> graph.hosts.items.value.firstOrNull { it.id == id } }
        val font = saved?.let { resolveLook(emptyList(), TerminalTheme.BERTH_DARK, app, it, null).font } ?: app
        return TerminalPaintsCache.get(context, font, res.displayMetrics.density, res.configuration.fontScale)
    }

    /** The centre of the view cell at [row], [col] in the canvas node's coordinates. */
    private fun cellCenter(row: Int, col: Int): Offset {
        val p = paints()
        return Offset((col + 0.5f) * p.cellWidth, (row + 0.5f) * p.cellHeight)
    }

    /** Waits for the canvas to size the grid to itself, so cells map to pixels. */
    private fun awaitGrid(session: TerminalSession) {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            val p = paints()
            val size = compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().size
            val cols = (size.width / p.cellWidth).toInt()
            val rows = (size.height / p.cellHeight).toInt()
            cols >= 2 && session.emulator.cols == cols && session.emulator.rows == rows
        }
        settle(200)
    }

    /** Real time passes (for the sshd and the session's own threads) while the compose clock keeps ticking. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun shown(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeout: Long = 5_000) = compose.waitUntil(timeout) { shown(text) }

    // ---- the canvas alone ------------------------------------------------------------------------------

    /** What the canvas reported, gesture by gesture. */
    private class Heard {
        var threeFingerTaps = 0
        var twoFingerDoubleTaps = 0
        var twoFingerTaps = 0
        var twoFingerTapsArmed = 0
        var taps = 0
        val linkTaps = ArrayList<LinkTap>()
        val fontSteps = ArrayList<Int>()
        val swipes = ArrayList<Boolean>()
        val keys = ArrayList<TerminalKey>()
        override fun toString() =
            "three-finger taps $threeFingerTaps, two-finger double-taps $twoFingerDoubleTaps, two-finger taps $twoFingerTaps (armed $twoFingerTapsArmed), taps $taps, link taps $linkTaps, font steps $fontSteps, swipes $swipes, keys $keys"
    }

    /**
     * The canvas on its own over [session] (homelab's live frame unless given), every gesture wired to
     * [heard]; [resetWired] false leaves the two-finger double-tap out, as a Stage without it would.
     * [tap] and [doubleTap] are Settings › Gestures' choices, and a [selection] lets a double tap select.
     */
    private fun canvasAlone(
        heard: Heard,
        resetWired: Boolean = true,
        dragArrows: Boolean = false,
        tap: TapAction = TapAction.SHOW_KEYBOARD,
        doubleTap: DoubleTapAction = DoubleTapAction.SELECT_WORD,
        selection: TerminalSelection? = null,
        session: TerminalSession = StageFixture.liveHomelab().also { sessions += it },
    ): TerminalSession {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val viewport = remember { TerminalViewport() }
                    val sink = remember {
                        object : TerminalInputSink {
                            override fun onText(text: String) = Unit
                            override fun onKey(key: TerminalKey, modifiers: Int) { heard.keys += key }
                        }
                    }
                    TerminalCanvas(
                        session = session,
                        theme = TerminalTheme.BERTH_DARK,
                        font = TerminalFont(),
                        sink = sink,
                        viewport = viewport,
                        modifier = Modifier.fillMaxSize(),
                        onFontSizeStep = { heard.fontSteps += it },
                        onTap = { heard.taps++ },
                        onTwoFingerSwipe = { heard.swipes += it },
                        onTwoFingerTap = { heard.twoFingerTaps++ },
                        onTwoFingerDoubleTap = if (resetWired) ({ heard.twoFingerDoubleTaps++ }) else null,
                        onTwoFingerTapArmed = { heard.twoFingerTapsArmed++ },
                        onThreeFingerTap = { heard.threeFingerTaps++ },
                        horizontalDragArrows = dragArrows,
                        tap = tap,
                        doubleTap = doubleTap,
                        onLinkTap = { heard.linkTaps += it },
                        selection = selection,
                    )
                }
            }
        }
        awaitGrid(session)
        return session
    }

    private fun twoFingerTap(row: Int = 5) {
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(row, 4))
            down(1, cellCenter(row, 24))
            up(0)
            up(1)
        }
    }

    private fun threeFingerTap(row: Int = 5) {
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(row, 4))
            down(1, cellCenter(row, 14))
            down(2, cellCenter(row, 24))
            up(0)
            up(1)
            up(2)
        }
    }

    /** Lets the double-tap window and anything queued behind it run out, so what did not happen is known not to. */
    private fun windowPasses() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `three fingers down and up together are a three-finger tap and nothing else`() {
        val heard = Heard()
        canvasAlone(heard)
        threeFingerTap()
        compose.waitUntil(5_000) { heard.threeFingerTaps == 1 }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
        assertEquals(heard.toString(), 0, heard.taps)
        assertTrue(heard.toString(), heard.fontSteps.isEmpty())
        assertTrue(heard.toString(), heard.swipes.isEmpty())

        // Lifted one at a time and in another order, the same tap; a third finger landing on two is one too.
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(6, 4))
            down(1, cellCenter(6, 14))
            down(2, cellCenter(6, 24))
            up(2)
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { heard.threeFingerTaps == 2 }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
    }

    @Test
    fun `three fingers that travel, or stay past the tap time, are no tap`() {
        val heard = Heard()
        canvasAlone(heard)
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 14))
            down(2, cellCenter(5, 24))
            updatePointerBy(0, Offset(0f, 80f))
            updatePointerBy(1, Offset(0f, 80f))
            updatePointerBy(2, Offset(0f, 80f))
            move()
            up(0)
            up(1)
            up(2)
        }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.threeFingerTaps)
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 14))
            down(2, cellCenter(5, 24))
            advanceEventTime(400)
            up(0)
            up(1)
            up(2)
        }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.threeFingerTaps)
        // Three fingers are never a pinch, a swipe or a paste either.
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertTrue(heard.toString(), heard.fontSteps.isEmpty())
        assertTrue(heard.toString(), heard.swipes.isEmpty())
    }

    @Test
    fun `two two-finger taps within the window are one reset, and neither pastes`() {
        val heard = Heard()
        canvasAlone(heard)
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 24))
            up(0)
            up(1)
            advanceEventTime(120)
            down(0, cellCenter(5, 5))
            down(1, cellCenter(5, 25))
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { heard.twoFingerDoubleTaps == 1 }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertEquals(heard.toString(), 1, heard.twoFingerDoubleTaps)
        assertEquals(heard.toString(), 0, heard.threeFingerTaps)
        assertTrue(heard.toString(), heard.fontSteps.isEmpty())
        // The reset spent the first tap: a third, on its own, opens a new window.
        twoFingerTap()
        windowPasses()
        assertEquals(heard.toString(), 1, heard.twoFingerTaps)
        assertEquals(heard.toString(), 1, heard.twoFingerDoubleTaps)
    }

    @Test
    fun `one two-finger tap pastes once the window has passed, not before`() {
        val heard = Heard()
        canvasAlone(heard)
        val window = ViewConfiguration.getDoubleTapTimeout().toLong()
        compose.mainClock.autoAdvance = false
        try {
            twoFingerTap()
            compose.mainClock.advanceTimeBy(window / 3)
            assertEquals("the window is still open: $heard", 0, heard.twoFingerTaps)
            compose.mainClock.advanceTimeBy(window)
            assertEquals("the window has passed: $heard", 1, heard.twoFingerTaps)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        windowPasses()
        assertEquals(heard.toString(), 1, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
        // Another after the window is another paste, not a reset.
        twoFingerTap()
        compose.waitUntil(5_000) { heard.twoFingerTaps == 2 }
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
    }

    @Test
    fun `with no reset wired a two-finger tap pastes at once`() {
        val heard = Heard()
        canvasAlone(heard, resetWired = false)
        twoFingerTap()
        assertEquals(heard.toString(), 1, heard.twoFingerTaps)
        twoFingerTap()
        assertEquals(heard.toString(), 2, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
        // Nothing waits, so nothing is armed: the paste itself is what the finger feels.
        assertEquals(heard.toString(), 0, heard.twoFingerTapsArmed)
    }

    @Test
    fun `the first two-finger tap's lift is reported while the window runs, so the finger is told it landed, and the second's is not`() {
        val heard = Heard()
        canvasAlone(heard)
        val window = ViewConfiguration.getDoubleTapTimeout().toLong()
        compose.mainClock.autoAdvance = false
        try {
            // Lifted: armed at once, with the paste still a window away.
            twoFingerTap()
            assertEquals("armed on the lift: $heard", 1, heard.twoFingerTapsArmed)
            assertEquals("not yet pasted: $heard", 0, heard.twoFingerTaps)
            compose.mainClock.advanceTimeBy(window + 50)
            assertEquals("pasted once the window passed: $heard", 1, heard.twoFingerTaps)
            assertEquals("and armed only the once: $heard", 1, heard.twoFingerTapsArmed)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        windowPasses()
        // A double-tap: the first lift arms, the second is the reset and arms nothing.
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(6, 4))
            down(1, cellCenter(6, 24))
            up(0)
            up(1)
            advanceEventTime(120)
            down(0, cellCenter(6, 5))
            down(1, cellCenter(6, 25))
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { heard.twoFingerDoubleTaps == 1 }
        windowPasses()
        assertEquals(heard.toString(), 2, heard.twoFingerTapsArmed)
        assertEquals(heard.toString(), 1, heard.twoFingerTaps)
        // Three fingers arm nothing either: that gesture has no window to wait out.
        threeFingerTap()
        compose.waitUntil(5_000) { heard.threeFingerTaps == 1 }
        windowPasses()
        assertEquals(heard.toString(), 2, heard.twoFingerTapsArmed)
    }

    /** The view row and column where [token] first shows on the screen. */
    private fun cellOf(session: TerminalSession, token: String): Pair<Int, Int> {
        val rows = synchronized(session.emulator.lock) { session.emulator.screenText() }
        val row = rows.indexOfFirst { it.contains(token) }
        assertTrue("'$token' is on screen in $rows", row >= 0)
        return row to rows[row].indexOf(token)
    }

    @Test
    fun `a tap on a link is the link's until the application takes the mouse, when it is the application's click`() {
        val heard = Heard()
        val session = canvasAlone(heard)
        val url = "https://caddyserver.com/docs/"
        // A link printed at the restored prompt, the way a program prints one.
        session.emulator.write("\u001b]8;;$url\u001b\\the docs\u001b]8;;\u001b\\ \r\n")
        compose.waitUntil(5_000) { synchronized(session.emulator.lock) { session.emulator.screenText() }.any { it.contains("the docs") } }
        settle(300)
        val (row, col) = cellOf(session, "the docs")
        val onLink = cellCenter(row, col + 2)

        // Nobody has the mouse: the tap is the link's, and never a plain tap.
        compose.onNodeWithTag(TerminalTag).performTouchInput { down(onLink); up() }
        compose.waitUntil(5_000) { heard.linkTaps.size == 1 }
        assertEquals(heard.toString(), LinkTap(url, "the docs"), heard.linkTaps.single())
        assertEquals(heard.toString(), 0, heard.taps)

        // The application asks for mouse clicks (a TUI, tmux): the same tap is its click, sent to it,
        // and the link is plain text to the finger; the selection bar's Open link is the way to it then.
        session.emulator.write("\u001b[?1000h")
        compose.waitUntil(5_000) { synchronized(session.emulator.lock) { session.emulator.mouseTracking } != MouseTracking.NONE }
        compose.onNodeWithTag(TerminalTag).performTouchInput { down(onLink); up() }
        compose.waitUntil(5_000) { heard.taps == 1 }
        windowPasses()
        assertEquals(heard.toString(), 1, heard.linkTaps.size)

        // The application lets the mouse go: the link is the finger's again.
        session.emulator.write("\u001b[?1000l")
        compose.waitUntil(5_000) { synchronized(session.emulator.lock) { session.emulator.mouseTracking } == MouseTracking.NONE }
        compose.onNodeWithTag(TerminalTag).performTouchInput { down(onLink); up() }
        compose.waitUntil(5_000) { heard.linkTaps.size == 2 }
        assertEquals(heard.toString(), 1, heard.taps)
    }

    @Test
    fun `a pinch steps the font and a two-finger swipe switches, and neither is a tap`() {
        val heard = Heard()
        canvasAlone(heard)
        val density = context.resources.displayMetrics.density
        // Apart by more than the 40 dp step between the two moves: one step out; the fingers lifting is no tap.
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 8))
            down(1, cellCenter(5, 20))
            updatePointerBy(0, Offset(-30 * density, 0f))
            move()
            updatePointerBy(1, Offset(30 * density, 0f))
            move()
            up(0)
            up(1)
        }
        windowPasses()
        assertEquals(heard.toString(), listOf(1), heard.fontSteps)
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.twoFingerDoubleTaps)
        // Both fingers the same way with the span held: a swipe, forward for leftward travel.
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 8))
            down(1, cellCenter(5, 20))
            updatePointerBy(0, Offset(-40 * density, 0f))
            updatePointerBy(1, Offset(-40 * density, 0f))
            move()
            up(0)
            up(1)
        }
        windowPasses()
        assertEquals(heard.toString(), listOf(true), heard.swipes)
        assertEquals(heard.toString(), listOf(1), heard.fontSteps)
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
        assertEquals(heard.toString(), 0, heard.threeFingerTaps)
    }

    // ---- Settings › Gestures' alternatives (spec D1), on the canvas alone ------------------------------------

    /** Every byte the session sends from here on, as text. */
    private fun sentBy(session: TerminalSession): MutableList<String> = ArrayList<String>().also { sent -> session.sendObserver = { sent += String(it, Charsets.UTF_8) } }

    private fun setMouseTracking(session: TerminalSession, on: Boolean) {
        session.emulator.write(if (on) "\u001b[?1000h" else "\u001b[?1000l")
        compose.waitUntil(5_000) { (synchronized(session.emulator.lock) { session.emulator.mouseTracking } != MouseTracking.NONE) == on }
    }

    @Test
    fun `the default double tap selects the word, and a double tap that drags whole lines`() {
        val heard = Heard()
        val selection = TerminalSelection()
        val session = canvasAlone(heard, selection = selection)
        val (row, col) = cellOf(session, "docker")
        val at = cellCenter(row, col + 1)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { selection.active }
        assertEquals("docker", selection.text(session.emulator))
        assertEquals("the first tap was a tap: $heard", 1, heard.taps)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(cellCenter(row + 4, 2)) }
        compose.waitUntil(5_000) { !selection.active }
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); down(at); moveTo(cellCenter(row + 2, col)); up() }
        compose.waitUntil(5_000) { selection.active && selection.summary == "3 lines" }
        assertTrue(heard.toString(), heard.keys.isEmpty())
    }

    @Test
    fun `with the double tap set to Send Tab the second tap is Tab through the input, and a double tap selects nothing`() {
        val heard = Heard()
        val selection = TerminalSelection()
        val session = canvasAlone(heard, doubleTap = DoubleTapAction.SEND_TAB, selection = selection)
        val (row, col) = cellOf(session, "docker")
        val at = cellCenter(row, col + 1)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { heard.keys.isNotEmpty() }
        windowPasses()
        assertEquals(heard.toString(), listOf(TerminalKey.TAB), heard.keys)
        assertEquals("the first tap was a tap, the second the Tab: $heard", 1, heard.taps)
        assertFalse("no word selected", selection.active)
        // The Tab spent the pair: a third tap is a tap, and a fourth close behind it Tab again.
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { heard.keys.size == 2 }
        windowPasses()
        assertEquals(heard.toString(), 2, heard.taps)
        // A second tap that drags is a drag like any other: no lines, and no Tab.
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); down(at); moveTo(cellCenter(row + 2, col)); up() }
        windowPasses()
        assertFalse("no lines selected", selection.active)
        assertEquals(heard.toString(), 2, heard.keys.size)
        assertEquals(heard.toString(), 3, heard.taps)
    }

    @Test
    fun `with the double tap set to nothing two taps are two taps, and select nothing`() {
        val heard = Heard()
        val selection = TerminalSelection()
        val session = canvasAlone(heard, doubleTap = DoubleTapAction.NOTHING, selection = selection)
        val (row, col) = cellOf(session, "docker")
        val at = cellCenter(row, col + 1)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { heard.taps == 2 }
        windowPasses()
        assertFalse("no word selected", selection.active)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); down(at); moveTo(cellCenter(row + 2, col)); up() }
        windowPasses()
        assertFalse("no lines selected", selection.active)
        assertEquals("the tap before the drag was a tap: $heard", 3, heard.taps)
        assertTrue(heard.toString(), heard.keys.isEmpty())
        // A long-press still selects, whatever the double tap does.
        compose.onNodeWithTag(TerminalTag).performTouchInput { longClick(at) }
        compose.waitUntil(5_000) { selection.active }
        assertEquals("docker", selection.text(session.emulator))
    }

    @Test
    fun `with the tap set to nothing a tap reports nothing and clicks nothing, and a double tap still selects`() {
        val heard = Heard()
        val selection = TerminalSelection()
        val session = canvasAlone(heard, tap = TapAction.NOTHING, selection = selection)
        val (row, col) = cellOf(session, "docker")
        val at = cellCenter(row, col + 1)
        val sent = sentBy(session)

        // The application has the mouse: the default tap would be its click; this one sends it nothing.
        setMouseTracking(session, on = true)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(cellCenter(row + 4, 2)) }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.taps)
        assertTrue("no click reached the application: $sent", sent.isEmpty())
        setMouseTracking(session, on = false)

        // The tap is still a double tap's first, and a tap still lets a selection go.
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(at); click(at) }
        compose.waitUntil(5_000) { selection.active }
        assertEquals("docker", selection.text(session.emulator))
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(cellCenter(row + 4, 2)) }
        compose.waitUntil(5_000) { !selection.active }
        windowPasses()
        assertEquals(heard.toString(), 0, heard.taps)
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test
    fun `the default tap is the application's click while it has the mouse`() {
        val heard = Heard()
        val session = canvasAlone(heard)
        val sent = sentBy(session)
        setMouseTracking(session, on = true)
        compose.onNodeWithTag(TerminalTag).performTouchInput { click(cellCenter(3, 2)) }
        compose.waitUntil(5_000) { sent.size == 2 }
        assertEquals(heard.toString(), 1, heard.taps)
        assertEquals("a press and a release at column 3, row 4", listOf("\u001b[M #$", "\u001b[M##$"), sent)
    }

    /** A detached tab on a host at a port nothing listens on, so a reconnect is refused at once rather than left to a timeout. */
    private fun detachedBox(): TerminalSession {
        val now = System.currentTimeMillis()
        val box = Host(
            id = "box", name = "box", color = SwatchColor.SLATE, monogram = Host.monogramFor("box"), address = "127.0.0.1", port = 1, user = "demo",
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), lastConnectedAt = now - TimeUnit.MINUTES.toMillis(5), createdAt = now - TimeUnit.DAYS.toMillis(1),
        )
        val record = SessionRecord(
            id = "s-box", workspaceId = Workspace.DEFAULT_ID, hostId = box.id, hostSnapshot = box, state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME,
            title = box.name, cwd = "~", lastCommand = "ls", sortOrder = 0, createdAt = now - TimeUnit.HOURS.toMillis(1), lastLiveAt = now - TimeUnit.MINUTES.toMillis(5),
        )
        runBlocking { graph.hosts.upsert(box) }
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            // Never completes and never emits, as the real network monitor's flow does not: an empty
            // flow would end the reconnect's `first()` wait with NoSuchElementException.
            override val networkAvailable: Flow<Unit> = MutableSharedFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        return TerminalSession(record, CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}.also { sessions += it }
    }

    /**
     * A one-finger horizontal drag of [cells] cells (negative is leftward) from column [fromCol],
     * a cell at a time so each step is its own pointer event with a time of its own (moves that
     * share a frame coalesce, and the arrows are counted per event). The travel from the finger's
     * landing counts from the first move, so a drag of n cells is n arrows.
     */
    private fun drag(cells: Int, row: Int = 3, fromCol: Int = 20) {
        val p = paints()
        val step = if (cells < 0) -p.cellWidth else p.cellWidth
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(cellCenter(row, fromCol))
            repeat(kotlin.math.abs(cells)) {
                advanceEventTime(16)
                moveBy(Offset(step, 0f))
            }
            advanceEventTime(16)
            up()
        }
    }

    private fun dragLeft(cells: Int, row: Int = 3) = drag(-cells, row)

    @Test
    fun `a horizontal drag on the canvas is arrows when asked, and on a detached tab the first is the key that reconnects it`() {
        val heard = Heard()
        val box = detachedBox()
        canvasAlone(heard, dragArrows = true, session = box)
        dragLeft(5)
        compose.waitUntil(5_000) { box.state != SessionState.DETACHED }
        windowPasses()
        // A drag is no tap, no scroll and no selection.
        assertEquals(heard.toString(), 0, heard.taps)
        assertEquals(heard.toString(), 0, heard.twoFingerTaps)
    }

    // ---- through the Stage -------------------------------------------------------------------------------

    @Composable
    private fun Stage(session: TerminalSession, tools: StageTools, ui: TabUiState) {
        val actions = remember { ShellTabActions(graph.viewModel, ui, onActivated = {}) }
        StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
    }

    private fun stage(session: TerminalSession, ui: TabUiState = TabUiState()): StageTools {
        val tools = StageTools()
        stagedHostId = session.record.value.hostId
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { Stage(session, tools, ui) }
            }
        }
        awaitGrid(session)
        return tools
    }

    /** Settings › Gestures changed as the screen would change it, and the Stage recomposed on the change. */
    private fun gestures(change: (TerminalSettings) -> TerminalSettings) {
        val wanted = change(graph.viewModel.terminalSettings.value)
        runBlocking { graph.settings.updateTerminalSettings(change) }
        compose.waitUntil(5_000) { graph.viewModel.terminalSettings.value == wanted }
        compose.waitForIdle()
    }

    private fun pinchOut() {
        val density = context.resources.displayMetrics.density
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 8))
            down(1, cellCenter(5, 20))
            updatePointerBy(0, Offset(-30 * density, 0f))
            move()
            updatePointerBy(1, Offset(30 * density, 0f))
            move()
            up(0)
            up(1)
        }
    }

    private fun homelabFontSize(): Int? = graph.hosts.items.value.first { it.id == "homelab" }.appearance.fontSizeSp

    @Test
    fun `on stage the two-finger tap is what Settings makes it, a paste, the New tab sheet, or nothing`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        val ui = TabUiState()
        stage(live, ui)
        val sent = sentBy(live)
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", "uptime"))

        twoFingerTap()
        compose.waitUntil(5_000) { sent.any { "uptime" in it } }
        windowPasses()
        assertEquals("the paste opened nothing", null, ui.newTab)

        gestures { it.copy(twoFingerTap = TwoFingerTapAction.NEW_TAB) }
        sent.clear()
        twoFingerTap(row = 6)
        compose.waitUntil(5_000) { ui.newTab != null }
        windowPasses()
        assertTrue("nothing was pasted: $sent", sent.isEmpty())

        gestures { it.copy(twoFingerTap = TwoFingerTapAction.NOTHING) }
        ui.newTab = null
        twoFingerTap(row = 7)
        windowPasses()
        assertEquals(null, ui.newTab)
        assertTrue("nothing was pasted: $sent", sent.isEmpty())
        // The reset is no alternative's to give away: two two-finger taps still take the host's size back.
        runBlocking {
            val homelab = graph.hosts.items.value.first { it.id == "homelab" }
            graph.hosts.upsert(homelab.copy(appearance = homelab.appearance.copy(fontSizeSp = 18)))
        }
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 24))
            up(0)
            up(1)
            advanceEventTime(120)
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 24))
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { homelabFontSize() == null }
    }

    @Test
    fun `on stage a three-finger tap shares the rows in view when Settings says so, and is nothing when it says nothing`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        val tools = stage(live)
        compose.waitUntil(5_000) { deckKeys() > 0 }
        val started = shadowOf(context as Application)

        gestures { it.copy(threeFingerTap = ThreeFingerTapAction.SHARE_SCREEN_TEXT) }
        threeFingerTap()
        compose.waitUntil(5_000) { started.peekNextStartedActivity() != null }
        val chooser = started.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(tools.screenText(live.emulator), send.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith("ben@homelab:~/srv$ docker compose ps\n"))
        windowPasses()
        assertFalse("the Deck stays up", deckCollapsed())

        gestures { it.copy(threeFingerTap = ThreeFingerTapAction.NOTHING) }
        threeFingerTap(row = 6)
        windowPasses()
        assertEquals("no share", null, started.peekNextStartedActivity())
        assertFalse("and no Deck toggled", deckCollapsed())
        assertTrue(deckKeys() > 0)
    }

    @Test
    fun `on stage a pinch set to nothing leaves the font size alone, and the default steps it`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live)
        val appSize = runBlocking { graph.settings.terminalFont.first().sizeSp }

        gestures { it.copy(pinch = PinchAction.NOTHING) }
        pinchOut()
        windowPasses()
        assertEquals("the host keeps no size of its own", null, homelabFontSize())
        assertEquals(appSize, runBlocking { graph.settings.terminalFont.first().sizeSp })

        gestures { it.copy(pinch = PinchAction.FONT_SIZE) }
        pinchOut()
        compose.waitUntil(5_000) { runBlocking { graph.settings.terminalFont.first().sizeSp } == appSize + 1 }
        assertEquals("a host with no size of its own steps the app's", null, homelabFontSize())
    }

    private fun deckKeys(): Int = compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().size
    private fun deckCollapsed(): Boolean = compose.onAllNodes(hasContentDescription("Deck collapsed, tap to show it")).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `on stage a three-finger tap hides the Deck and another shows it again`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live)
        compose.waitUntil(5_000) { deckKeys() > 0 }
        assertFalse(deckCollapsed())
        threeFingerTap()
        compose.waitUntil(5_000) { deckCollapsed() }
        assertEquals("the Deck's keys are gone with it", 0, deckKeys())
        threeFingerTap(row = 6)
        compose.waitUntil(5_000) { deckKeys() > 0 }
        assertFalse(deckCollapsed())
    }

    @Test
    fun `on stage a two-finger double-tap takes the host's own font size away`() {
        StageFixture.seed(graph)
        runBlocking {
            val homelab = graph.hosts.items.value.first { it.id == "homelab" }
            graph.hosts.upsert(homelab.copy(appearance = homelab.appearance.copy(fontSizeSp = 18)))
        }
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live)
        compose.onNodeWithTag(TerminalTag).performTouchInput {
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 24))
            up(0)
            up(1)
            advanceEventTime(120)
            down(0, cellCenter(5, 4))
            down(1, cellCenter(5, 24))
            up(0)
            up(1)
        }
        compose.waitUntil(5_000) { graph.hosts.items.value.first { it.id == "homelab" }.appearance.fontSizeSp == null }
        windowPasses()
        assertEquals("the app's size is not what the reset touched", TerminalFont().sizeSp, runBlocking { graph.settings.terminalFont.first().sizeSp })
    }

    @Test
    fun `on stage a horizontal drag sends nothing while Settings has it off, and arrows once it is on`() {
        StageFixture.seed(graph)
        val box = detachedBox()
        stage(box)
        val reported = "Reconnecting, the key was not sent"

        // Off, the default: the drag is nothing. The tab stays detached and the Stage says nothing.
        dragLeft(5)
        settle(300)
        assertEquals(SessionState.DETACHED, box.state)
        assertFalse(shown(reported))
        // A three-finger tap on a detached tab has no Deck to toggle and shows none.
        threeFingerTap()
        settle(200)
        assertFalse(deckCollapsed())

        // On: the travel is arrows, and the first into a detached tab reconnects it and is reported (review #15).
        runBlocking { graph.settings.updateTerminalSettings { it.copy(horizontalDragArrows = true) } }
        compose.waitUntil(5_000) { graph.viewModel.terminalSettings.value.horizontalDragArrows }
        compose.waitForIdle()
        dragLeft(5)
        compose.waitUntil(5_000) { box.state != SessionState.DETACHED }
        waitForText(reported)
    }

    // ---- a right click through the Stage -----------------------------------------------------------------

    @OptIn(ExperimentalTestApi::class)
    private fun rightClick(row: Int = 5, col: Int = 10) {
        compose.onNodeWithTag(TerminalTag).performMouseInput {
            moveTo(cellCenter(row, col))
            press(MouseButton.Secondary)
            release(MouseButton.Secondary)
        }
    }

    private fun clip(text: String) = context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("test", text))

    @Test
    fun `on stage a right click on a live tab pastes through the gate, a multi-line clipboard opening the preview first`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        val tools = stage(live)
        val sent = sentBy(live)
        clip("uptime\ndf -h\n")
        rightClick()
        compose.waitUntil(5_000) { tools.pendingPaste != null }
        assertEquals("uptime\ndf -h\n", tools.pendingPaste!!.text)
        windowPasses()
        assertTrue("nothing is sent before the preview says so: $sent", sent.isEmpty())
        compose.runOnIdle { tools.pendingPaste = null }
        windowPasses()

        // One short line needs no preview, and goes as a paste.
        clip("uptime")
        rightClick(row = 6)
        compose.waitUntil(5_000) { sent.any { "uptime" in it } }
        assertEquals(null, tools.pendingPaste)
    }

    @Test
    fun `on stage a right click with the paste turned off in Settings sends nothing and opens nothing`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        val tools = stage(live)
        val sent = sentBy(live)
        gestures { it.copy(rightClickPaste = false) }
        clip("uptime\ndf -h\n")
        rightClick()
        windowPasses()
        assertEquals(null, tools.pendingPaste)
        clip("uptime")
        rightClick(row = 6)
        windowPasses()
        assertTrue("nothing was pasted: $sent", sent.isEmpty())
        assertEquals(null, tools.notice)
    }

    @Test
    fun `on stage a right click while the program has the mouse under 1000 is its report, not a paste`() {
        StageFixture.seed(graph)
        val live = StageFixture.liveHomelab().also { sessions += it }
        val tools = stage(live)
        val sent = sentBy(live)
        clip("uptime")
        live.emulator.write("\u001b[?1000h\u001b[?1006h")
        compose.waitUntil(5_000) { synchronized(live.emulator.lock) { live.emulator.mouseTracking } == MouseTracking.NORMAL }
        rightClick(row = 5, col = 10)
        compose.waitUntil(5_000) { sent.size >= 2 }
        windowPasses()
        assertEquals("the right button's press and release at column 11, row 6", listOf("\u001b[<2;11;6M", "\u001b[<2;11;6m"), sent)
        assertEquals(null, tools.pendingPaste)
    }

    @Test
    fun `on stage a right click on a detached tab says Not connected and does not reconnect it, whatever mode its frame was left in`() {
        StageFixture.seed(graph)
        val box = detachedBox()
        val tools = stage(box)
        val sent = sentBy(box)
        clip("uptime")
        rightClick()
        compose.waitUntil(5_000) { tools.notice == StageTools.NOT_CONNECTED }
        waitForText(StageTools.NOT_CONNECTED)
        windowPasses()
        assertEquals(SessionState.DETACHED, box.state)
        assertTrue("nothing was sent: $sent", sent.isEmpty())

        // Left in 1000 by the program that ran before the detach: still the paste's refusal, and no report.
        compose.runOnIdle { tools.notice = null }
        box.emulator.write("\u001b[?1000h\u001b[?1006h")
        compose.waitUntil(5_000) { synchronized(box.emulator.lock) { box.emulator.mouseTracking } == MouseTracking.NORMAL }
        rightClick(row = 6)
        compose.waitUntil(5_000) { tools.notice == StageTools.NOT_CONNECTED }
        windowPasses()
        assertEquals(SessionState.DETACHED, box.state)
        assertTrue("nothing was sent: $sent", sent.isEmpty())
        assertEquals(null, tools.pendingPaste)
    }

    // ---- against the sshd --------------------------------------------------------------------------------

    private fun cursorX(session: TerminalSession): Int = synchronized(session.emulator.lock) { session.emulator.cursorX }
    private fun cursorLine(session: TerminalSession): String = synchronized(session.emulator.lock) { session.emulator.screenText()[session.emulator.cursorY] }

    @Test
    fun `live, a horizontal drag moves the shell's cursor a cell per cell of travel, and types nothing`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val now = System.currentTimeMillis()
        val box = Host(
            id = "berth-test-box", name = "Berth test box", color = SwatchColor.TEAL, monogram = Host.monogramFor("Berth test box"),
            address = sshHost, port = sshPort, user = sshUser, auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText("Berth test box")
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!
        settle(1_200)
        awaitGrid(session)

        // A line at the prompt, the cursor at its end.
        val word = "berth-drag-arrows"
        session.sendText(word)
        compose.waitUntil(10_000) { cursorLine(session).trimEnd().endsWith(word) }
        settle(300)
        val end = cursorX(session)
        val row = synchronized(session.emulator.lock) { session.emulator.cursorY }
        assertTrue("the cursor sits at the end of the word", end >= word.length)

        // Off by default: five cells of travel move nothing.
        dragLeft(5, row)
        settle(500)
        assertEquals(end, cursorX(session))

        // On: five cells left are five Left arrows, five back are five Right, and the word is untouched.
        runBlocking { graph.settings.updateTerminalSettings { it.copy(horizontalDragArrows = true) } }
        compose.waitUntil(5_000) { graph.viewModel.terminalSettings.value.horizontalDragArrows }
        compose.waitForIdle()
        dragLeft(5, row)
        compose.waitUntil(10_000) { cursorX(session) == end - 5 }
        settle(300)
        drag(5, row, fromCol = 4)
        compose.waitUntil(10_000) { cursorX(session) == end }
        settle(300)
        assertTrue(cursorLine(session), cursorLine(session).trimEnd().endsWith(word))

        // Abandon the line so the shell exits cleanly.
        session.sendControl('c')
        settle(300)
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
    }
}

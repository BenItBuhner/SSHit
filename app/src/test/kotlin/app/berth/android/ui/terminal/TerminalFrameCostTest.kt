package app.berth.android.ui.terminal

import android.app.Application
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalAccessibility
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.ssh.SshSecurity
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalKey
import app.berth.terminal.TerminalListenerAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * What a frame costs between the read loop and the pixels, measured rather than read off the code:
 * the bytes the JVM allocates to capture a screen, and how many captures a burst of output makes
 * when its chunks land between two frames.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalFrameCostTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()
    private val theme = TerminalTheme.BERTH_DARK

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
    }

    @Test
    fun `capturing a screen allocates nothing once the frame has its rows`() {
        val emulator = screen(::plainRow)
        val frame = TerminalFrame()
        val bytes = perFrame { frame.capture(emulator, 0) }
        println("FRAMECOST capture ${COLS}x$ROWS: $bytes B a capture")
        assertEquals("bytes a capture", 0L, bytes)
    }

    @Test
    fun `a burst of output between two frames is captured once, and its last chunk at the next frame`() {
        // With a reader on, every capture is copied for it and bumps its version: a count of captures.
        shadowOf(context.getSystemService(AccessibilityManager::class.java)).setEnabled(true)
        val session = StageFixture.liveQuick().also { sessions += it }
        val accessibility = TerminalAccessibility()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                TerminalCanvas(
                    session = session,
                    theme = theme,
                    font = TerminalFont(),
                    sink = NoSink,
                    viewport = remember { TerminalViewport() },
                    modifier = Modifier.fillMaxSize(),
                    accessibility = accessibility,
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        quiesce(accessibility)
        val start = accessibility.version
        write(session, "chunk 0 of the burst\r\n")
        awaitWorker("the first chunk is captured at once") { accessibility.version == start + 1 }
        for (i in 1 until CHUNKS) {
            write(session, "chunk $i of the burst\r\n")
            settle()
        }
        val between = accessibility.version - start
        compose.mainClock.advanceTimeByFrame()
        awaitWorker("the last chunk is on screen") { accessibility.screenText().contains("chunk ${CHUNKS - 1} of the burst") }
        settle()
        val next = accessibility.version - start
        println("FRAMECOST burst: $CHUNKS chunks between two frames, $between captures; $next once the next frame came")
        assertEquals("captures between the frames", 1, between)
        assertEquals("captures with the next frame", 2, next)
    }

    /**
     * Bytes the calling thread allocates for one run of [block], after a warm-up: the least of
     * several windows of many runs. What the block allocates each run shows in every window; the
     * runtime's own one-off (about 2 KB, once, when the JIT recompiles the loop) shows in one.
     */
    private fun perFrame(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(WARMUP) { block() }
        return (0 until WINDOWS).minOf {
            val before = threads.getThreadAllocatedBytes(id)
            repeat(RUNS) { block() }
            threads.getThreadAllocatedBytes(id) - before
        } / RUNS
    }

    private fun screen(row: (Int) -> String): TerminalEmulator {
        val t = TerminalEmulator(COLS, ROWS, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        t.write((0 until ROWS).joinToString("\r\n") { row(it) })
        return t
    }

    /** An `ls --color` line: directories bold blue, scripts green, the rest plain. */
    private fun plainRow(i: Int): String = "\u001b[01;34mdir-$i\u001b[0m  file-$i.txt  \u001b[32mscript-$i.sh\u001b[0m  notes-$i.md  \u001b[01;34mbuild-$i\u001b[0m  Makefile"

    /** Every resumption queued on the test scheduler run, and a capture on the worker given time to come back. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            compose.waitForIdle()
            Thread.sleep(SETTLE_SLEEP_MS)
        }
        compose.waitForIdle()
    }

    /**
     * Frames advanced by hand until one passes with no capture: the canvas' first captures (which
     * may still be on the worker when the clock stops) are all in, and its loop waits on the screen
     * rather than on a frame.
     */
    private fun quiesce(accessibility: TerminalAccessibility) {
        repeat(QUIESCE_FRAMES) {
            val before = accessibility.version
            compose.mainClock.advanceTimeByFrame()
            settle()
            if (accessibility.version == before) return
        }
        fail("the canvas kept capturing with nothing written")
    }

    /** Settles until [done] holds, for a worker slower than [settle] allows; the clock stays where it is. */
    private fun awaitWorker(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + WORKER_TIMEOUT_NS
        while (true) {
            compose.waitForIdle()
            if (done()) return
            assertTrue(what, System.nanoTime() < deadline)
            Thread.sleep(SETTLE_SLEEP_MS)
        }
    }

    /** Bytes as the read loop hands them over: on a thread of its own, into the emulator. */
    private fun write(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val t = Thread { session.emulator.write(bytes) }
        t.start()
        t.join()
    }

    private object NoSink : TerminalInputSink {
        override fun onText(text: String) = Unit
        override fun onKey(key: TerminalKey, modifiers: Int) = Unit
    }

    private companion object {
        const val COLS = 80
        const val ROWS = 40
        const val WARMUP = 200
        const val RUNS = 1000
        const val WINDOWS = 5
        const val CHUNKS = 20
        const val SETTLE_ROUNDS = 4
        const val SETTLE_SLEEP_MS = 5L
        const val QUIESCE_FRAMES = 50
        const val WORKER_TIMEOUT_NS = 5_000_000_000L
    }
}

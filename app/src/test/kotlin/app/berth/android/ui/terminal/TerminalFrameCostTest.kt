package app.berth.android.ui.terminal

import android.app.Application
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * What a frame costs between the read loop and the pixels, measured rather than read off the code:
 * the bytes the JVM allocates to capture a screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TerminalFrameCostTest {
    private val theme = TerminalTheme.BERTH_DARK

    @Test
    fun `capturing a screen allocates nothing once the frame has its rows`() {
        val emulator = screen(::plainRow)
        val frame = TerminalFrame()
        val bytes = perFrame { frame.capture(emulator, 0) }
        println("FRAMECOST capture ${COLS}x$ROWS: $bytes B a capture")
        assertEquals("bytes a capture", 0L, bytes)
    }

    /** Bytes the calling thread allocates for one run of [block], averaged over many after a warm-up. */
    private fun perFrame(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(WARMUP) { block() }
        val before = threads.getThreadAllocatedBytes(id)
        repeat(RUNS) { block() }
        return (threads.getThreadAllocatedBytes(id) - before) / RUNS
    }

    private fun screen(row: (Int) -> String): TerminalEmulator {
        val t = TerminalEmulator(COLS, ROWS, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        t.write((0 until ROWS).joinToString("\r\n") { row(it) })
        return t
    }

    /** An `ls --color` line: directories bold blue, scripts green, the rest plain. */
    private fun plainRow(i: Int): String = "\u001b[01;34mdir-$i\u001b[0m  file-$i.txt  \u001b[32mscript-$i.sh\u001b[0m  notes-$i.md  \u001b[01;34mbuild-$i\u001b[0m  Makefile"

    private companion object {
        const val COLS = 80
        const val ROWS = 40
        const val WARMUP = 200
        const val RUNS = 1000
    }
}

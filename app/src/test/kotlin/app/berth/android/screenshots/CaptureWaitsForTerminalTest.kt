package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.terminal.TerminalCanvas
import app.berth.android.ui.terminal.TerminalCanvasPending
import app.berth.android.ui.terminal.TerminalInputSink
import app.berth.android.ui.terminal.TerminalPaintsCache
import app.berth.android.ui.terminal.TerminalViewport
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.TerminalKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * [captureAudited] takes its picture of a terminal only once the canvas draws the screen as it
 * stands (brief item 13): the frame of the latest output, which the canvas captures on a worker
 * Compose's idling does not see, and a grid that has followed the canvas's width.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class CaptureWaitsForTerminalTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir: File = Files.createTempDirectory("berth-capture-waits").toFile()
    private val sessions = ArrayList<TerminalSession>()

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        outDir.deleteRecursively()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    /** Homelab's live frame on a canvas of its own, [width] wide. */
    private fun canvas(width: () -> Dp): TerminalSession {
        val session = StageFixture.liveHomelab().also { sessions += it }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val viewport = remember { TerminalViewport() }
                    val sink = remember {
                        object : TerminalInputSink {
                            override fun onText(text: String) = Unit
                            override fun onKey(key: TerminalKey, modifiers: Int) = Unit
                        }
                    }
                    TerminalCanvas(
                        session = session,
                        theme = TerminalTheme.BERTH_DARK,
                        font = TerminalFont(),
                        sink = sink,
                        viewport = viewport,
                        modifier = Modifier.fillMaxHeight().width(width()),
                    )
                }
            }
        }
        return session
    }

    /** The columns the canvas on screen holds at the app's font. */
    private fun canvasCols(): Int {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val paints = TerminalPaintsCache.get(ApplicationProvider.getApplicationContext(), TerminalFont(), res.displayMetrics.density, res.configuration.fontScale)
        return (compose.onNodeWithTag(TerminalTag).fetchSemanticsNode().size.width / paints.cellWidth).toInt()
    }

    @Test
    fun `a capture waits for the frame of the latest output, which the canvas captures on a worker`() {
        val session = canvas { 411.dp }
        capture("before")
        assertNull(TerminalCanvasPending.pending())

        // New output, then the emulator held from another thread: the canvas's capture of it waits on the lock, off
        // the composition, for longer than the audit after a picture takes.
        session.emulator.write("\r\nben@homelab:~/srv$ echo landed\r\nlanded\r\n")
        val holding = CountDownLatch(1)
        val holder = thread {
            synchronized(session.emulator.lock) {
                holding.countDown()
                Thread.sleep(HOLD_MS)
            }
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
        capture("after-output")
        assertNull("the picture was taken before the canvas drew the output", TerminalCanvasPending.pending())
        holder.join()
    }

    @Test
    fun `a capture waits for the grid to follow the canvas's new width`() {
        var width by mutableStateOf(411.dp)
        val session = canvas { width }
        capture("wide")
        val wide = session.emulator.cols
        assertEquals(canvasCols(), wide)

        width = 300.dp
        capture("narrowed")
        assertNull("the picture was taken before the canvas drew its new width", TerminalCanvasPending.pending())
        assertEquals(canvasCols(), session.emulator.cols)
        assertTrue("${session.emulator.cols} columns, from $wide", session.emulator.cols < wide)
    }

    private companion object {
        const val HOLD_MS = 2_000L
    }
}

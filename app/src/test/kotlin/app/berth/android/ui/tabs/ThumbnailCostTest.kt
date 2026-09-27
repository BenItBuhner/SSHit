package app.berth.android.ui.tabs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.hasContentDescription
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.session.TerminalSession
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.terminal.TerminalPaintsCache
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory
import javax.management.ObjectName

/**
 * What the switcher's cards cost, measured rather than read off the code, over eight live cards laid
 * out as the switcher lays them (two columns of 1.6:1 cards, each clipped to its corners), every
 * card the same screen and one of them changing: the [TerminalPaints] the cards keep alive, and the
 * glyphs they hand Skia, counted by [CountingCanvasShadows] however they are drawn. A draw of the
 * whole window runs every card's draw into a software canvas, as a capture does, and records the
 * layers that changed into the display lists the render thread replays each frame it draws the
 * switcher; the first draw records them all.
 *
 * The figures it prints (`THUMBCOST`) compare only between runs of this class alone, the two sides
 * of a change run in turn (before, after, before, after). The glyph counts are exact and the same
 * on every run; the bytes a whole draw allocates move by a few hundred from run to run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    application = Application::class,
    qualifiers = "w411dp-h914dp-420dpi",
    shadows = [CountingCanvasShadows.Raster::class, CountingCanvasShadows.Recording::class],
)
class ThumbnailCostTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val sessions = ArrayList<TerminalSession>()
    private val theme = TerminalTheme.BERTH_DARK
    private val font = TerminalFont()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
    }

    @Test
    fun `eight cards keep one set of paints alive`() {
        // Sets other tests left in the process-wide cache would stand in for the one these cards make.
        TerminalPaintsCache.clear()
        val before = liveInstances(TerminalPaints::class.java.name)
        mount()
        val alive = liveInstances(TerminalPaints::class.java.name) - before
        println("THUMBCOST paints: $alive TerminalPaints alive for $CARDS cards, ${"%.3f".format(alive.toDouble() / CARDS)} a card")
        assertEquals("TerminalPaints alive for $CARDS cards at one font", 1L, alive)
    }

    @Test
    fun `a whole draw of the switcher draws only the card whose frame moved`() {
        mount()
        val window = compose.activity.window.decorView
        val target = Canvas(Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888))
        val first = counted { window.draw(target) }
        val still = ArrayList<Counts>()
        val moved = ArrayList<Counts>()
        repeat(PASSES) { pass ->
            still += counted { window.draw(target) }
            tick(sessions[0], pass + 1)
            compose.waitForIdle()
            moved += counted { window.draw(target) }
        }
        val card = first.rasterGlyphs / CARDS
        val stillBytes = perDraw { window.draw(target) }
        println("THUMBCOST first draw of $CARDS cards: $first, $card glyphs a card")
        println("THUMBCOST whole draw, nothing changed: ${still.distinct().joinToString(" / ")}; $stillBytes B a draw")
        println("THUMBCOST whole draw, one of $CARDS changed: ${moved.distinct().joinToString(" / ")}")
        assertEquals("the display lists the render thread replays hold no glyphs", 0L, first.recordedGlyphs)
        assertEquals("the display lists hold each card's frame", CARDS.toLong(), first.recordedBitmaps)
        for (c in still) assertEquals("nothing changed: glyphs drawn", 0L, c.glyphs)
        for (c in moved) {
            assertEquals("one card changed: glyphs drawn, into its frame", card, c.rasterGlyphs)
            assertEquals("one card changed: glyphs recorded", 0L, c.recordedGlyphs)
            assertEquals("one card changed: frames recorded, its own", 1L, c.recordedBitmaps)
        }
    }

    /** Eight live cards over sessions showing the same screen, once each has drawn its first frame. */
    private fun mount() {
        repeat(CARDS) {
            val session = StageFixture.liveQuick().also { sessions += it }
            write(session, "\u001b[2J\u001b[H" + (0 until ROWS - 1).joinToString("\r\n", postfix = "\r\n") { row(it) })
            tick(session, 0)
        }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize()) {
                    items(sessions.size, key = { it }) { i ->
                        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(BerthRadius.row)).background(theme.background.toColor())) {
                            FrameThumbnail(sessions[i], theme, font, live = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of", substring = true)).fetchSemanticsNodes().size == CARDS }
        compose.waitForIdle()
    }

    /** An `ls --color` line: directories bold blue, scripts green, the rest plain. */
    private fun row(i: Int): String = "\u001b[01;34mdir-$i\u001b[0m  file-$i.txt  \u001b[32mscript-$i.sh\u001b[0m  notes-$i.md"

    /** Rewrites the screen's last row in place, the same width every time, so a changed frame has the glyphs it had. */
    private fun tick(session: TerminalSession, n: Int) = write(session, "\u001b[$ROWS;1Htick ${"%03d".format(n)}")

    private data class Counts(val rasterGlyphs: Long, val rasterBitmaps: Long, val recordedGlyphs: Long, val recordedBitmaps: Long) {
        val glyphs: Long get() = rasterGlyphs + recordedGlyphs

        override fun toString() = "$rasterGlyphs glyphs and $rasterBitmaps bitmaps drawn, $recordedGlyphs glyphs and $recordedBitmaps bitmaps recorded"
    }

    private fun counted(block: () -> Unit): Counts {
        CountingCanvasShadows.reset()
        block()
        return Counts(CountingCanvasShadows.rasterGlyphs, CountingCanvasShadows.rasterBitmaps, CountingCanvasShadows.recordedGlyphs, CountingCanvasShadows.recordedBitmaps)
    }

    /** Bytes the calling thread allocates for one run of [block] after a warm-up: the least of several windows of runs. */
    private fun perDraw(block: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        repeat(WARMUP) { block() }
        return (0 until WINDOWS).minOf {
            val before = threads.getThreadAllocatedBytes(id)
            repeat(RUNS) { block() }
            threads.getThreadAllocatedBytes(id) - before
        } / RUNS
    }

    /** Instances of [className] still reachable, from the JVM's class histogram, which collects first. */
    private fun liveInstances(className: String): Long {
        val histogram = ManagementFactory.getPlatformMBeanServer().invoke(
            ObjectName("com.sun.management:type=DiagnosticCommand"),
            "gcClassHistogram",
            arrayOf<Any>(emptyArray<String>()),
            arrayOf(Array<String>::class.java.name),
        ) as String
        return histogram.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 4 && it[3] == className }
            .sumOf { it[1].toLong() }
    }

    /** Bytes as the read loop hands them over: on a thread of its own, into the emulator. */
    private fun write(session: TerminalSession, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val t = Thread { session.emulator.write(bytes) }
        t.start()
        t.join()
    }

    private companion object {
        const val CARDS = 8
        const val ROWS = 24
        const val PASSES = 5
        const val WARMUP = 10
        const val RUNS = 20
        const val WINDOWS = 3
    }
}

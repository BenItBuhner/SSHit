package app.berth.android.ui.tabs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.hasContentDescription
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.session.TerminalSession
import app.berth.android.ui.terminal.TerminalPaints
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.toColor
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * What the switcher's cards cost, measured rather than read off the code, over eight live cards laid
 * out as the switcher lays them (two columns of 1.6:1 cards, each clipped to its corners), every
 * card the same screen and one of them changing: the paints the cards draw their glyphs with (a
 * [TerminalPaints] a card built for itself would bring its own), and the glyphs they hand Skia,
 * counted by [CountingCanvasShadows] however they are drawn. A draw of the whole window runs every
 * card's draw into a software canvas, as a capture does, and records the layers that changed into
 * the display lists the render thread replays each frame it draws the switcher; the first draw
 * records them all.
 *
 * The glyph and paint counts it prints (`THUMBCOST`) are exact and the same on every run. The bytes
 * a whole draw allocates move by a few hundred from run to run and compare only between runs of
 * this class alone, the two sides of a change run in turn (before, after, before, after).
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
    private val shown = mutableIntStateOf(CARDS)
    private lateinit var window: View
    private lateinit var target: Canvas
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
    fun `eight cards draw their glyphs with one card's paints`() {
        mount(1)
        val one = counted { window.draw(target) }.paints
        shown.intValue = CARDS
        awaitCards()
        val eight = counted { window.draw(target) }.paints
        val all = one + eight
        println("THUMBCOST paints: one card draws its glyphs with ${one.size} paints, $CARDS cards with ${all.size}")
        assertTrue("one card draws its glyphs", one.isNotEmpty())
        assertEquals("paints $CARDS cards draw their glyphs with, against one card's", one.size, all.size)
    }

    @Test
    fun `a whole draw of the switcher draws only the card whose frame moved`() {
        mount()
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

    /**
     * Eight live sessions showing the same screen, the first [cards] of them on cards, once each card
     * is up; [shown] puts the rest up later. Nothing is drawn until a test draws [window].
     */
    private fun mount(cards: Int = CARDS) {
        repeat(CARDS) {
            val session = StageFixture.liveQuick().also { sessions += it }
            write(session, "\u001b[2J\u001b[H" + (0 until ROWS - 1).joinToString("\r\n", postfix = "\r\n") { row(it) })
            tick(session, 0)
        }
        shown.intValue = cards
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize()) {
                    items(shown.intValue, key = { it }) { i ->
                        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(BerthRadius.row)).background(theme.background.toColor())) {
                            FrameThumbnail(sessions[i], theme, font, live = true, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        awaitCards()
        window = compose.activity.window.decorView
        target = Canvas(Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888))
    }

    private fun awaitCards() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Frame of", substring = true)).fetchSemanticsNodes().size == shown.intValue }
        compose.waitForIdle()
    }

    /** An `ls --color` line: directories bold blue, scripts green, the rest plain. */
    private fun row(i: Int): String = "\u001b[01;34mdir-$i\u001b[0m  file-$i.txt  \u001b[32mscript-$i.sh\u001b[0m  notes-$i.md"

    /** Rewrites the screen's last row in place, the same width every time, so a changed frame has the glyphs it had. */
    private fun tick(session: TerminalSession, n: Int) = write(session, "\u001b[$ROWS;1Htick ${"%03d".format(n)}")

    private data class Counts(val rasterGlyphs: Long, val rasterBitmaps: Long, val recordedGlyphs: Long, val recordedBitmaps: Long, val paints: Set<Long>) {
        val glyphs: Long get() = rasterGlyphs + recordedGlyphs

        override fun toString() = "$rasterGlyphs glyphs and $rasterBitmaps bitmaps drawn, $recordedGlyphs glyphs and $recordedBitmaps bitmaps recorded"
    }

    private fun counted(block: () -> Unit): Counts {
        CountingCanvasShadows.reset()
        block()
        return Counts(CountingCanvasShadows.rasterGlyphs, CountingCanvasShadows.rasterBitmaps, CountingCanvasShadows.recordedGlyphs, CountingCanvasShadows.recordedBitmaps, HashSet(CountingCanvasShadows.glyphPaints))
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

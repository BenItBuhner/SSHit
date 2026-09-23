package app.berth.android.ui.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.CellPos
import app.berth.terminal.CellRange
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * What the renderer puts in the pixels under a search's current match (spec C17): the accent,
 * opaque, with the glyphs in the theme's background, whatever colour the cell's own text had. A
 * bold green prompt under the accent at 60% was under 2:1; this pair is the one the accent was
 * chosen for. Plain matches and the selection keep the glyph's own colour, as before. And the text
 * it hands the canvas for a cell that carries combining marks.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TerminalRendererTest {
    private val theme = TerminalTheme.BERTH_DARK
    private val accent = 0xC97B4A
    private val green = theme.ansi[10]

    private fun near(a: Int, b: Int, tolerance: Int = 24): Boolean =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    /** Green by hue, loose enough for the theme's muted green (0xA6CB8C) at full coverage; the accent, the background and their blends all have red over green. */
    private fun greenish(rgb: Int): Boolean = Color.green(rgb) > Color.red(rgb) + 16 && Color.green(rgb) > Color.blue(rgb) + 32

    private fun render(overlay: FrameOverlay?): Pair<Bitmap, TerminalPaints> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val paints = TerminalPaints(context, TerminalFont(sizeSp = 16), density = 2f, fontScale = 1f)
        val t = TerminalEmulator(12, 2, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        // `demo` in bright bold green, as a prompt paints it, then plain text.
        t.write("\u001b[1;92mdemo\u001b[0m@host")
        val frame = TerminalFrame()
        frame.capture(t, 0)
        val w = (12 * paints.cellWidth).toInt()
        val h = (2 * paints.cellHeight).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TerminalRenderer.draw(Canvas(bitmap), frame, paints, theme, boldAsBright = false, w.toFloat(), h.toFloat(), showCursor = false, focused = true, overlay = overlay)
        return bitmap to paints
    }

    /** Every pixel of the cells [from] to [through] on row 0, with the frame's alpha dropped. */
    private fun cells(bitmap: Bitmap, paints: TerminalPaints, from: Int, through: Int): List<Int> {
        val out = ArrayList<Int>()
        for (y in 0 until paints.cellHeight.toInt()) {
            for (x in (from * paints.cellWidth).toInt() until ((through + 1) * paints.cellWidth).toInt()) {
                out.add(bitmap.getPixel(x, y) and 0xFFFFFF)
            }
        }
        return out
    }

    @Test
    fun `a green cell under the current match is the accent with its glyph in the background colour`() {
        val overlay = FrameOverlay().apply {
            current = CellRange(CellPos(0, 0), CellPos(0, 3))
            currentColor = 0xFF000000.toInt() or accent
            currentFg = theme.background
        }
        val (bitmap, paints) = render(overlay)
        val px = cells(bitmap, paints, 0, 3)
        assertTrue("the fill is the accent", px.count { near(it, accent) } > px.size / 3)
        assertTrue("the glyph is the background colour", px.any { near(it, theme.background) })
        assertEquals("no green under the current match", 0, px.count { greenish(it) })
        // Outside the match the prompt is still green.
        assertTrue(cells(bitmap, paints, 5, 8).none { near(it, accent) })
        assertTrue(cells(bitmap, paints, 5, 8).all { near(it, theme.background) || near(it, theme.foreground) || !greenish(it) })
    }

    @Test
    fun `without an overlay the same cell draws its own green`() {
        val (bitmap, paints) = render(null)
        val px = cells(bitmap, paints, 0, 3)
        assertTrue("a bold green glyph is on the cell", px.any { greenish(it) && near(it, green, 60) })
        assertTrue(px.none { near(it, accent) })
    }

    @Test
    fun `a plain match keeps the glyph's own colour over the selection fill`() {
        val overlay = FrameOverlay().apply {
            matches.add(CellRange(CellPos(0, 0), CellPos(0, 3)))
            matchColor = 0xFF000000.toInt() or theme.selection
            currentFg = theme.background
        }
        val (bitmap, paints) = render(overlay)
        val px = cells(bitmap, paints, 0, 3)
        assertTrue("the fill is the selection colour", px.count { near(it, theme.selection) } > px.size / 3)
        assertTrue("the glyph stays green", px.any { greenish(it) })
    }

    @Test
    fun `a letter with combining marks reaches the canvas with its marks, in the same draw as the letter`() {
        val line = "cafe\u0301 na\u0308ive"
        val context = ApplicationProvider.getApplicationContext<Application>()
        val paints = TerminalPaints(context, TerminalFont(sizeSp = 16), density = 2f, fontScale = 1f)
        val t = TerminalEmulator(12, 2, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        t.write(line)
        val frame = TerminalFrame()
        frame.capture(t, 0)
        val w = (12 * paints.cellWidth).toInt()
        val h = (2 * paints.cellHeight).toInt()
        val canvas = RecordingCanvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        TerminalRenderer.draw(canvas, frame, paints, theme, boldAsBright = false, w.toFloat(), h.toFloat(), showCursor = false, focused = true)
        assertEquals("the text handed to the canvas, in order", line, canvas.texts.joinToString(""))
        // A mark in a draw of its own is not shaped onto the letter before it.
        assertTrue("e and its acute in one draw: ${canvas.texts}", canvas.texts.any { "e\u0301" in it })
        assertTrue("a and its diaeresis in one draw: ${canvas.texts}", canvas.texts.any { "a\u0308" in it })
    }

    /** A canvas over a bitmap that keeps the text of every draw it is handed, and draws it. */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = ArrayList<String>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += text
            super.drawText(text, x, y, paint)
        }

        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += text.substring(start, end)
            super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharSequence, start: Int, end: Int, x: Float, y: Float, paint: Paint) {
            texts += text.subSequence(start, end).toString()
            super.drawText(text, start, end, x, y, paint)
        }

        override fun drawText(text: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
            texts += String(text, index, count)
            super.drawText(text, index, count, x, y, paint)
        }
    }
}

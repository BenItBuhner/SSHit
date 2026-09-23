package app.berth.android.ui.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ui.a11y.MAX_INTERFACE_FONT_SCALE
import app.berth.domain.model.TerminalFont
import app.berth.domain.model.TerminalTheme
import app.berth.terminal.TerminalEmulator
import app.berth.terminal.TerminalListenerAdapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The bundled Symbols Nerd Font Mono against what prompts and listings actually print: every
 * private-use code point in starship's Nerd Font preset, powerlevel10k's nerdfont-v3 table, and
 * lsd's and eza's icon tables (`nerd-font-glyphs.txt`, read from each project's source) is written
 * to an emulator and drawn by the renderer into a frame, as the canvas draws one, in the default
 * family, in IBM Plex Mono (which has none of them) and in the device's monospace. Every one of
 * those cells has ink, and none is the glyph the fallback has no entry for. The fallback's glyphs
 * are an em wide against a cell of 0.6 em, so the renderer fits them: icons into their cell, or
 * two when a blank follows, and Powerline dividers over the whole cell, and neither leaves its row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class NerdFontGlyphsTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val theme = TerminalTheme.BERTH_DARK

    private class Source(val name: String, val origin: String, val codePoints: List<Int>)

    private val sources: List<Source> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/nerd-font-glyphs.txt")) { "nerd-font-glyphs.txt is on the test classpath" }.bufferedReader().use { it.readText() }
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val (name, repo, file, points) = line.split('\t')
            Source(name, "$repo $file", points.split(' ').map { it.toInt(16) })
        }
    }

    @After
    fun tearDown() {
        TypefaceCache.clear()
        TerminalPaintsCache.clear()
    }

    private class Frame(val bitmap: Bitmap, val paints: TerminalPaints, val cols: Int)

    /** [codePoints] written [COLS] to a row into an emulator and drawn by the renderer, the way the canvas draws a frame. */
    private fun draw(font: TerminalFont, codePoints: List<Int>): Frame {
        val paints = TerminalPaints(context, font, density = 2f, fontScale = 1f)
        val rows = (codePoints.size + COLS - 1) / COLS
        val t = TerminalEmulator(COLS, rows, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        codePoints.chunked(COLS).forEachIndexed { row, chunk ->
            t.write("\u001b[${row + 1};1H" + chunk.joinToString("") { String(Character.toChars(it)) })
        }
        val frame = TerminalFrame()
        frame.capture(t, 0)
        val w = (COLS * paints.cellWidth).toInt()
        val h = (rows * paints.cellHeight).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TerminalRenderer.draw(Canvas(bitmap), frame, paints, theme, boldAsBright = false, w.toFloat(), h.toFloat(), showCursor = false, focused = true, overlay = null)
        return Frame(bitmap, paints, COLS)
    }

    /** The pixels of the cell holding the [index]th code point written, with the frame's alpha dropped. */
    private fun cell(frame: Frame, index: Int): List<Int> {
        val row = index / frame.cols
        val col = index % frame.cols
        val p = frame.paints
        val out = ArrayList<Int>()
        for (y in (row * p.cellHeight).toInt() until ((row + 1) * p.cellHeight).toInt()) {
            for (x in (col * p.cellWidth).toInt() until ((col + 1) * p.cellWidth).toInt()) out.add(frame.bitmap.getPixel(x, y) and 0xFFFFFF)
        }
        return out
    }

    private fun inked(pixels: List<Int>): Int = pixels.count { it != theme.background and 0xFFFFFF }

    /** [text], escapes and all, written into a one-row emulator [cols] wide and drawn by the renderer. */
    private fun drawLine(font: TerminalFont, text: String, cols: Int = 8): Frame {
        val paints = TerminalPaints(context, font, density = 2f, fontScale = 1f)
        val t = TerminalEmulator(cols, 1, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        t.write(text)
        val frame = TerminalFrame()
        frame.capture(t, 0)
        val w = (cols * paints.cellWidth).toInt()
        val h = paints.cellHeight.toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TerminalRenderer.draw(Canvas(bitmap), frame, paints, theme, boldAsBright = false, w.toFloat(), h.toFloat(), showCursor = false, focused = true, overlay = null)
        return Frame(bitmap, paints, cols)
    }

    /** Cell [col]'s pixels [inset] in from its sides, clear of the antialiased edge a neighbour leaves on the boundary. */
    private fun inner(frame: Frame, col: Int, inset: Int = 2): List<Int> {
        val p = frame.paints
        val out = ArrayList<Int>()
        for (y in 0 until p.cellHeight.toInt()) {
            for (x in (col * p.cellWidth).toInt() + inset until ((col + 1) * p.cellWidth).toInt() - inset) out.add(frame.bitmap.getPixel(x, y) and 0xFFFFFF)
        }
        return out
    }

    private fun str(cp: Int): String = String(Character.toChars(cp))

    /**
     * [codePoints], each with a blank after it so an icon has its two cells of room and draws at its
     * largest, [COLS] / 2 to a row on every other row of a frame at [fontScale]: its first and last
     * rows and each row between two of glyphs are blank, and a frame drawn whole shows ink that leaves
     * its row where a one-row bitmap would clip it.
     */
    private fun drawSpaced(font: TerminalFont, fontScale: Float, codePoints: List<Int>): Frame {
        val paints = TerminalPaints(context, font, density = 2f, fontScale = fontScale)
        val chunks = codePoints.chunked(COLS / 2)
        val rows = chunks.size * 2 + 1
        val t = TerminalEmulator(COLS, rows, 0, TerminalListenerAdapter())
        t.applyTheme(theme.ansi.toIntArray(), theme.foreground, theme.background)
        chunks.forEachIndexed { i, chunk ->
            t.write("\u001b[${i * 2 + 2};1H" + chunk.joinToString("") { str(it) + " " })
        }
        val frame = TerminalFrame()
        frame.capture(t, 0)
        val w = (COLS * paints.cellWidth).toInt()
        val h = (rows * paints.cellHeight).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        TerminalRenderer.draw(Canvas(bitmap), frame, paints, theme, boldAsBright = false, w.toFloat(), h.toFloat(), showCursor = false, focused = true, overlay = null)
        return Frame(bitmap, paints, COLS)
    }

    @Test
    fun `a fallback icon shrinks into its own cell and leaves the digit printed hard against it whole`() {
        // powerlevel10k's segments print an icon against what follows it: its home outline, then a count.
        for (family in listOf("IBM Plex Mono", TerminalFonts.DEFAULT)) {
            val font = TerminalFont(family = family, sizeSp = 16)
            val icon = drawLine(font, str(0xF06A1) + "1")
            val plain = drawLine(font, " 1")
            assertTrue("the icon draws in $family", inked(inner(icon, 0)) >= MIN_INK)
            assertEquals("the 1 after the icon in $family is the 1 alone", inner(plain, 1), inner(icon, 1))
        }
    }

    @Test
    fun `with a blank after it a fallback icon has two cells of room and stops short of the third`() {
        val font = TerminalFont(family = "IBM Plex Mono", sizeSp = 16)
        val roomy = drawLine(font, str(0xF418) + " x")
        val tight = drawLine(font, str(0xF418) + "x")
        assertTrue("the icon reaches into the blank after it", inked(inner(roomy, 1)) > 0)
        assertTrue("and draws larger than against a glyph", inked(inner(roomy, 0)) + inked(inner(roomy, 1)) > inked(inner(tight, 0)))
        assertEquals("the x two cells on is the x alone", inner(drawLine(font, "  x"), 2), inner(roomy, 2))
    }

    @Test
    fun `a Powerline divider from the fallback fills its cell top to bottom, and a family's own draws as the family made it`() {
        val plex = drawLine(TerminalFont(family = "IBM Plex Mono", sizeSp = 16), str(0xE0B0))
        val p = plex.paints
        val rows = (0 until p.cellHeight.toInt()).filter { y ->
            (0 until p.cellWidth.toInt()).any { x -> plex.bitmap.getPixel(x, y) and 0xFFFFFF != theme.background and 0xFFFFFF }
        }
        assertTrue("the divider's ink starts at the cell's top (${rows.first()})", rows.first() <= 1)
        assertTrue("and ends at its bottom (${rows.last()} of ${p.cellHeight.toInt()})", rows.last() >= p.cellHeight.toInt() - 2)
        assertEquals("and stays in its cell", 0, inked(inner(plex, 1)))

        // JetBrains Mono has its own arrows a cell wide: the renderer draws those untouched.
        val jb = drawLine(TerminalFont(family = TerminalFonts.DEFAULT, sizeSp = 16), str(0xE0B0))
        val expected = Bitmap.createBitmap(jb.bitmap.width, jb.bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(expected).apply {
            drawColor(0xFF000000.toInt() or theme.background)
            drawText(str(0xE0B0), 0f, jb.paints.baseline, jb.paints.regular.apply { color = 0xFF000000.toInt() or theme.foreground })
        }
        assertTrue("JetBrains Mono's own U+E0B0 is a cell wide", Paint().apply { typeface = jb.paints.regular.typeface; textSize = jb.paints.regular.textSize }.measureText(str(0xE0B0)) <= jb.paints.cellWidth * 1.05f)
        assertTrue("and draws as drawText draws it", expected.sameAs(jb.bitmap))
    }

    @Test
    fun `every icon at its largest and every fallback Powerline divider keeps its ink in its row, at 1x and at the cap`() {
        val plexFallback = Paint().apply { typeface = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = true)[0] }
        val dividers = (0xE0B0..0xE0D7).filter { plexFallback.hasGlyph(str(it)) }
        assertTrue("the fallback has the Powerline dividers", dividers.size > 30)
        val glyphs = (sources.flatMap { it.codePoints } + dividers).distinct()
        val chunks = glyphs.chunked(COLS / 2)
        val failures = ArrayList<String>()
        for (family in listOf(TerminalFonts.DEFAULT, "IBM Plex Mono", TerminalFonts.SYSTEM)) {
            for (lineHeight in listOf(1.0f, 1.2f)) {
                for (scale in listOf(1f, MAX_INTERFACE_FONT_SCALE)) {
                    val frame = drawSpaced(TerminalFont(family = family, sizeSp = 16, lineHeight = lineHeight), scale, glyphs)
                    val cw = frame.paints.cellWidth
                    val ch = frame.paints.cellHeight
                    val leaks = sortedSetOf<String>()
                    // Row 2i is the blank between chunk i - 1 and chunk i; only the pixels wholly inside it
                    // count, and one in its upper half is ink from the row above, one in its lower half from below.
                    for (i in 0..chunks.size) {
                        val from = ceil(i * 2 * ch).toInt()
                        val to = floor((i * 2 + 1) * ch).toInt().coerceAtMost(frame.bitmap.height)
                        val middle = (i * 2 + 0.5f) * ch
                        for (y in from until to) for (x in 0 until frame.bitmap.width) {
                            if (frame.bitmap.getPixel(x, y) and 0xFFFFFF == theme.background and 0xFFFFFF) continue
                            val pair = (x / cw).toInt() / 2
                            if (y + 0.5f < middle) chunks.getOrNull(i - 1)?.getOrNull(pair)?.let { leaks += "U+%04X below its row".format(it) }
                            else chunks.getOrNull(i)?.getOrNull(pair)?.let { leaks += "U+%04X above its row".format(it) }
                        }
                    }
                    if (leaks.isNotEmpty()) failures += "$family at line height $lineHeight and ${scale}x: $leaks"
                }
            }
        }
        assertTrue("ink leaves its row in ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test
    fun `the four sources are all there, and every code point in them is in the bundled font's cmap`() {
        assertEquals(listOf("starship", "p10k", "lsd", "eza"), sources.map { it.name })
        for (s in sources) assertTrue("${s.name} lists its icons (${s.origin})", s.codePoints.size > 100)
        val paint = Paint().apply { typeface = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = true)[0] }
        val bare = Paint().apply { typeface = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = false)[0] }
        for (s in sources) {
            val missing = s.codePoints.filterNot { paint.hasGlyph(String(Character.toChars(it))) }
            assertTrue("${s.name} has no glyph for ${missing.joinToString { "U+%04X".format(it) }}", missing.isEmpty())
            assertFalse("${s.name}'s icons are the fallback's, not the family's own", s.codePoints.any { bare.hasGlyph(String(Character.toChars(it))) })
        }
    }

    @Test
    fun `every icon the prompts and listings print draws in a frame, in every family, and none is the missing glyph`() {
        // A private-use code point outside every set the font collects: what a cell with no glyph for it draws.
        val none = listOf(MISSING)
        for (family in listOf(TerminalFonts.DEFAULT, "IBM Plex Mono", TerminalFonts.SYSTEM)) {
            val font = TerminalFont(family = family, sizeSp = 16)
            val missing = cell(draw(font, none), 0)
            assertFalse("U+%04X is in no set".format(MISSING), Paint().apply { typeface = TypefaceCache.forFamily(context, family, nerdFallback = true)[0] }.hasGlyph(String(Character.toChars(MISSING))))
            for (s in sources) {
                val frame = draw(font, s.codePoints)
                s.codePoints.forEachIndexed { i, cp ->
                    val px = cell(frame, i)
                    assertTrue("${s.name}'s U+%04X in $family draws ink".format(cp), inked(px) >= MIN_INK)
                    assertTrue("${s.name}'s U+%04X in $family is not the missing glyph".format(cp), px != missing)
                }
            }
        }
    }

    @Test
    fun `with the fallback off the same icons have nothing to draw them in IBM Plex Mono`() {
        val icons = sources.flatMap { it.codePoints }.distinct()
        val frame = draw(TerminalFont(family = "IBM Plex Mono", sizeSp = 16, nerdFontFallback = false), icons)
        val drawn = draw(TerminalFont(family = "IBM Plex Mono", sizeSp = 16), icons)
        // Off, each cell is what the family and the system draw for a glyph neither has; on, the symbols font's own.
        val same = icons.indices.count { cell(frame, it) == cell(drawn, it) }
        assertEquals("no icon draws the same with the fallback off", 0, same)
    }

    private companion object {
        const val COLS = 40
        const val MIN_INK = 6
        const val MISSING = 0xF8FE
    }
}

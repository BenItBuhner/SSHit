package app.berth.android.ui.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.berth.android.R
import app.berth.domain.model.TerminalFont
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

/**
 * The font families (spec C20, Fonts): each bundled family loads as its own faces, not the system's
 * monospace under another name; the Nerd Font fallback puts the Powerline glyphs behind a family
 * that lacks them and nowhere else; a TTF or OTF import is filed under the family and face the
 * file's own name table gives, listed, drawn, and removable; what is not a font is turned down by
 * name, and a file that names itself but will not open is turned down with the face it would have
 * replaced left standing; and whether a family holds a column is measured once, at import, and
 * kept in its name file.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class TerminalFontsTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        TerminalFonts.importedDir(context).deleteRecursively()
        TypefaceCache.clear()
        TerminalPaintsCache.clear()
    }

    private fun resource(id: Int): ByteArray = context.resources.openRawResource(id).use { it.readBytes() }

    /** A rough fingerprint of `g` set in [typeface]: the ink of one glyph tells one face from another. */
    private fun ink(typeface: Typeface, text: String = "g"): List<Int> {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = 32f; color = Color.BLACK }
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawText(text, 4f, 36f, paint) }
        val out = ArrayList<Int>()
        for (y in 0 until 48) for (x in 0 until 48) out.add(bitmap.getPixel(x, y) and 0xFF)
        return out
    }

    // ---- what the file says about itself --------------------------------------------------------------

    @Test
    fun `the name table gives each bundled face its family and its face`() {
        fun parsed(id: Int) = SfntNames.parse(resource(id)) ?: error("no name table in $id")
        assertEquals("IBM Plex Mono" to FontFace.BOLD_ITALIC, parsed(R.font.ibm_plex_mono_bold_italic).let { it.family to it.face })
        assertEquals("IBM Plex Mono" to FontFace.REGULAR, parsed(R.font.ibm_plex_mono_regular).let { it.family to it.face })
        assertEquals("Fira Code" to FontFace.BOLD, parsed(R.font.fira_code_bold).let { it.family to it.face })
        assertEquals("Hack" to FontFace.REGULAR, parsed(R.font.hack_regular).let { it.family to it.face })
        assertEquals("IBM Plex Mono" to FontFace.ITALIC, parsed(R.font.ibm_plex_mono_italic).let { it.family to it.face })
        assertEquals("Source Code Pro" to FontFace.BOLD, parsed(R.font.source_code_pro_bold).let { it.family to it.face })
        assertEquals("JetBrains Mono" to FontFace.BOLD, parsed(R.font.jetbrains_mono_bold).let { it.family to it.face })
        assertFalse(parsed(R.font.hack_regular).cff)
    }

    @Test
    fun `what is not a single sfnt font is no font`() {
        assertNull(SfntNames.parse(ByteArray(0)))
        assertNull(SfntNames.parse("not a font, a note".toByteArray()))
        // A truncated font: the table directory runs past the end.
        assertNull(SfntNames.parse(resource(R.font.hack_regular).copyOf(40)))
        val collection = ByteBuffer.allocate(16).putInt(0x74746366).array()
        assertTrue(SfntNames.isCollection(collection))
        assertNull(SfntNames.parse(collection))
    }

    @Test
    fun `a folder name is made from the family name, one for every spelling of it`() {
        assertEquals("ibm-plex-mono", TerminalFonts.slug("IBM Plex Mono"))
        assertEquals("ibm-plex-mono", TerminalFonts.slug("  ibm  plex/mono!! "))
        assertEquals("font", TerminalFonts.slug("***"))
    }

    // ---- the faces on the canvas -----------------------------------------------------------------------

    @Test
    fun `every bundled family draws with its own face, and none is the system's monospace renamed`() {
        val inks = TerminalFonts.bundled.associate { fam -> fam.name to ink(TypefaceCache.forFamily(context, fam.name, nerdFallback = false)[0]) }
        val system = ink(TypefaceCache.forFamily(context, TerminalFonts.SYSTEM, nerdFallback = false)[0])
        assertEquals("five families, five different glyphs", inks.size, inks.values.toSet().size)
        for ((name, glyph) in inks) assertNotEquals("$name is not the system's monospace", system, glyph)
        // The bold face is the family's own where it ships one, so it is not the regular face thickened.
        val plex = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = false)
        assertNotEquals(ink(plex[0]), ink(plex[1]))
        assertNotEquals(ink(plex[0]), ink(plex[2]))
    }

    @Test
    fun `a family missing a face has it made from its regular one, so bold and italic still differ from it`() {
        val hack = TypefaceCache.forFamily(context, "Hack", nerdFallback = false)
        assertEquals(4, hack.size)
        assertNotEquals("Hack's italic is synthesised from its regular", ink(hack[0]), ink(hack[2]))
        assertTrue(hack[2].isItalic)
        assertTrue(hack[3].isBold && hack[3].isItalic)
    }

    @Test
    fun `the Nerd Font fallback puts the Powerline glyphs behind a family that lacks them`() {
        // IBM Plex Mono is the bundled family without the Powerline range (the other four carry it themselves).
        val paint = Paint()
        paint.typeface = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = false)[0]
        assertFalse("IBM Plex Mono has no Powerline arrow of its own", paint.hasGlyph("\uE0B0"))
        assertFalse(paint.hasGlyph("\uE0B6"))
        paint.typeface = TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = true)[0]
        assertTrue("the fallback supplies it", paint.hasGlyph("\uE0B0"))
        assertTrue("and the dividers", paint.hasGlyph("\uE0B1") && paint.hasGlyph("\uE0B2") && paint.hasGlyph("\uE0B3"))
        assertTrue("and the rest of the Powerline range", paint.hasGlyph("\uE0B6") && paint.hasGlyph("\uE0C0") && paint.hasGlyph("\uE0D4"))
        assertFalse("but not the icon sets, which an imported Nerd Font brings", paint.hasGlyph("\uE7A2"))
        // The letters stay the family's own: the symbols cover none of them.
        assertEquals(ink(TypefaceCache.forFamily(context, "IBM Plex Mono", nerdFallback = false)[0]), ink(paint.typeface))
        // The system's monospace gets the same glyphs in front of it, and keeps its own letters.
        paint.typeface = TypefaceCache.forFamily(context, TerminalFonts.SYSTEM, nerdFallback = true)[0]
        assertTrue(paint.hasGlyph("\uE0B0"))
        assertEquals(ink(Typeface.MONOSPACE), ink(paint.typeface))
    }

    @Test
    fun `paints draw the family the setting names, or the default when an import it named is gone`() {
        val plex = TerminalPaints(context, TerminalFont(family = "IBM Plex Mono", sizeSp = 16), density = 2f, fontScale = 1f)
        val jb = TerminalPaints(context, TerminalFont(sizeSp = 16), density = 2f, fontScale = 1f)
        assertNotEquals(ink(plex.regular.typeface), ink(jb.regular.typeface))
        val gone = TerminalPaints(context, TerminalFont(family = "A Font That Left", sizeSp = 16), density = 2f, fontScale = 1f)
        assertEquals(ink(jb.regular.typeface), ink(gone.regular.typeface))
        assertEquals(TerminalFonts.DEFAULT, TerminalFont(family = "A Font That Left").resolvedFamily(context))
    }

    // ---- import ----------------------------------------------------------------------------------------

    private fun fileUri(name: String, bytes: ByteArray): Uri {
        val f = File(context.cacheDir, name)
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    @Test
    fun `an imported face is filed under the family and face its name table gives, listed, drawn and removable`() = runBlocking {
        val before = TerminalFonts.version
        val result = TerminalFonts.import(context, fileUri("picked.ttf", resource(R.font.ibm_plex_mono_bold_italic)))
        assertEquals(TerminalFonts.ImportResult.Done("IBM Plex Mono", FontFace.BOLD_ITALIC), result)
        assertEquals(before + 1, TerminalFonts.version)

        val fam = TerminalFonts.imported(context).single()
        assertEquals("IBM Plex Mono", fam.name)
        assertEquals(setOf(FontFace.BOLD_ITALIC), fam.files.keys)
        assertEquals("bold_italic.ttf", fam.files.getValue(FontFace.BOLD_ITALIC).name)
        assertEquals("ibm-plex-mono", fam.dir.name)
        // A second face into the same family joins it; the same face again replaces, never doubles.
        assertEquals(TerminalFonts.ImportResult.Done("IBM Plex Mono", FontFace.REGULAR), TerminalFonts.import(context, fileUri("other.ttf", resource(R.font.ibm_plex_mono_regular))))
        assertEquals(TerminalFonts.ImportResult.Done("IBM Plex Mono", FontFace.REGULAR), TerminalFonts.import(context, fileUri("again.ttf", resource(R.font.ibm_plex_mono_regular))))
        val two = TerminalFonts.imported(context).single()
        assertEquals(2, two.faces)
        assertEquals(2, two.dir.listFiles { f -> f.extension == "ttf" }!!.size)

        // The picker lists it after the bundled families and the system's, with its provenance.
        val choice = TerminalFonts.choices(context).last()
        assertEquals(FontChoice.Kind.IMPORTED, choice.kind)
        assertTrue(choice.note.startsWith("Imported"))
        assertTrue(choice.note, choice.note.contains("2 faces"))
        assertTrue(choice.monospaced)

        // Bundled and imported share the name here; the bundled one wins the lookup, so give the import a name of its own to draw by.
        assertNotNull(TerminalFonts.imported(context, "IBM Plex Mono"))
        TerminalFonts.remove(context, two)
        assertTrue(TerminalFonts.imported(context).isEmpty())
        assertEquals(before + 4, TerminalFonts.version)
    }

    @Test
    fun `an imported family of its own name is what the paints draw with, and its loss falls back to the default`() = runBlocking {
        // Hack under its own name table, so the import is a family the bundle does not have by that spelling: rename the folder's name file to tell them apart.
        TerminalFonts.import(context, fileUri("hack.ttf", resource(R.font.hack_bold)))
        val fam = TerminalFonts.imported(context).single()
        File(fam.dir, "family.txt").writeText("Hack Imported")
        TypefaceCache.clear()
        val imported = TerminalFonts.imported(context).single()
        assertEquals("Hack Imported", imported.name)
        assertEquals("Hack Imported", TerminalFont(family = "Hack Imported").resolvedFamily(context))
        val faces = TypefaceCache.forFamily(context, "Hack Imported", nerdFallback = false)
        // One face, bold: it stands as the regular one and the others are made from it.
        assertEquals(ink(TypefaceCache.forFamily(context, "Hack", nerdFallback = false)[1]), ink(faces[0]))
        assertTrue(TerminalFonts.choices(context).last().note.contains("one face"))
        TerminalFonts.remove(context, imported)
        assertEquals(TerminalFonts.DEFAULT, TerminalFont(family = "Hack Imported").resolvedFamily(context))
    }

    @Test
    fun `what is not a font is turned down by name and leaves nothing behind`() = runBlocking {
        assertEquals(TerminalFonts.ImportResult.Failed("Not a TTF or OTF font file."), TerminalFonts.import(context, fileUri("note.txt", "hello".toByteArray())))
        val collection = ByteBuffer.allocate(64).putInt(0x74746366).array()
        assertEquals(TerminalFonts.ImportResult.Failed("That is a font collection; Berth takes one face per file."), TerminalFonts.import(context, fileUri("all.ttc", collection)))
        assertEquals(TerminalFonts.ImportResult.Failed("Couldn't read that file."), TerminalFonts.import(context, Uri.fromFile(File(context.cacheDir, "missing.ttf"))))
        assertTrue(TerminalFonts.imported(context).isEmpty())
        assertFalse(File(TerminalFonts.importedDir(context), "font").exists())
    }

    /**
     * [bytes] with the named tables struck from the directory (their tags overwritten, the data
     * left where it was): the name table still reads, so the file names its family, and FreeType
     * refuses a TrueType font with no horizontal header, so Android will not open it.
     */
    private fun withoutTables(bytes: ByteArray, vararg tags: String): ByteArray {
        val out = bytes.copyOf()
        val b = ByteBuffer.wrap(out)
        val numTables = b.getShort(4).toInt() and 0xFFFF
        var struck = 0
        for (i in 0 until numTables) {
            val rec = 12 + i * 16
            val tag = String(out, rec, 4, Charsets.ISO_8859_1)
            if (tag in tags) {
                b.putInt(rec, 0x7A7A7A30 + struck) // 'zzz0', 'zzz1', ...
                struck++
            }
        }
        assertEquals("every named table was there to strike", tags.size, struck)
        return out
    }

    @Test
    fun `a file that names a family but will not open is refused, and the face it would have replaced stands`() = runBlocking {
        assertEquals(TerminalFonts.ImportResult.Done("Hack", FontFace.REGULAR), TerminalFonts.import(context, fileUri("hack.ttf", resource(R.font.hack_regular))))
        val family = TerminalFonts.imported(context).single()
        val good = family.files.getValue(FontFace.REGULAR)
        val goodBytes = good.readBytes()
        val nameFile = File(family.dir, "family.txt").readLines()
        val version = TerminalFonts.version

        // The same face, its horizontal metrics struck: it still says Hack, Regular, so it gets as far
        // as the open check, and fails there, on its own copy, before the face it would replace is touched.
        val broken = withoutTables(resource(R.font.hack_regular), "hhea", "hmtx", "maxp")
        assertEquals("Hack" to FontFace.REGULAR, SfntNames.parse(broken)!!.let { it.family to it.face })
        assertEquals(TerminalFonts.ImportResult.Failed("Android couldn't open that font."), TerminalFonts.import(context, fileUri("broken.ttf", broken)))

        val after = TerminalFonts.imported(context).single()
        assertEquals(setOf(FontFace.REGULAR), after.files.keys)
        assertEquals(good, after.files.getValue(FontFace.REGULAR))
        assertTrue("the good face is byte for byte what it was", goodBytes.contentEquals(good.readBytes()))
        assertEquals("the name file too", nameFile, File(family.dir, "family.txt").readLines())
        assertTrue("no copy of the bad file is left behind", family.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals("nothing changed, so nothing was told to redraw", version, TerminalFonts.version)
        // The face still draws as Hack's own.
        assertEquals(ink(TypefaceCache.forFamily(context, "Hack", nerdFallback = false)[0]), ink(TypefaceCache.forFamily(context, after.name, nerdFallback = false)[0]))
    }

    @Test
    fun `whether a family holds a column is measured at import and kept on the name file's second line`() = runBlocking {
        // A proportional face, the interface's own: the name file says so, and the picker's note follows without opening the font again.
        assertEquals(TerminalFonts.ImportResult.Done("IBM Plex Sans", FontFace.REGULAR), TerminalFonts.import(context, fileUri("sans.ttf", resource(R.font.ibm_plex_sans_regular))))
        val sans = TerminalFonts.imported(context).single()
        assertFalse(sans.monospaced)
        assertEquals(listOf("IBM Plex Sans", "proportional"), File(sans.dir, "family.txt").readLines())
        assertFalse(TerminalFonts.choices(context).last().monospaced)
        assertTrue(TerminalFonts.choices(context).last().note, TerminalFonts.choices(context).last().note.contains("not monospaced"))

        // A monospaced one says so too.
        TerminalFonts.import(context, fileUri("mono.ttf", resource(R.font.ibm_plex_mono_regular)))
        val mono = TerminalFonts.imported(context).first { it.name == "IBM Plex Mono" }
        assertTrue(mono.monospaced)
        assertEquals(listOf("IBM Plex Mono", "monospaced"), File(mono.dir, "family.txt").readLines())

        // A second face joining a family re-measures its regular face, not the newcomer: the sans family stays proportional.
        assertEquals(TerminalFonts.ImportResult.Done("IBM Plex Sans", FontFace.BOLD), TerminalFonts.import(context, fileUri("sans-bold.ttf", resource(R.font.ibm_plex_sans_semibold))))
        assertEquals(listOf("IBM Plex Sans", "proportional"), File(sans.dir, "family.txt").readLines())

        // A name file from before the second line was kept: the face is measured here instead, and the family is listed all the same.
        File(mono.dir, "family.txt").writeText("Plex Mono Imported")
        val legacy = TerminalFonts.imported(context).first { it.name == "Plex Mono Imported" }
        assertTrue(legacy.monospaced)
        File(sans.dir, "family.txt").writeText("Plex Sans Imported\n")
        assertFalse(TerminalFonts.imported(context).first { it.name == "Plex Sans Imported" }.monospaced)
    }

    @Test
    fun `a Nerd Font the user imported is the fallback's source over the bundled subset`() = runBlocking {
        assertNull(TerminalFonts.nerdFallback(context))
        // Any face will do for the test: what makes it a Nerd Font here is the family name the file carries, which this one is given.
        TerminalFonts.import(context, fileUri("nerd.ttf", resource(R.font.source_code_pro_regular)))
        val fam = TerminalFonts.imported(context).single()
        File(fam.dir, "family.txt").writeText("Symbols Nerd Font Mono")
        assertEquals("Symbols Nerd Font Mono", TerminalFonts.nerdFallback(context)?.name)
        assertTrue(TerminalFonts.imported(context).single().isNerdFont)
    }
}

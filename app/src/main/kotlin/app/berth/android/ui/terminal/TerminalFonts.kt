package app.berth.android.ui.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.berth.android.R
import app.berth.domain.model.TerminalFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** The four faces a terminal draws with; a family missing one has it made from the regular face. */
enum class FontFace(val fileStem: String) { REGULAR("regular"), BOLD("bold"), ITALIC("italic"), BOLD_ITALIC("bold_italic") }

/** One family the font picker offers (spec C20, Fonts): what it is called and where it comes from. */
data class FontChoice(val name: String, val kind: Kind, val faces: Int, val licence: String? = null, val monospaced: Boolean = true) {
    enum class Kind { BUNDLED, SYSTEM, IMPORTED }

    /**
     * The caption under the name: its provenance, its faces when not all four, a warning for a
     * proportional face. Two lines at most beside the sample at 1× and at the interface's font cap,
     * so the longest (an import of one proportional face) is kept short; that imports stay on the
     * phone is the sheet's footer's to say once, not every row's.
     */
    val note: String
        get() {
            val parts = ArrayList<String>()
            parts += when (kind) {
                Kind.BUNDLED -> "Bundled" + (licence?.let { " \u00B7 $it" } ?: "")
                Kind.SYSTEM -> "The device's monospace font"
                Kind.IMPORTED -> "Imported"
            }
            if (kind != Kind.SYSTEM && faces < 4) parts += if (faces == 1) "one face, the rest made from it" else "$faces faces, the rest made from them"
            if (!monospaced) parts += "not monospaced, columns will drift"
            return parts.joinToString(" \u00B7 ")
        }
}

/** A family shipped in the APK: its faces as font resources, null where the family has none of its own. */
class BundledFamily(val name: String, val regular: Int, val bold: Int?, val italic: Int?, val boldItalic: Int?, val licence: String) {
    val faces: Int get() = 1 + listOf(bold, italic, boldItalic).count { it != null }
    fun res(face: FontFace): Int? = when (face) {
        FontFace.REGULAR -> regular
        FontFace.BOLD -> bold
        FontFace.ITALIC -> italic
        FontFace.BOLD_ITALIC -> boldItalic
    }
}

/**
 * A family the user imported: its display name, the face files under its folder in the app's
 * files, and whether its regular face holds a column ([monospaced], measured once at import and
 * kept in the name file, so listing the families opens no font).
 */
class ImportedFamily(val name: String, val dir: File, val files: Map<FontFace, File>, val monospaced: Boolean = true) {
    val faces: Int get() = files.size

    /** The regular face, or the first face there is when the user imported only a bold or an italic. */
    val primary: File? get() = files[FontFace.REGULAR] ?: files.values.firstOrNull()

    /** A patched Nerd Font, or the symbols font itself, either of which can stand behind another family's glyphs. */
    val isNerdFont: Boolean get() = name.contains("nerd", ignoreCase = true)
}

/**
 * The terminal's font families (spec C20, Fonts): the five bundled ones, the device's monospace,
 * and the TTF or OTF files the user imports, kept under the app's files and grouped by the family
 * name in the file. Bundling stops at what a family costs, under 300 KB compressed each: IBM Plex
 * Mono ships all four faces at 236 KB, Fira Code its two, and Hack and Source Code Pro their regular
 * and bold (their italics would add 160 to 300 KB a family), with the missing faces made from the
 * regular one, as they are for a System font. The one exception to that budget is the Nerd Font
 * fallback's own face: all of Symbols Nerd Font Mono v3.5.1 ([R.font.symbols_nerd_font_mono], 1.6 MB
 * compressed), since a prompt's icons come from every set it collects (Powerline, Font Awesome,
 * Devicons, Octicons, Seti, Codicons, Material Design, the distribution logos) and a subset is
 * tofu for whichever prompt it did not foresee. The fallback draws the glyphs a family lacks from a
 * Nerd Font the user has imported first, then from the bundled symbols, so an import made for a
 * prompt still wins where it has the glyph and the bundle covers the rest.
 */
object TerminalFonts {
    const val SYSTEM = "System monospace"
    const val DEFAULT = "JetBrains Mono"

    /** The sample line the picker sets each family in (spec C20): the glyphs that tell coding fonts apart. */
    const val SAMPLE = "0O il1 {} => -> |~"

    /**
     * The OpenType feature string for the Ligatures switch (spec C19), the canvas's paints' and the
     * picker's sample line's alike: `liga` and `calt` said on or off outright, so no style around the
     * text speaks for it (the interface's mono role keeps its own ligatures off, and a sample laid
     * over it with nothing said would follow that, not the switch).
     */
    fun featureSettings(ligatures: Boolean): String = if (ligatures) "liga, calt" else "-liga, -calt"

    val bundled: List<BundledFamily> = listOf(
        BundledFamily(DEFAULT, R.font.jetbrains_mono_regular, R.font.jetbrains_mono_bold, R.font.jetbrains_mono_italic, R.font.jetbrains_mono_bold_italic, "OFL"),
        BundledFamily("IBM Plex Mono", R.font.ibm_plex_mono_regular, R.font.ibm_plex_mono_bold, R.font.ibm_plex_mono_italic, R.font.ibm_plex_mono_bold_italic, "OFL"),
        BundledFamily("Fira Code", R.font.fira_code_regular, R.font.fira_code_bold, null, null, "OFL"),
        BundledFamily("Hack", R.font.hack_regular, R.font.hack_bold, null, null, "MIT"),
        BundledFamily("Source Code Pro", R.font.source_code_pro_regular, R.font.source_code_pro_bold, null, null, "OFL"),
    )

    /** Bumps when an import lands or a family is removed; paints and typefaces keyed on a family re-read it. */
    var version: Int by mutableIntStateOf(0)
        private set

    fun bundled(name: String): BundledFamily? = bundled.firstOrNull { it.name == name }

    /**
     * Opens [font]'s faces into [TypefaceCache] and measures once with each, off the UI thread, so
     * the first terminal drawn after a launch builds its paints from faces already read rather
     * than reading them inside a frame.
     */
    fun warm(context: Context, font: TerminalFont): List<Typeface> {
        val faces = TypefaceCache.forFamily(context, font.resolvedFamily(context), font.nerdFontFallback)
        val paint = Paint()
        faces.forEach {
            paint.typeface = it
            paint.measureText("M")
        }
        return faces
    }

    fun importedDir(context: Context): File = File(context.filesDir, "fonts")

    /**
     * The imported families, by name; read from disk each time, which is a directory listing and a
     * name file per family: the family's name on its first line, `monospaced` or `proportional`
     * on its second. A name file from before the second line was kept has the face measured here, once.
     */
    fun imported(context: Context): List<ImportedFamily> {
        val root = importedDir(context)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val lines = runCatching { File(dir, NAME_FILE).readText(StandardCharsets.UTF_8).lines() }.getOrNull() ?: return@mapNotNull null
            val name = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val files = HashMap<FontFace, File>()
            for (face in FontFace.entries) {
                dir.listFiles { f -> f.isFile && f.nameWithoutExtension == face.fileStem }?.firstOrNull()?.let { files[face] = it }
            }
            if (files.isEmpty()) return@mapNotNull null
            val primary = files[FontFace.REGULAR] ?: files.values.first()
            val monospaced = when (lines.getOrNull(1)?.trim()) {
                MONOSPACED -> true
                PROPORTIONAL -> false
                else -> isMonospaced(primary)
            }
            ImportedFamily(name, dir, files, monospaced)
        }.sortedBy { it.name.lowercase() }
    }

    fun imported(context: Context, name: String): ImportedFamily? = imported(context).firstOrNull { it.name == name }

    /** Every family the picker lists, in its order: bundled, the system's, then imports by name. */
    fun choices(context: Context): List<FontChoice> =
        bundled.map { FontChoice(it.name, FontChoice.Kind.BUNDLED, it.faces, it.licence) } +
            FontChoice(SYSTEM, FontChoice.Kind.SYSTEM, 4) +
            imported(context).map { fam -> FontChoice(fam.name, FontChoice.Kind.IMPORTED, fam.faces, monospaced = fam.monospaced) }

    /**
     * The imported family the Nerd Font fallback draws from: the symbols font itself when it is
     * there, else the first patched Nerd Font by name; null with none, when the bundled symbols stand alone.
     */
    fun nerdFallback(context: Context): ImportedFamily? {
        val nerd = imported(context).filter { it.isNerdFont && it.primary != null }
        return nerd.firstOrNull { it.name.contains("symbols", ignoreCase = true) } ?: nerd.firstOrNull()
    }

    sealed interface ImportResult {
        /** The face went into [family]; a family that had the face already has the new file in its place. */
        data class Done(val family: String, val face: FontFace) : ImportResult
        data class Failed(val reason: String) : ImportResult
    }

    /**
     * Reads the picked file, checks it is a TrueType or OpenType font, takes the family and the
     * face from its name table and copies it under the family's folder. Nothing is trusted about
     * the file beyond the bytes: the family name is what the font says, cut to a line; the folder
     * name is made from it, never from the picked path.
     */
    suspend fun import(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> readCapped(input, MAX_FONT_BYTES) }
        }.getOrNull() ?: return@withContext ImportResult.Failed("Couldn't read that file.")
        if (bytes == null) return@withContext ImportResult.Failed("That file is too large for a font (over ${MAX_FONT_BYTES / 1_000_000} MB).")
        val names = SfntNames.parse(bytes) ?: return@withContext ImportResult.Failed(
            if (SfntNames.isCollection(bytes)) "That is a font collection; Berth takes one face per file." else "Not a TTF or OTF font file.",
        )
        val family = names.family.lineSequence().first().trim().take(MAX_NAME_LENGTH).ifEmpty { "Imported font" }
        val face = names.face
        val dir = File(importedDir(context), slug(family)).also { it.mkdirs() }
        if (!dir.isDirectory) return@withContext ImportResult.Failed("Couldn't make room for the font.")
        val ext = if (names.cff) "otf" else "ttf"
        val target = File(dir, "${face.fileStem}.$ext")
        // Named so it is never a face to [imported] and never what the delete below matches.
        val tmp = File(dir, "import-${face.fileStem}.tmp")
        val ok = runCatching {
            tmp.writeBytes(bytes)
            // A font Android cannot open is no font to us, proven on the copy before anything the
            // family already had is touched, so a bad file over a good face leaves the good face.
            Font.Builder(tmp).build()
            // The face already in the family goes, whatever its extension, so the folder never holds two of one face.
            dir.listFiles { f -> f.isFile && f.nameWithoutExtension == face.fileStem }?.forEach { it.delete() }
            if (!tmp.renameTo(target)) error("rename")
            val primary = dir.listFiles { f -> f.isFile && f.nameWithoutExtension == FontFace.REGULAR.fileStem }?.firstOrNull() ?: target
            File(dir, NAME_FILE).writeText("$family\n${if (isMonospaced(primary)) MONOSPACED else PROPORTIONAL}", StandardCharsets.UTF_8)
        }.isSuccess
        if (!ok) {
            tmp.delete()
            if (dir.listFiles()?.none { it.isFile && it.name != NAME_FILE } == true) dir.deleteRecursively()
            return@withContext ImportResult.Failed("Android couldn't open that font.")
        }
        invalidate()
        ImportResult.Done(family, face)
    }

    /** Deletes an imported family's folder; a font setting naming it falls back to the default family. */
    suspend fun remove(context: Context, family: ImportedFamily) {
        withContext(Dispatchers.IO) { family.dir.deleteRecursively() }
        invalidate()
    }

    /** Whether the font draws `i` and `M` at one width, measured once at import for the picker's note. */
    fun isMonospaced(file: File): Boolean = runCatching {
        val paint = Paint().apply { typeface = Typeface.createFromFile(file); textSize = 100f }
        val narrow = paint.measureText("i")
        val wide = paint.measureText("M")
        narrow > 0f && Math.abs(narrow - wide) < 0.5f
    }.getOrDefault(true)

    private fun invalidate() {
        TypefaceCache.clear()
        TerminalPaintsCache.clear()
        version++
    }

    /** A folder name from a family name: letters and digits, the rest dashes, so two spellings of a name share one folder. */
    internal fun slug(family: String): String {
        val s = family.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').replace(Regex("-+"), "-")
        return s.ifEmpty { "font" }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private const val NAME_FILE = "family.txt"
    private const val MONOSPACED = "monospaced"
    private const val PROPORTIONAL = "proportional"
    private const val MAX_FONT_BYTES = 40 * 1_000_000
    private const val MAX_NAME_LENGTH = 60
}

/**
 * The family and face named inside a TrueType or OpenType file: the `name` table's typographic
 * family (16) over the legacy one (1), the subfamily (17 over 2), and bold and italic from the
 * `head` and `OS/2` flags with the subfamily's words as a second opinion. Enough of the format to
 * file a font under the name its designer gave it, and no more.
 */
internal class SfntNames(val family: String, val subfamily: String, val bold: Boolean, val italic: Boolean, val cff: Boolean) {
    val face: FontFace
        get() = when {
            bold && italic -> FontFace.BOLD_ITALIC
            bold -> FontFace.BOLD
            italic -> FontFace.ITALIC
            else -> FontFace.REGULAR
        }

    companion object {
        private const val TAG_TRUETYPE = 0x00010000
        private const val TAG_TRUE = 0x74727565 // 'true'
        private const val TAG_OTTO = 0x4F54544F // 'OTTO'
        private const val TAG_TTCF = 0x74746366 // 'ttcf'

        fun isCollection(bytes: ByteArray): Boolean = bytes.size >= 4 && ByteBuffer.wrap(bytes).getInt(0) == TAG_TTCF

        /** Null for anything that is not a single sfnt font with a readable `name` table. */
        fun parse(bytes: ByteArray): SfntNames? {
            if (bytes.size < 12) return null
            val b = ByteBuffer.wrap(bytes)
            val tag = b.getInt(0)
            if (tag != TAG_TRUETYPE && tag != TAG_TRUE && tag != TAG_OTTO) return null
            val numTables = b.getShort(4).toInt() and 0xFFFF
            if (numTables == 0 || numTables > 512 || 12 + numTables * 16 > bytes.size) return null
            var nameTable: Pair<Int, Int>? = null
            var head: Int? = null
            var os2: Int? = null
            for (i in 0 until numTables) {
                val rec = 12 + i * 16
                val t = b.getInt(rec)
                val off = b.getInt(rec + 8)
                val len = b.getInt(rec + 12)
                if (off < 0 || len < 0 || off.toLong() + len > bytes.size) continue
                when (t) {
                    0x6E616D65 -> nameTable = off to len // 'name'
                    0x68656164 -> head = off // 'head'
                    0x4F532F32 -> os2 = off // 'OS/2'
                }
            }
            val (nOff, nLen) = nameTable ?: return null
            if (nLen < 6) return null
            val count = b.getShort(nOff + 2).toInt() and 0xFFFF
            val stringOffset = b.getShort(nOff + 4).toInt() and 0xFFFF
            // The best string for each id: Windows English first, then any Windows or Unicode, then Mac Roman.
            val best = HashMap<Int, Pair<Int, String>>()
            for (i in 0 until count) {
                val rec = nOff + 6 + i * 12
                if (rec + 12 > nOff + nLen) break
                val platform = b.getShort(rec).toInt() and 0xFFFF
                val encoding = b.getShort(rec + 2).toInt() and 0xFFFF
                val language = b.getShort(rec + 4).toInt() and 0xFFFF
                val id = b.getShort(rec + 6).toInt() and 0xFFFF
                val len = b.getShort(rec + 8).toInt() and 0xFFFF
                val off = b.getShort(rec + 10).toInt() and 0xFFFF
                if (id != 1 && id != 2 && id != 16 && id != 17) continue
                val start = nOff + stringOffset + off
                if (start + len > bytes.size || start + len > nOff + nLen) continue
                val text = when (platform) {
                    3, 0 -> String(bytes, start, len, StandardCharsets.UTF_16BE)
                    1 -> if (encoding == 0) String(bytes, start, len, StandardCharsets.ISO_8859_1) else continue
                    else -> continue
                }.trim()
                if (text.isEmpty()) continue
                val rank = when {
                    platform == 3 && language == 0x409 -> 3
                    platform == 3 || platform == 0 -> 2
                    else -> 1
                }
                val have = best[id]
                if (have == null || rank > have.first) best[id] = rank to text
            }
            val family = best[16]?.second ?: best[1]?.second ?: return null
            val subfamily = best[17]?.second ?: best[2]?.second ?: "Regular"
            var bold = false
            var italic = false
            head?.let { h -> if (h + 46 <= bytes.size) { val mac = b.getShort(h + 44).toInt(); bold = mac and 1 != 0; italic = mac and 2 != 0 } }
            os2?.let { o -> if (o + 64 <= bytes.size) { val sel = b.getShort(o + 62).toInt(); if (sel and 0x20 != 0) bold = true; if (sel and 1 != 0) italic = true } }
            val words = subfamily.lowercase()
            if (words.contains("bold")) bold = true
            if (words.contains("italic") || words.contains("oblique")) italic = true
            return SfntNames(family, subfamily, bold, italic, cff = tag == TAG_OTTO)
        }
    }
}

/**
 * The regular, bold, italic and bold-italic [Typeface]s of each family, built once per process
 * and per fallback setting. Each face is its own font with, when the Nerd Font fallback is on, an
 * imported Nerd Font and the bundled symbols behind it and the system's monospace behind those, so
 * a glyph the family lacks is looked for in that order. A face the family does not have is made from its regular one, the way
 * the System font's always were.
 */
internal object TypefaceCache {
    private data class Key(val family: String, val nerd: Boolean)

    private val faces = HashMap<Key, List<Typeface>>()

    @Synchronized
    fun forFamily(context: Context, family: String, nerdFallback: Boolean): List<Typeface> =
        faces.getOrPut(Key(family, nerdFallback)) { load(context.applicationContext, family, nerdFallback) }

    @Synchronized
    fun clear() {
        faces.clear()
    }

    private fun load(context: Context, family: String, nerdFallback: Boolean): List<Typeface> {
        val fallback: List<FontFamily> = if (nerdFallback) nerdFamilies(context) else emptyList()
        // The fonts of each face the family has: a resource for a bundled one, a file for an import.
        val fonts: Map<FontFace, Font> = when {
            family == TerminalFonts.SYSTEM -> emptyMap()
            TerminalFonts.bundled(family) != null -> {
                val b = TerminalFonts.bundled(family)!!
                FontFace.entries.mapNotNull { face -> b.res(face)?.let { id -> runCatching { Font.Builder(context.resources, id).build() }.getOrNull()?.let { face to it } } }.toMap()
            }
            else -> TerminalFonts.imported(context, family)?.files?.mapNotNull { (face, file) -> runCatching { Font.Builder(file).build() }.getOrNull()?.let { face to it } }?.toMap() ?: emptyMap()
        }
        val regular: Typeface = when {
            fonts[FontFace.REGULAR] != null -> build(fonts.getValue(FontFace.REGULAR), fallback)
            fonts.isNotEmpty() -> build(fonts.values.first(), fallback)
            // The system's monospace, with the symbols in front of it when asked: they cover no letter, so every letter still comes from it.
            fallback.isNotEmpty() -> Typeface.CustomFallbackBuilder(fallback.first()).also { b -> fallback.drop(1).forEach(b::addCustomFallback) }.setSystemFallback("monospace").build()
            else -> Typeface.MONOSPACE
        }
        fun face(face: FontFace, style: Int): Typeface = fonts[face]?.let { build(it, fallback) } ?: Typeface.create(regular, style)
        return listOf(
            regular,
            face(FontFace.BOLD, Typeface.BOLD),
            face(FontFace.ITALIC, Typeface.ITALIC),
            face(FontFace.BOLD_ITALIC, Typeface.BOLD_ITALIC),
        )
    }

    private fun build(font: Font, fallback: List<FontFamily>): Typeface {
        val builder = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
        fallback.forEach(builder::addCustomFallback)
        return builder.setSystemFallback("monospace").build()
    }

    // Built once per process and shared by every family's typefaces: the face is 2.6 MB unpacked, and [clear] is about imports, not it.
    private var bundledSymbols: FontFamily? = null

    /** The symbols families the fallback draws from, in order: an imported Nerd Font's primary face, then the bundled Symbols Nerd Font Mono. */
    private fun nerdFamilies(context: Context): List<FontFamily> {
        val imported = TerminalFonts.nerdFallback(context)?.primary?.let { file -> runCatching { Font.Builder(file).build() }.getOrNull() }?.let { FontFamily.Builder(it).build() }
        val bundled = bundledSymbols ?: runCatching { FontFamily.Builder(Font.Builder(context.resources, R.font.symbols_nerd_font_mono).build()).build() }.getOrNull()?.also { bundledSymbols = it }
        return listOfNotNull(imported, bundled)
    }
}

/** The family a [TerminalFont] names, or the default when an import it named is gone. */
fun TerminalFont.resolvedFamily(context: Context): String = when {
    family == TerminalFonts.SYSTEM || TerminalFonts.bundled(family) != null -> family
    TerminalFonts.imported(context, family) != null -> family
    else -> TerminalFonts.DEFAULT
}

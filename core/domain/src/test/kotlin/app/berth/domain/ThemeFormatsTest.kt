package app.berth.domain

import app.berth.domain.model.ColorMath
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.TerminalThemes
import app.berth.domain.model.ThemeFormat
import app.berth.domain.model.ThemeSlot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Import and export of the theme files spec A10 names, on real files as their projects publish them
 * (`src/test/resources/themes/SOURCES.txt`). The iTerm2 rows' expectations were read off the same
 * files with Python's plistlib, not with the parser under test.
 */
class ThemeFormatsTest {
    /** What one sample file must read as; a null [selection] is the quarter mix a format without one gets. */
    private data class Sample(
        val path: String,
        val format: ThemeFormat,
        val name: String,
        val background: Int,
        val foreground: Int,
        val cursor: Int,
        val cursorText: Int,
        val selection: Int?,
        val ansi: Map<Int, Int>,
        val bold: Int? = null,
        val links: Int? = null,
    )

    private val samples = listOf(
        Sample(
            "iterm2/Dracula.itermcolors", ThemeFormat.ITERM2, "Dracula", 0x282A36, 0xF8F8F2, 0xF8F8F2, 0x282A36, 0x44475A,
            mapOf(0 to 0x21222C, 1 to 0xFF5555, 4 to 0xBD93F9, 8 to 0x6272A4, 15 to 0xFFFFFF), bold = 0xF8F8F2,
        ),
        Sample(
            "iterm2/Catppuccin Latte.itermcolors", ThemeFormat.ITERM2, "Catppuccin Latte", 0xEFF1F5, 0x4C4F69, 0xDC8A78, 0xEFF1F5, 0xDC8A78,
            mapOf(0 to 0xBCC0CC, 1 to 0xD20F39, 4 to 0x1E66F5, 8 to 0xACB0BE, 15 to 0x6C6F85), bold = 0x4C4F69,
        ),
        Sample(
            "iterm2/Gruvbox Dark.itermcolors", ThemeFormat.ITERM2, "Gruvbox Dark", 0x282828, 0xEBDBB2, 0xEBDBB2, 0x282828, 0x665C54,
            mapOf(0 to 0x282828, 1 to 0xCC241D, 4 to 0x458588, 8 to 0x928374, 15 to 0xEBDBB2), bold = 0xEBDBB2, links = 0xD65D0E,
        ),
        Sample(
            "iterm2/One Dark.itermcolors", ThemeFormat.ITERM2, "One Dark", 0x282C34, 0xABB2BF, 0xABB2BF, 0x282C34, 0xABB2BF,
            mapOf(0 to 0x2C323C, 1 to 0xE06C75, 4 to 0x61AFEF, 8 to 0x3E4452, 15 to 0xABB2BF), bold = 0xABB2BF,
        ),
        Sample(
            "ghostty/dracula", ThemeFormat.GHOSTTY, "Dracula", 0x282A36, 0xF8F8F2, 0xF8F8F2, 0x282A36, 0x44475A,
            mapOf(0 to 0x21222C, 1 to 0xFF5555, 4 to 0xBD93F9, 8 to 0x6272A4, 15 to 0xFFFFFF),
        ),
        Sample(
            "ghostty/rose-pine", ThemeFormat.GHOSTTY, "Rose Pine", 0x191724, 0xE0DEF4, 0xE0DEF4, 0x191724, 0x403D52,
            mapOf(0 to 0x26233A, 1 to 0xEB6F92, 4 to 0x9CCFD8, 8 to 0x6E6A86, 15 to 0xE0DEF4),
        ),
        Sample(
            // No cursor-text line: the cursor's text is the background.
            "ghostty/tokyonight_night", ThemeFormat.GHOSTTY, "Tokyonight Night", 0x1A1B26, 0xC0CAF5, 0xC0CAF5, 0x1A1B26, 0x283457,
            mapOf(0 to 0x15161E, 1 to 0xF7768E, 4 to 0x7AA2F7, 8 to 0x414868, 9 to 0xFF899D, 15 to 0xC0CAF5),
        ),
        Sample(
            // Background and foreground are written without the `#`.
            "ghostty/kanagawa-wave", ThemeFormat.GHOSTTY, "Kanagawa Wave", 0x1F1F28, 0xDCD7BA, 0xC8C093, 0x1F1F28, 0x2D4F67,
            mapOf(0 to 0x16161D, 1 to 0xC34043, 4 to 0x7E9CD8, 8 to 0x727169, 15 to 0xDCD7BA),
        ),
        Sample(
            "ghostty/catppuccin-latte.conf", ThemeFormat.GHOSTTY, "Catppuccin Latte", 0xEFF1F5, 0x4C4F69, 0xDC8A78, 0xEFF1F5, 0xD8DAE1,
            mapOf(0 to 0x5C5F77, 1 to 0xD20F39, 4 to 0x1E66F5, 8 to 0x6C6F85, 15 to 0xBCC0CC),
        ),
        Sample(
            // The file's own name wins over the file name; trailing comments on every line.
            "base16/dracula.yaml", ThemeFormat.BASE16, "Dracula", 0x282A36, 0xF8F8F2, 0xF8F8F2, 0x282A36, 0x44475A,
            mapOf(0 to 0x282A36, 1 to 0xFF5555, 2 to 0x50FA7B, 4 to 0xBD93F9, 7 to 0xF8F8F2, 8 to 0x6272A4, 15 to 0xFFFFFF),
            links = 0xBD93F9,
        ),
        Sample(
            "base16/catppuccin-latte.yaml", ThemeFormat.BASE16, "Catppuccin Latte", 0xEFF1F5, 0x4C4F69, 0x4C4F69, 0xEFF1F5, 0xCCD0DA,
            mapOf(0 to 0xEFF1F5, 1 to 0xD20F39, 4 to 0x1E66F5, 8 to 0xBCC0CC, 15 to 0x7287FD),
            links = 0x1E66F5,
        ),
        Sample(
            "base16/gruvbox-dark-hard.yaml", ThemeFormat.BASE16, "Gruvbox dark, hard", 0x1D2021, 0xD5C4A1, 0xD5C4A1, 0x1D2021, 0x504945,
            mapOf(0 to 0x1D2021, 1 to 0xFB4934, 4 to 0x83A598, 8 to 0x665C54, 15 to 0xFBF1C7),
            links = 0x83A598,
        ),
        Sample(
            // The original flat layout: `scheme:` for the name, bare quoted hex.
            "base16/default-dark.yaml", ThemeFormat.BASE16, "Default Dark", 0x181818, 0xD8D8D8, 0xD8D8D8, 0x181818, 0x383838,
            mapOf(0 to 0x181818, 1 to 0xAB4642, 4 to 0x7CAFC2, 8 to 0x585858, 15 to 0xF8F8F8),
            links = 0x7CAFC2,
        ),
        Sample(
            "termux/dracula.properties", ThemeFormat.TERMUX, "Dracula", 0x282A36, 0xF8F8F2, 0xF8F8F2, 0x282A36, null,
            mapOf(0 to 0x000000, 1 to 0xFF5555, 4 to 0xBD93F9, 8 to 0x4D4D4D, 15 to 0xE6E6E6),
        ),
        Sample(
            // `key: value` pairs and `!` comments, and no cursor line: the cursor is the text colour.
            "termux/gruvbox-light.properties", ThemeFormat.TERMUX, "Gruvbox Light", 0xF9F5D7, 0x3C3836, 0x3C3836, 0xF9F5D7, null,
            mapOf(0 to 0xFDF4C1, 1 to 0xCC241D, 4 to 0x458588, 8 to 0x928374, 15 to 0x3C3836),
        ),
        Sample(
            // color9 comes before color8 in the file.
            "termux/solarized-dark.properties", ThemeFormat.TERMUX, "Solarized Dark", 0x002B36, 0x839496, 0x93A1A1, 0x002B36, null,
            mapOf(0 to 0x073642, 1 to 0xDC322F, 4 to 0x268BD2, 8 to 0x002B36, 9 to 0xCB4B16, 15 to 0xFDF6E3),
        ),
        Sample(
            // color16 and color17 (base16's extras) are not ANSI slots and are passed over.
            "termux/catppuccin-mocha.properties", ThemeFormat.TERMUX, "Catppuccin Mocha", 0x1E1E2E, 0xCDD6F4, 0xF5E0DC, 0x1E1E2E, null,
            mapOf(0 to 0x45475A, 1 to 0xF38BA8, 4 to 0x89B4FA, 8 to 0x585B70, 15 to 0xA6ADC8),
        ),
        Sample(
            "termux/base16-default-dark.properties", ThemeFormat.TERMUX, "Base16 Default Dark", 0x181818, 0xD8D8D8, 0xD8D8D8, 0x181818, null,
            mapOf(0 to 0x181818, 1 to 0xAB4642, 4 to 0x7CAFC2, 8 to 0x585858, 15 to 0xF8F8F8),
        ),
    )

    private fun text(path: String): String = assertNotNull(javaClass.getResource("/themes/$path"), path).readText()

    private fun read(s: Sample): TerminalTheme =
        assertNotNull(TerminalThemes.import(text(s.path), name = TerminalThemes.nameFromFile(s.path)), "${s.path} reads")

    private fun hex(rgb: Int?) = rgb?.let { "#%06x".format(it) }

    @Test
    fun `every sample file is recognised as its format`() {
        for (s in samples) assertEquals(s.format, TerminalThemes.detect(text(s.path)), s.path)
    }

    @Test
    fun `every sample file reads to its own colours`() {
        for (s in samples) {
            val t = read(s)
            assertEquals(s.name, t.name, "${s.path} name")
            assertEquals(TerminalThemes.slug(s.name), t.id, "${s.path} id")
            assertEquals(false, t.builtIn, s.path)
            assertEquals(hex(s.background), hex(t.background), "${s.path} background")
            assertEquals(hex(s.foreground), hex(t.foreground), "${s.path} foreground")
            assertEquals(hex(s.cursor), hex(t.cursor), "${s.path} cursor")
            assertEquals(hex(s.cursorText), hex(t.cursorText), "${s.path} cursor text")
            assertEquals(hex(s.selection ?: ColorMath.mix(s.background, s.foreground, 0.25f)), hex(t.selection), "${s.path} selection")
            assertEquals(hex(s.bold), hex(t.bold), "${s.path} bold")
            assertEquals(hex(s.links ?: t.ansi[4]), hex(t.links), "${s.path} links")
            for ((i, rgb) in s.ansi) assertEquals(hex(rgb), hex(t.ansi[i]), "${s.path} ansi $i")
        }
    }

    @Test
    fun `a file read in exports back to its own format with nothing lost`() {
        for (s in samples.filter { it.format.exports }) {
            val first = read(s)
            assertEquals(emptyList(), TerminalThemes.dropped(first, s.format), "${s.path} drops nothing")
            val again = assertNotNull(TerminalThemes.import(TerminalThemes.export(first, s.format), name = first.name), "${s.path} re-reads")
            for (slot in TerminalThemes.slots) assertEquals(hex(first.color(slot)), hex(again.color(slot)), "${s.path} ${slot.title}")
        }
    }

    @Test
    fun `Berth JSON and iTerm2 carry every stock theme whole`() {
        for (t in TerminalTheme.builtIns) {
            assertEquals(emptyList(), TerminalThemes.dropped(t, ThemeFormat.BERTH), t.id)
            assertEquals(emptyList(), TerminalThemes.dropped(t, ThemeFormat.ITERM2), t.id)
            val back = assertNotNull(TerminalThemes.import(TerminalThemes.export(t, ThemeFormat.ITERM2), name = t.name))
            assertEquals(t.copy(id = back.id, builtIn = false, suggestedAccent = null), back, t.id)
        }
    }

    @Test
    fun `a format drops only the colours it has no place for, and only when they differ from what an import derives`() {
        val lacks = mapOf(
            ThemeFormat.GHOSTTY to setOf(ThemeSlot.Links),
            ThemeFormat.WINDOWS_TERMINAL to setOf(ThemeSlot.CursorText, ThemeSlot.Bold, ThemeSlot.Links),
            ThemeFormat.TERMUX to setOf(ThemeSlot.CursorText, ThemeSlot.Selection, ThemeSlot.Bold, ThemeSlot.Links),
        )
        for (t in TerminalTheme.builtIns) {
            for ((format, missing) in lacks) {
                val dropped = TerminalThemes.dropped(t, format)
                assertTrue(missing.containsAll(dropped), "${t.id} to $format drops $dropped")
            }
            assertEquals(t.links != t.ansi[4], ThemeSlot.Links in TerminalThemes.dropped(t, ThemeFormat.GHOSTTY), "${t.id} Ghostty links")
        }
        // Berth Dark's link colour is its blue, and it has no bold of its own: Ghostty loses nothing of it.
        assertEquals(emptyList(), TerminalThemes.dropped(TerminalTheme.BERTH_DARK, ThemeFormat.GHOSTTY))
        assertEquals(listOf<ThemeSlot>(ThemeSlot.Links), TerminalThemes.dropped(TerminalTheme.DRACULA, ThemeFormat.GHOSTTY))
    }

    @Test
    fun `base16 is read and never written`() {
        assertEquals(false, ThemeFormat.BASE16.exports)
        assertEquals(TerminalThemes.slots, TerminalThemes.dropped(TerminalTheme.NORD, ThemeFormat.BASE16))
        assertFailsWith<IllegalArgumentException> { TerminalThemes.export(TerminalTheme.NORD, ThemeFormat.BASE16) }
    }

    @Test
    fun `exports are named for the theme in the format's own way`() {
        assertEquals("Ros\u00E9 Pine.itermcolors", TerminalThemes.fileName(TerminalTheme.ROSE_PINE, ThemeFormat.ITERM2))
        assertEquals("Ros\u00E9 Pine", TerminalThemes.fileName(TerminalTheme.ROSE_PINE, ThemeFormat.GHOSTTY))
        assertEquals("rose-pine.properties", TerminalThemes.fileName(TerminalTheme.ROSE_PINE, ThemeFormat.TERMUX))
        assertEquals("rose-pine.json", TerminalThemes.fileName(TerminalTheme.ROSE_PINE, ThemeFormat.BERTH))
        assertEquals("a b.itermcolors", TerminalThemes.fileName(TerminalTheme.NORD.copy(name = "a/b"), ThemeFormat.ITERM2))
    }

    @Test
    fun `file names give theme names`() {
        assertEquals("Dracula", TerminalThemes.nameFromFile("Dracula.itermcolors"))
        assertEquals("Gruvbox Light", TerminalThemes.nameFromFile("gruvbox-light.properties"))
        assertEquals("Catppuccin Latte", TerminalThemes.nameFromFile("catppuccin-latte.conf"))
        assertEquals("Everforest Dark Hard", TerminalThemes.nameFromFile("Everforest Dark Hard"))
        assertEquals("iTerm2 Solarized Dark", TerminalThemes.nameFromFile("/storage/Download/iTerm2 Solarized Dark.itermcolors"))
        assertNull(TerminalThemes.nameFromFile("colors.properties"))
        assertNull(TerminalThemes.nameFromFile(null))
        // Termux's own file names no theme, so the import falls back to a plain name.
        val termux = TerminalThemes.export(TerminalTheme.NORD, ThemeFormat.TERMUX)
        assertEquals("Imported theme", assertNotNull(TerminalThemes.import(termux, name = TerminalThemes.nameFromFile("colors.properties"))).name)
    }

    /** A property list with the sixteen ANSI entries and the two named ones, every colour in [space]. */
    private fun plist(space: String, keySuffix: String = "", colour: (key: String) -> Triple<Double, Double, Double>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\">\n<dict>\n")
        val keys = (0..15).map { "Ansi $it Color" } + listOf("Background Color", "Foreground Color")
        for (key in keys) {
            val (r, g, b) = colour(key)
            append("<key>$key$keySuffix</key>\n<dict>\n")
            append("<key>Blue Component</key>\n<real>$b</real>\n<key>Color Space</key>\n<string>$space</string>\n")
            append("<key>Green Component</key>\n<real>$g</real>\n<key>Red Component</key>\n<real>$r</real>\n</dict>\n")
        }
        append("</dict>\n</plist>\n")
    }

    @Test
    fun `iTerm2 colours in Display P3 are converted to sRGB`() {
        // sRGB red is (0.9175, 0.2003, 0.1387) in Display P3; white and a grey are the same in both.
        val file = plist("P3") { key ->
            when (key) {
                "Ansi 1 Color" -> Triple(0.9175, 0.2003, 0.1387)
                "Ansi 7 Color" -> Triple(0.4, 0.4, 0.4)
                "Ansi 9 Color" -> Triple(1.0, 0.0, 0.0)
                else -> Triple(1.0, 1.0, 1.0)
            }
        }
        val t = assertNotNull(TerminalThemes.import(file, name = "P3"))
        val red = t.ansi[1]
        assertTrue(abs(ColorMath.red(red) - 255) <= 1 && ColorMath.green(red) <= 1 && ColorMath.blue(red) <= 1, "P3 red reads as ${hex(red)}")
        assertEquals(0x666666, t.ansi[7])
        // P3's own red is outside sRGB and clips to the nearest sRGB red.
        assertEquals(0xFF0000, t.ansi[9])
        assertEquals(0xFFFFFF, t.background)
    }

    @Test
    fun `an iTerm2 profile with separate light and dark colours reads its dark set`() {
        val file = plist("sRGB", keySuffix = " (Dark)") { Triple(0.0, 0.0, 0.2) }
        val t = assertNotNull(TerminalThemes.import(file, name = "Split"))
        assertEquals(ThemeFormat.ITERM2, TerminalThemes.detect(file))
        assertEquals(0x000033, t.background)
        assertEquals(0x000033, t.ansi[15])
    }

    @Test
    fun `text that is no theme is refused without an exception`() {
        for (junk in listOf("", "hello", "palette = 0=#000000", "base00: \"000000\"", "color0=#000000", "<plist><dict></dict></plist>", "{}", "[1, 2]")) {
            assertNull(TerminalThemes.import(junk), "'$junk'")
            assertEquals(emptyList(), TerminalThemes.importAll(junk), "'$junk'")
        }
    }
}

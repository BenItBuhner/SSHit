package app.berth.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings › Licences against what ships: every licence file under `assets/licenses/` is opened by
 * exactly one row and every row's file is there, so a text added or renamed cannot go unlisted;
 * and a file's hard wraps are joined while its headings, title block and rules keep their lines, and a
 * table's rows stand as paragraphs of their own. Of the shipped texts only the icon sets' table has any,
 * so a text added later that the rule would split fails here first.
 */
class LicencesTest {
    private val shipped = File("src/main/assets/licenses")

    @Test
    fun `every shipped licence file has one row, and every row's file ships`() {
        val named = SHIPPED_NOTICES.flatMap { (_, notices) -> notices.flatMap { it.files } }
        assertEquals("a file named by two rows", named.size, named.toSet().size)
        val files = shipped.list().orEmpty().toSet()
        assertTrue("the licence folder is empty", files.isNotEmpty())
        assertEquals(files, named.toSet())
    }

    @Test
    fun `a credit with no licence file names its terms whole`() {
        val gruvbox = SHIPPED_NOTICES.flatMap { it.second }.single { it.name.startsWith("Gruvbox") }
        assertTrue(gruvbox.files.isEmpty())
        assertEquals("Colours from gruvbox by Pavel Pertsev (github.com/morhetz/gruvbox) under the MIT/X11 licence its README states", gruvbox.terms)
    }

    @Test
    fun `hard wraps are joined, and headings, title blocks and rules keep their lines`() {
        val ofl = """
            |-----------------------------------------------------------
            |SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
            |-----------------------------------------------------------
            |
            |PREAMBLE
            |The goals of the Open Font License (OFL) are to stimulate worldwide
            |development of collaborative font projects, to support the font creation
            |with others.
        """.trimMargin()
        assertEquals(
            listOf(
                "-----------------------------------------------------------\nSIL OPEN FONT LICENSE Version 1.1 - 26 February 2007\n-----------------------------------------------------------",
                "PREAMBLE\nThe goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects, to support the font creation with others.",
            ),
            licenceParagraphs(ofl),
        )
        val apache = "                                 Apache License\r\n                           Version 2.0, January 2004\r\n\r\n   1. Definitions.\r\n"
        assertEquals(listOf("Apache License\nVersion 2.0, January 2004", "1. Definitions."), licenceParagraphs(apache))
    }

    @Test
    fun `a table's rows are paragraphs with their cells set apart, while one sentence's double space still runs on`() {
        val table = """
            |The font as a whole is under the MIT licence (nerd-fonts-symbols-MIT.txt). The icon sets it
            |collects keep their own licences:
            |Codicons                https://github.com/microsoft/vscode-codicons           0.0.45           CC BY 4.0
            |extraglyphs             https://github.com/source-foundry/Hack                 -                MIT
            |Font Logos              https://github.com/lukas-w/font-logos                  1.3.0            The Unlicense
        """.trimMargin()
        assertEquals(
            listOf(
                "The font as a whole is under the MIT licence (nerd-fonts-symbols-MIT.txt). The icon sets it collects keep their own licences:",
                "Codicons \u00B7 https://github.com/microsoft/vscode-codicons \u00B7 0.0.45 \u00B7 CC BY 4.0",
                "extraglyphs \u00B7 https://github.com/source-foundry/Hack \u00B7 - \u00B7 MIT",
                "Font Logos \u00B7 https://github.com/lukas-w/font-logos \u00B7 1.3.0 \u00B7 The Unlicense",
            ),
            licenceParagraphs(table),
        )
        val sentences = "      the brackets!)  The text should be enclosed in the appropriate\n      comment syntax for the file format."
        assertEquals(
            listOf("the brackets!) The text should be enclosed in the appropriate comment syntax for the file format."),
            licenceParagraphs(sentences),
        )
    }

    @Test
    fun `a justified text reads as prose, though its lines hold three padded runs each`() {
        val justified = """
            |THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
            |EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
            |MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
            |NONINFRINGEMENT.
        """.trimMargin()
        assertEquals(
            listOf("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT."),
            licenceParagraphs(justified),
        )
    }

    @Test
    fun `a box drawn in stars loses its drawing and its words read as prose, while a bullet outside keeps its star`() {
        val boxed = """
            |* A bullet outside any box keeps its star.
            |
            |************************************************************************
            |*                                                                      *
            |*  6. Disclaimer of Warranty                                           *
            |*  -------------------------                                           *
            |*                                                                      *
            |*  Covered Software is provided under this License on an "as is"       *
            |*  basis, without warranty of any kind, either expressed, implied, or  *
            |*  statutory.                                                          *
            |*                                                                      *
            |************************************************************************
            |
            |8. Litigation
        """.trimMargin()
        assertEquals(
            listOf(
                "* A bullet outside any box keeps its star.",
                "6. Disclaimer of Warranty\n-------------------------",
                "Covered Software is provided under this License on an \"as is\" basis, without warranty of any kind, either expressed, implied, or statutory.",
                "8. Litigation",
            ),
            licenceParagraphs(boxed),
        )
    }

    @Test
    fun `every font's and library's text names where it came from`() {
        for ((section, notices) in SHIPPED_NOTICES.filter { (section, _) -> section == "Fonts" || section == "Libraries" }) {
            for (notice in notices) assertTrue("$section: ${notice.name} names no origin", !notice.origin.isNullOrBlank() && notice.origin.startsWith("github.com/"))
        }
        assertEquals(listOf("Fonts", "Libraries"), SHIPPED_NOTICES.map { it.first }.filter { it == "Fonts" || it == "Libraries" })
    }

    @Test
    fun `only the icon sets' text holds table rows, and it holds all fourteen`() {
        val rows = shipped.listFiles().orEmpty()
            .associate { file -> file.name to licenceParagraphs(file.readText()).count { TABLE_CELL_SEPARATOR in it } }
            .filterValues { it > 0 }
        assertEquals(mapOf("nerd-fonts-symbols-icon-sets.txt" to 14), rows)
    }

    @Test
    fun `every shipped text keeps every word it has, and adds none but a table's separators`() {
        for (file in shipped.listFiles().orEmpty()) {
            val text = file.readText()
            val words = { s: String -> s.split(Regex("[ \t\r\n]+")).filter { it.isNotEmpty() && !it.all { c -> c == '*' } } }
            val shown = licenceParagraphs(text).flatMap { words(it.replace(TABLE_CELL_SEPARATOR, " ")) }
            assertEquals(file.name, words(text), shown)
        }
    }
}

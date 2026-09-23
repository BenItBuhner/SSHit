package app.berth.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Settings › Licences against what ships: every licence file under `assets/licenses/` is opened by
 * exactly one row and every row's file is there, so a text added or renamed cannot go unlisted;
 * and a file's hard wraps are joined while its headings, title block and rules keep their lines.
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
    fun `every shipped text keeps every word it has`() {
        for (file in shipped.listFiles().orEmpty()) {
            val text = file.readText()
            val words = { s: String -> s.split(Regex("[ \t\r\n]+")).filter { it.isNotEmpty() } }
            assertEquals(file.name, words(text), licenceParagraphs(text).flatMap(words))
        }
    }
}

package app.berth.android.ui.terminal

import app.berth.domain.model.TerminalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPreviewTest {
    private val theme = TerminalTheme.BERTH_DARK

    @Test
    fun `visible length ignores SGR escapes`() {
        assertEquals(5, visibleLength("\u001b[1m\u001b[34mdocs/\u001b[0m"))
        assertEquals(0, visibleLength("\u001b[38;2;122;156;214m"))
    }

    @Test
    fun `the full sample never wraps and fills exactly its rows from narrow to wide panels`() {
        for (cols in 26..100) {
            val lines = previewText(theme, PreviewScript.FULL, cols).split("\r\n")
            assertEquals("rows at $cols columns", PreviewScript.FULL.rows, lines.size)
            for (line in lines) {
                assertTrue("'$line' spills at $cols columns (${visibleLength(line)} cells)", visibleLength(line) < cols)
            }
        }
    }

    @Test
    fun `wider panels keep more of the sample`() {
        val narrow = previewText(theme, PreviewScript.FULL, 40)
        val wide = previewText(theme, PreviewScript.FULL, 60)
        assertTrue(visibleLength(wide) > visibleLength(narrow))
        assertTrue(wide.contains("notes.md") && wide.contains("magenta"))
        assertTrue(narrow.contains("config.toml") && narrow.contains("https://berth.app"))
    }

    @Test
    fun `the tile sample is four rows`() {
        assertEquals(PreviewScript.TILE.rows, previewText(theme, PreviewScript.TILE, 30).split("\r\n").size)
    }
}

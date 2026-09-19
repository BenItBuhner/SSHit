package app.berth.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScrollbackSearchTest {
    private fun term(cols: Int = 10, rows: Int = 4, scrollback: Int = 100, text: String = ""): TerminalEmulator =
        TerminalEmulator(cols, rows, scrollback, TerminalListenerAdapter()).also { if (text.isNotEmpty()) it.write(text) }

    @Test
    fun `finds every occurrence in reading order, history included`() {
        val t = term(cols = 20, rows = 2, text = "nginx up\r\nother\r\nnginx down\r\nnginx again")
        val matches = ScrollbackSearch.find(t.grid, "nginx")
        assertEquals(
            listOf(
                CellRange(CellPos(0, 0), CellPos(0, 4)),
                CellRange(CellPos(2, 0), CellPos(2, 4)),
                CellRange(CellPos(3, 0), CellPos(3, 4)),
            ),
            matches,
        )
    }

    @Test
    fun `a match may run across a soft wrap`() {
        val t = term(cols = 10, text = "error: disk full")
        assertTrue(t.line(0).wrapped)
        assertEquals(listOf(CellRange(CellPos(0, 7), CellPos(1, 0))), ScrollbackSearch.find(t.grid, "disk"))
        assertEquals(listOf(CellRange(CellPos(0, 7), CellPos(1, 5))), ScrollbackSearch.find(t.grid, "disk full"))
    }

    @Test
    fun `a query never matches across a hard line break`() {
        val t = term(cols = 10, text = "ab\r\ncd")
        assertTrue(ScrollbackSearch.find(t.grid, "abcd").isEmpty())
        assertTrue(ScrollbackSearch.find(t.grid, "ab cd").isEmpty())
    }

    @Test
    fun `the case toggle folds or keeps case`() {
        val t = term(cols = 20, text = "Error one\r\nerror two\r\nERROR three")
        assertEquals(3, ScrollbackSearch.find(t.grid, "error").size)
        assertEquals(1, ScrollbackSearch.find(t.grid, "error", caseSensitive = true).size)
        assertEquals(1, ScrollbackSearch.find(t.grid, "Error", caseSensitive = true).size)
        assertEquals(3, ScrollbackSearch.find(t.grid, "ERROR").size)
    }

    @Test
    fun `matches do not overlap`() {
        val t = term(cols = 10, text = "aaaaa")
        assertEquals(listOf(CellRange(CellPos(0, 0), CellPos(0, 1)), CellRange(CellPos(0, 2), CellPos(0, 3))), ScrollbackSearch.find(t.grid, "aa"))
    }

    @Test
    fun `regular expressions match when asked and an invalid one matches nothing`() {
        val t = term(cols = 20, text = "GET /health 200 3ms\r\nPOST /jobs 202 41ms")
        assertEquals(listOf(CellRange(CellPos(0, 12), CellPos(0, 14)), CellRange(CellPos(1, 11), CellPos(1, 13))), ScrollbackSearch.find(t.grid, "20\\d", regex = true))
        assertTrue(ScrollbackSearch.find(t.grid, "20\\d").isEmpty())
        assertTrue(ScrollbackSearch.find(t.grid, "(", regex = true).isEmpty())
        // A pattern that can match nothing at all never loops or yields empty ranges.
        assertTrue(ScrollbackSearch.find(t.grid, "x*", regex = true).all { it.end >= it.start })
    }

    @Test
    fun `wide characters are found and their cells reported whole`() {
        val t = term(cols = 10, text = "日本語 ok")
        assertEquals(listOf(CellRange(CellPos(0, 2), CellPos(0, 5))), ScrollbackSearch.find(t.grid, "本語"))
    }

    @Test
    fun `an empty query and a blank buffer find nothing and the limit bounds the work`() {
        assertTrue(ScrollbackSearch.find(term().grid, "").isEmpty())
        assertTrue(ScrollbackSearch.find(term().grid, "a").isEmpty())
        val t = term(cols = 10, rows = 2, text = (1..50).joinToString("\r\n") { "a" })
        assertEquals(7, ScrollbackSearch.find(t.grid, "a", limit = 7).size)
    }

    @Test
    fun `searching the alternate screen sees its rows only`() {
        val t = term(cols = 10, rows = 3, text = "needle\r\n\u001b[?1049h\u001b[Hhay needle")
        assertEquals(listOf(CellRange(CellPos(0, 4), CellPos(0, 9))), ScrollbackSearch.find(t.grid, "needle"))
    }
}

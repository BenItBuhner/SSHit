package app.berth.android.ui.terminal

import app.berth.terminal.CellPos
import app.berth.terminal.CellRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the selection's handles go ([handleCenters]): under the text with the shoulder toward it
 * in the ordinary case, and never outside the canvas. On the bottom row there is no room below
 * (the pill row and the Deck are there, and a touch there is theirs), so the handle flips above the
 * row; at the first and last columns it is pulled in by its radius. The draw and the hit-test both
 * read these centres, so one function pinned here keeps them in agreement.
 */
class TerminalHandlesTest {
    private val cw = 10f
    private val ch = 20f
    private val r = 9f
    private val rows = 10
    private val cols = 40
    private val width = cols * cw
    private val height = rows * ch

    private fun centers(range: CellRange) = handleCenters(range, rows, cw, ch, r, width, height)

    private fun assertInside(spot: HandleSpot) {
        val (x, y) = spot.center
        assertTrue("x $x inside by the radius", x >= r && x <= width - r)
        assertTrue("y $y inside by the radius", y >= r && y <= height - r)
    }

    @Test
    fun `mid-screen handles hang under their cells with the shoulder up`() {
        val (s, e) = centers(CellRange(CellPos(3, 4), CellPos(3, 9)))
        assertEquals(HandleSpot(androidx.compose.ui.geometry.Offset(4 * cw, 4 * ch + r), above = false), s)
        assertEquals(HandleSpot(androidx.compose.ui.geometry.Offset(10 * cw, 4 * ch + r), above = false), e)
        assertInside(s!!)
        assertInside(e!!)
    }

    @Test
    fun `a range ending on the last row and column keeps both handles inside the canvas`() {
        val (s, e) = centers(CellRange(CellPos(rows - 1, 0), CellPos(rows - 1, cols - 1)))
        assertInside(s!!)
        assertInside(e!!)
        // No room below the bottom row: both flip above it, shoulder down.
        assertTrue(s.above)
        assertTrue(e.above)
        assertEquals((rows - 1) * ch - r, s.center.y)
        assertEquals((rows - 1) * ch - r, e.center.y)
        // The first column pulls the start in by its radius; the last pulls the end in.
        assertEquals(r, s.center.x)
        assertEquals(width - r, e.center.x)
    }

    @Test
    fun `the flip happens exactly when the disc would cross the bottom edge`() {
        // A canvas whose height leaves a little under the bottom row, but less than the disc needs.
        val tight = handleCenters(CellRange(CellPos(rows - 1, 2), CellPos(rows - 1, 5)), rows, cw, ch, r, width, height + r)
        assertTrue(tight.first!!.above)
        // Enough room for the whole disc: it hangs below as usual.
        val roomy = handleCenters(CellRange(CellPos(rows - 1, 2), CellPos(rows - 1, 5)), rows, cw, ch, r, width, height + 2 * r)
        assertFalse(roomy.first!!.above)
        assertEquals(rows * ch + r, roomy.first!!.center.y)
    }

    @Test
    fun `ends off the screen have no handle`() {
        val (s, e) = centers(CellRange(CellPos(-3, 0), CellPos(2, 4)))
        assertNull(s)
        assertInside(e!!)
        val (s2, e2) = centers(CellRange(CellPos(2, 0), CellPos(rows + 4, 4)))
        assertInside(s2!!)
        assertNull(e2)
    }
}

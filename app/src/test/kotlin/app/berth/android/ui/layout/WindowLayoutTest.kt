package app.berth.android.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one answer every adapting surface reads (spec A12, C23): which windows fit two panes, stand
 * the drawer up as a rail, open sheets as dialogs, and shorten the chrome. The phone in portrait
 * answers no to all four, which is what keeps its frames what they were.
 */
class WindowLayoutTest {
    @Test
    fun `a phone in portrait sees the layouts it always had`() {
        val phone = WindowLayout(411, 914)
        assertEquals(WidthClass.COMPACT, phone.width)
        assertEquals(HeightClass.EXPANDED, phone.height)
        assertFalse(phone.panes)
        assertFalse(phone.rail)
        assertFalse(phone.dialogs)
        assertFalse(phone.shortLandscape)
    }

    @Test
    fun `a phone on its side fits two panes and shortens the chrome, but keeps its sheets and drawer`() {
        val phone = WindowLayout(914, 411)
        assertEquals(WidthClass.EXPANDED, phone.width)
        assertEquals(HeightClass.COMPACT, phone.height)
        assertTrue(phone.panes)
        assertTrue(phone.shortLandscape)
        // Too short for the rail's rows or for a dialog to be better than a sheet.
        assertFalse(phone.rail)
        assertFalse(phone.dialogs)
    }

    @Test
    fun `a foldable open the wide way stands the rail up`() {
        val fold = WindowLayout(841, 701)
        assertEquals(WidthClass.EXPANDED, fold.width)
        assertEquals(HeightClass.MEDIUM, fold.height)
        assertTrue(fold.panes)
        assertTrue(fold.rail)
        assertTrue(fold.dialogs)
        assertFalse(fold.shortLandscape)
    }

    @Test
    fun `a foldable turned tall is medium, panes and dialogs without the rail`() {
        val fold = WindowLayout(701, 841)
        assertEquals(WidthClass.MEDIUM, fold.width)
        assertTrue(fold.panes)
        assertTrue(fold.dialogs)
        assertFalse(fold.rail)
        assertFalse(fold.shortLandscape)
    }

    @Test
    fun `a tablet in portrait is medium too`() {
        val tablet = WindowLayout(800, 1280)
        assertEquals(WidthClass.MEDIUM, tablet.width)
        assertEquals(HeightClass.EXPANDED, tablet.height)
        assertTrue(tablet.panes)
        assertTrue(tablet.dialogs)
        assertFalse(tablet.rail)
    }

    @Test
    fun `a tablet on its side has all of it`() {
        val tablet = WindowLayout(1280, 800)
        assertEquals(WidthClass.EXPANDED, tablet.width)
        assertEquals(HeightClass.MEDIUM, tablet.height)
        assertTrue(tablet.panes)
        assertTrue(tablet.rail)
        assertTrue(tablet.dialogs)
        assertFalse(tablet.shortLandscape)
    }

    @Test
    fun `the width classes turn at 600 and 840`() {
        assertEquals(WidthClass.COMPACT, WindowLayout(599, 1000).width)
        assertEquals(WidthClass.MEDIUM, WindowLayout(600, 1000).width)
        assertEquals(WidthClass.MEDIUM, WindowLayout(839, 1000).width)
        assertEquals(WidthClass.EXPANDED, WindowLayout(840, 1000).width)
    }

    @Test
    fun `the height classes turn at 480 and 900`() {
        assertEquals(HeightClass.COMPACT, WindowLayout(1000, 479).height)
        assertEquals(HeightClass.MEDIUM, WindowLayout(1000, 480).height)
        assertEquals(HeightClass.MEDIUM, WindowLayout(1000, 899).height)
        assertEquals(HeightClass.EXPANDED, WindowLayout(1000, 900).height)
    }

    @Test
    fun `short landscape wants a short window that is wider than it is tall`() {
        assertTrue(WindowLayout(640, 360).shortLandscape)
        // A short square, or a tiny portrait window, is not a phone on its side.
        assertFalse(WindowLayout(400, 400).shortLandscape)
        assertFalse(WindowLayout(300, 479).shortLandscape)
        // Wide and short opens the panes but never the rail, whatever the width.
        val wideShort = WindowLayout(1280, 400)
        assertTrue(wideShort.panes)
        assertFalse(wideShort.rail)
        assertFalse(wideShort.dialogs)
    }
}

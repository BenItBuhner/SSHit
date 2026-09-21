package app.berth.android.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one answer every adapting surface reads (spec A12, C23, C7, C4): which windows fit two panes,
 * how the drawer stands (a sheet, the 72 dp column or the 280 dp rail), which open sheets as dialogs,
 * which have the room for the Deck's second row, and which shorten the chrome. The phone in
 * portrait answers no to all of them, which is what keeps its frames what they were.
 */
class WindowLayoutTest {
    @Test
    fun `a phone in portrait sees the layouts it always had`() {
        val phone = WindowLayout(411, 914)
        assertEquals(WidthClass.COMPACT, phone.width)
        assertEquals(HeightClass.EXPANDED, phone.height)
        assertFalse(phone.panes)
        assertEquals(DrawerForm.SHEET, phone.drawer)
        assertFalse(phone.rail)
        assertFalse(phone.dialogs)
        assertFalse(phone.twoRowDeck)
        assertFalse(phone.shortLandscape)
    }

    @Test
    fun `a phone on its side fits two panes and shortens the chrome, but keeps its sheets and drawer and one Deck row`() {
        val phone = WindowLayout(914, 411)
        assertEquals(WidthClass.EXPANDED, phone.width)
        assertEquals(HeightClass.COMPACT, phone.height)
        assertTrue(phone.panes)
        assertTrue(phone.shortLandscape)
        // Too short for either rail's rows, for a dialog to be better than a sheet, or for a second row of keys.
        assertEquals(DrawerForm.SHEET, phone.drawer)
        assertFalse(phone.rail)
        assertFalse(phone.dialogs)
        assertFalse(phone.twoRowDeck)
    }

    @Test
    fun `a foldable open the wide way stands the wide rail up and has the room for two Deck rows`() {
        val fold = WindowLayout(841, 701)
        assertEquals(WidthClass.EXPANDED, fold.width)
        assertEquals(HeightClass.MEDIUM, fold.height)
        assertTrue(fold.panes)
        assertEquals(DrawerForm.WIDE, fold.drawer)
        assertTrue(fold.rail)
        assertTrue(fold.dialogs)
        assertTrue(fold.twoRowDeck)
        assertFalse(fold.shortLandscape)
    }

    @Test
    fun `a foldable turned tall is medium, panes and dialogs with the narrow rail`() {
        val fold = WindowLayout(701, 841)
        assertEquals(WidthClass.MEDIUM, fold.width)
        assertTrue(fold.panes)
        assertTrue(fold.dialogs)
        assertEquals(DrawerForm.NARROW, fold.drawer)
        assertTrue(fold.rail)
        assertTrue(fold.twoRowDeck)
        assertFalse(fold.shortLandscape)
    }

    @Test
    fun `a tablet in portrait is medium too, the narrow rail standing`() {
        val tablet = WindowLayout(800, 1280)
        assertEquals(WidthClass.MEDIUM, tablet.width)
        assertEquals(HeightClass.EXPANDED, tablet.height)
        assertTrue(tablet.panes)
        assertTrue(tablet.dialogs)
        assertEquals(DrawerForm.NARROW, tablet.drawer)
        assertTrue(tablet.rail)
        assertTrue(tablet.twoRowDeck)
    }

    @Test
    fun `a tablet on its side has all of it`() {
        val tablet = WindowLayout(1280, 800)
        assertEquals(WidthClass.EXPANDED, tablet.width)
        assertEquals(HeightClass.MEDIUM, tablet.height)
        assertTrue(tablet.panes)
        assertEquals(DrawerForm.WIDE, tablet.drawer)
        assertTrue(tablet.rail)
        assertTrue(tablet.dialogs)
        assertTrue(tablet.twoRowDeck)
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
    fun `the drawer's forms turn with the width classes, the narrow rail from 600 to 840 and the wide one past it`() {
        assertEquals(DrawerForm.SHEET, WindowLayout(599, 1000).drawer)
        assertEquals(DrawerForm.NARROW, WindowLayout(600, 1000).drawer)
        assertEquals(DrawerForm.NARROW, WindowLayout(839, 1000).drawer)
        assertEquals(DrawerForm.WIDE, WindowLayout(840, 1000).drawer)
    }

    @Test
    fun `the height classes turn at 480 and 900`() {
        assertEquals(HeightClass.COMPACT, WindowLayout(1000, 479).height)
        assertEquals(HeightClass.MEDIUM, WindowLayout(1000, 480).height)
        assertEquals(HeightClass.MEDIUM, WindowLayout(1000, 899).height)
        assertEquals(HeightClass.EXPANDED, WindowLayout(1000, 900).height)
    }

    @Test
    fun `the second Deck row wants a window past compact both ways`() {
        // A tablet's or a fold's window either way up.
        assertTrue(WindowLayout(600, 480).twoRowDeck)
        assertTrue(WindowLayout(1280, 800).twoRowDeck)
        // A phone upright is compact in width; a phone on its side, or any short window, is compact in height.
        assertFalse(WindowLayout(599, 1000).twoRowDeck)
        assertFalse(WindowLayout(1280, 479).twoRowDeck)
    }

    @Test
    fun `short landscape wants a short window that is wider than it is tall`() {
        assertTrue(WindowLayout(640, 360).shortLandscape)
        // A short square, or a tiny portrait window, is not a phone on its side.
        assertFalse(WindowLayout(400, 400).shortLandscape)
        assertFalse(WindowLayout(300, 479).shortLandscape)
        // Wide and short opens the panes but never a rail, whatever the width.
        val wideShort = WindowLayout(1280, 400)
        assertTrue(wideShort.panes)
        assertEquals(DrawerForm.SHEET, wideShort.drawer)
        assertFalse(wideShort.rail)
        assertFalse(wideShort.dialogs)
    }
}

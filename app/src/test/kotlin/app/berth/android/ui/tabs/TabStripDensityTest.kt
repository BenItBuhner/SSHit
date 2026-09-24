package app.berth.android.ui.tabs

import androidx.compose.ui.unit.dp
import app.berth.android.ui.short
import app.berth.android.ui.theme.DensityTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The strip at density's header (spec A12, ribbon 40 → 28): Comfortable's header leaves a style
 * as it is; Compact's steps it down in size alone, the swatch as far in from the tab's edges as
 * the padding and the height it gave up added to the target's reach, the phone on its side too.
 * The Stage steps it down only where a status bar's inset lends that whole reach; anywhere else
 * the skin's own strip stands whole.
 */
class TabStripDensityTest {
    private val compact = DensityTokens.Compact.header

    @Test
    fun `comfortable's header leaves the style as it is`() {
        assertSame(TabStripStyle.Default, TabStripStyle.Default.within(DensityTokens.Comfortable.header))
        assertSame(TabStripStyle.Default, TabStripStyle.Default.atDensity(DensityTokens.Comfortable.header, 24.dp))
        assertSame(TabStripStyle.Default, TabStripStyle.Default.atDensity(DensityTokens.Comfortable.header, 0.dp))
    }

    @Test
    fun `compact's header takes the strip to 28 with 24 dp tabs and a 16 dp swatch in 4 dp`() {
        val s = TabStripStyle.Default.within(compact)
        assertEquals(28.dp, s.height)
        assertEquals(24.dp, s.tabHeight)
        assertEquals(4.dp, s.tabPadding)
        assertEquals(16.dp, s.swatchSize)
        assertEquals(20.dp, s.chipHeight)
    }

    @Test
    fun `the height given up goes to the reach, so the target stands 48 dp`() {
        val s = TabStripStyle.Default.within(compact)
        assertEquals(20.dp, s.topReach)
        assertEquals(48.dp, s.height + s.topReach)
    }

    @Test
    fun `under a phone's status bar the stepped strip stands, its whole reach the inset's`() {
        val s = TabStripStyle.Default.atDensity(compact, 24.dp)
        assertEquals(TabStripStyle.Default.within(compact), s)
        assertEquals(20.dp, s.reachUnder(24.dp))
        assertEquals(48.dp, s.height + s.reachUnder(24.dp))
    }

    @Test
    fun `with no status bar the skin's strip stands whole`() {
        val s = TabStripStyle.Default.atDensity(compact, 0.dp)
        assertSame(TabStripStyle.Default, s)
        assertEquals(40.dp, s.height)
        assertEquals(32.dp, s.tabHeight)
        assertEquals(20.dp, s.swatchSize)
        assertEquals("and the band over it", 44.dp, s.height + s.reachUnder(0.dp))
    }

    @Test
    fun `a status bar short of the stepped strip's reach keeps the skin's, whose targets it lends the most`() {
        assertSame("12 dp takes the step but not the skin's own 8 over it", TabStripStyle.Default, TabStripStyle.Default.atDensity(compact, 12.dp))
        assertSame(TabStripStyle.Default, TabStripStyle.Default.atDensity(compact, 19.dp))
        assertEquals(48.dp, TabStripStyle.Default.height + TabStripStyle.Default.reachUnder(12.dp))
        assertEquals(28.dp, TabStripStyle.Default.atDensity(compact, 20.dp).height)
    }

    @Test
    fun `comfortable reaches into the inset as before, and no further than it goes`() {
        assertEquals(5.dp, TabStripStyle.Default.reachUnder(5.dp))
        assertEquals(8.dp, TabStripStyle.Default.reachUnder(24.dp))
        assertEquals("the inset above the reach", 16.dp, TabStripStyle.Default.insetAbove(24.dp))
    }

    /** Spec C3 l.351: the band is what the inset does not lend of 4 dp, 4 with no status bar and nothing under 4 dp or more. */
    @Test
    fun `where the inset lends less than 4 dp a band makes up the difference, and stands above nothing`() {
        val s = TabStripStyle.Default
        assertEquals("no status bar: the band's 4", 4.dp, s.reachUnder(0.dp))
        assertEquals("2 dp lent and a 2 dp band", 4.dp, s.reachUnder(2.dp))
        assertEquals(4.dp, s.reachUnder(4.dp))
        for (inset in listOf(0.dp, 2.dp, 4.dp)) assertEquals("no inset left above the row at $inset", 0.dp, s.insetAbove(inset))
        assertEquals("the header 44 with no status bar", 44.dp, s.insetAbove(0.dp) + s.reachUnder(0.dp) + s.height)
        assertEquals("and under 2 dp of one", 44.dp, s.insetAbove(2.dp) + s.reachUnder(2.dp) + s.height)
        assertEquals("under a phone's 24 the header is the inset and the row, no taller", 64.dp, s.insetAbove(24.dp) + s.reachUnder(24.dp) + s.height)
    }

    @Test
    fun `an island lends nothing, so it keeps the skin's strip`() {
        val island = TabStripStyle(chrome = StripChrome.ISLAND)
        assertEquals(0.dp, island.reachUnder(24.dp))
        assertEquals(0.dp, island.reachUnder(0.dp))
        assertSame(island, island.atDensity(compact, 24.dp))
    }

    @Test
    fun `the skin holds`() {
        val skin = TabStripStyle(activeMark = ActiveTabMark.UNDERLINE, inactiveTitles = false, tabMinWidth = 72.dp)
        val s = skin.atDensity(compact, 24.dp)
        assertEquals(skin.copy(height = s.height, tabHeight = s.tabHeight, tabPadding = s.tabPadding, swatchSize = s.swatchSize, chipHeight = s.chipHeight, topReach = s.topReach), s)
    }

    @Test
    fun `the phone on its side reaches into the status bar for the same 48 dp target`() {
        val short = TabStripStyle.Default.short()
        assertEquals(32.dp, short.height)
        assertEquals(48.dp, short.height + short.reachUnder(24.dp))
    }

    @Test
    fun `the phone on its side steps from its 32 to 28 as well, and with no status bar stands its own 32`() {
        val s = TabStripStyle.Default.short().atDensity(compact, 24.dp)
        assertEquals(28.dp, s.height)
        assertEquals(24.dp, s.tabHeight)
        assertEquals(16.dp, s.swatchSize)
        assertEquals(20.dp, s.topReach)
        assertEquals(48.dp, s.height + s.reachUnder(24.dp))
        assertEquals(TabStripStyle.Default.short(), TabStripStyle.Default.short().atDensity(compact, 0.dp))
    }
}

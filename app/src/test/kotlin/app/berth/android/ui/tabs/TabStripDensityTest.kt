package app.berth.android.ui.tabs

import androidx.compose.ui.unit.dp
import app.berth.android.ui.short
import app.berth.android.ui.theme.DensityTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The strip within density's header (spec A12, ribbon 40 → 28): Comfortable's header leaves a
 * style as it is; Compact's steps it down in size alone, the swatch as far in from the tab's edges
 * as the padding and the height it gave up kept as the target's reach, the phone on its side too.
 * The reach comes out of the status bar's inset where it has the room, and the kept part stands
 * as the header's own band where it has not.
 */
class TabStripDensityTest {
    private val compact = DensityTokens.Compact.header

    @Test
    fun `comfortable's header leaves the style as it is`() {
        assertSame(TabStripStyle.Default, TabStripStyle.Default.within(DensityTokens.Comfortable.header))
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
    fun `the height given up is kept as reach, so the target stands 48 dp`() {
        val s = TabStripStyle.Default.within(compact)
        assertEquals(TabStripStyle.Default.topReach, s.topReach)
        assertEquals(12.dp, s.keptReach)
        assertEquals(48.dp, s.height + s.topReach + s.keptReach)
    }

    @Test
    fun `under a status bar the whole reach is the inset's`() {
        val s = TabStripStyle.Default.within(compact)
        assertEquals(20.dp, s.reachUnder(24.dp))
        assertEquals(48.dp, s.height + s.reachUnder(24.dp))
    }

    @Test
    fun `with no status bar the kept reach stands as the row's own, so the target is Comfortable's`() {
        val s = TabStripStyle.Default.within(compact)
        assertEquals(12.dp, s.reachUnder(0.dp))
        assertEquals(TabStripStyle.Default.height + TabStripStyle.Default.reachUnder(0.dp), s.height + s.reachUnder(0.dp))
        assertEquals("a status bar shorter than the kept reach lends what it has, the band the rest", 12.dp, s.reachUnder(8.dp))
    }

    @Test
    fun `comfortable reaches into the inset as before, and no further than it goes`() {
        assertEquals(0.dp, TabStripStyle.Default.reachUnder(0.dp))
        assertEquals(5.dp, TabStripStyle.Default.reachUnder(5.dp))
        assertEquals(8.dp, TabStripStyle.Default.reachUnder(24.dp))
    }

    @Test
    fun `an island lends nothing and keeps its band inside it`() {
        val island = TabStripStyle(chrome = StripChrome.ISLAND)
        assertEquals(0.dp, island.reachUnder(24.dp))
        assertEquals(12.dp, island.within(compact).reachUnder(24.dp))
    }

    @Test
    fun `the skin holds`() {
        val skin = TabStripStyle(chrome = StripChrome.ISLAND, activeMark = ActiveTabMark.UNDERLINE, inactiveTitles = false, tabMinWidth = 72.dp)
        val s = skin.within(compact)
        assertEquals(skin.copy(height = s.height, tabHeight = s.tabHeight, tabPadding = s.tabPadding, swatchSize = s.swatchSize, chipHeight = s.chipHeight, keptReach = s.keptReach), s)
    }

    @Test
    fun `the phone on its side reaches into the status bar for the same 48 dp target`() {
        val short = TabStripStyle.Default.short()
        assertEquals(32.dp, short.height)
        assertEquals(48.dp, short.height + short.reachUnder(24.dp))
    }

    @Test
    fun `the phone on its side steps from its 32 to 28 as well`() {
        val s = TabStripStyle.Default.short().within(compact)
        assertEquals(28.dp, s.height)
        assertEquals(24.dp, s.tabHeight)
        assertEquals(16.dp, s.swatchSize)
        assertEquals(4.dp, s.keptReach)
        assertEquals(48.dp, s.height + s.reachUnder(24.dp))
        assertEquals("with no status bar it stands as tall as its own 32", 32.dp, s.height + s.reachUnder(0.dp))
    }
}

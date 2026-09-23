package app.berth.android.ui.stage

import androidx.compose.ui.unit.dp
import app.berth.android.ui.theme.DensityTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Deck at the interface's density (spec A12, Deck 44 → 40): Compact takes its 4 dp off each
 * key height setting (A9: 40 to 52) and never goes under 40; Comfortable keeps the setting, and
 * the setting's caption says what Compact made of it.
 */
class DeckDensityTest {
    @Test
    fun `comfortable keys stand as set`() {
        for (setting in listOf(40, 44, 48, 52)) assertEquals(setting.dp, deckKeyHeight(setting, DensityTokens.Comfortable))
    }

    @Test
    fun `compact brings each height a step down and leaves 40 where it is`() {
        assertEquals(40.dp, deckKeyHeight(44, DensityTokens.Compact))
        assertEquals(44.dp, deckKeyHeight(48, DensityTokens.Compact))
        assertEquals(48.dp, deckKeyHeight(52, DensityTokens.Compact))
        assertEquals(40.dp, deckKeyHeight(40, DensityTokens.Compact))
    }

    @Test
    fun `a setting out of range is held to it first`() {
        assertEquals(52.dp, deckKeyHeight(60, DensityTokens.Comfortable))
        assertEquals(48.dp, deckKeyHeight(60, DensityTokens.Compact))
        assertEquals(40.dp, deckKeyHeight(30, DensityTokens.Compact))
    }

    @Test
    fun `the setting's caption says the compact height, and nothing where the keys stand as set`() {
        assertEquals("40 dp under Compact", deckKeyHeightNote(44, DensityTokens.Compact))
        assertEquals("48 dp under Compact", deckKeyHeightNote(52, DensityTokens.Compact))
        assertNull(deckKeyHeightNote(40, DensityTokens.Compact))
        assertNull(deckKeyHeightNote(44, DensityTokens.Comfortable))
    }
}

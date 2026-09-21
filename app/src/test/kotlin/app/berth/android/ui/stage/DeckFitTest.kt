package app.berth.android.ui.stage

import app.berth.domain.model.DeckLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window's fits of the saved Deck layout (spec C23, C4): a phone on its side takes the Deck
 * down to one row of 40, a tablet gives it its second row, and neither touches the layers.
 */
class DeckFitTest {
    private val saved = DeckLayout.default()

    @Test
    fun `the short fit is one row of 40 whatever was saved`() {
        val fitted = ShortDeckFit.fit(saved.copy(rows = 2, heightDp = 52))
        assertEquals(1, fitted.rows)
        assertEquals(40, fitted.heightDp)
        assertEquals(saved.layers, fitted.layers)
    }

    @Test
    fun `the two-row fit adds the second row to a one-row layout and keeps the key height`() {
        val fitted = TwoRowDeckFit.fit(saved.copy(rows = 1, heightDp = 48))
        assertEquals(2, fitted.rows)
        assertEquals(48, fitted.heightDp)
        assertEquals(saved.layers, fitted.layers)
        assertEquals(saved.reach, fitted.reach)
        assertEquals(saved.arrows, fitted.arrows)
    }

    @Test
    fun `a layout saved with two rows or more keeps them under the two-row fit`() {
        assertEquals(2, TwoRowDeckFit.fit(saved.copy(rows = 2)).rows)
        assertEquals(3, TwoRowDeckFit.fit(saved.copy(rows = 3)).rows)
    }
}

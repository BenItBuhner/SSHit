package app.berth.android.ui.stage

import androidx.compose.runtime.staticCompositionLocalOf
import app.berth.domain.model.DeckLayout

/** How the Stage fits the saved Deck layout to the window it is in. */
fun interface DeckFit {
    fun fit(layout: DeckLayout): DeckLayout
}

/** The fit in force: the layout as saved unless the window says otherwise. */
val LocalDeckFit = staticCompositionLocalOf<DeckFit> { DeckFit { it } }

/**
 * The least the Deck can be while every key keeps its 40 × 44 target (spec A12): one row of
 * 40 dp keys. For a phone on its side (spec C23), where a second row would take the terminal's
 * last lines; the second layer stays a swipe away.
 */
val ShortDeckFit = DeckFit { it.copy(rows = 1, heightDp = 40) }

/**
 * The tablet's default (spec C4, two-row mode; #14 review, nit 6): two rows, the saved layer over
 * Nav/Fn, on a window with the room for them ([app.berth.android.ui.layout.WindowLayout.twoRowDeck])
 * while Settings › Deck keeps "Two rows on a large screen" on. A layout saved with two rows already
 * keeps them; the key height is the saved one either way.
 */
val TwoRowDeckFit = DeckFit { it.copy(rows = maxOf(it.rows, 2)) }

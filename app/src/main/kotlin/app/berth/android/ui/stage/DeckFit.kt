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

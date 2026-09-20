package app.berth.android.ui.tabs

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.berth.android.ui.a11y.BerthMotion
import app.berth.android.ui.a11y.LocalReducedMotion

/**
 * A strip item's placement: it slides to its new slot when the entries change, and fades in or
 * out; under reduced motion (spec A7) it appears and leaves on the 120 ms crossfade and is in its
 * slot the frame the entries change.
 */
@Composable
fun LazyItemScope.stripItem(): Modifier =
    if (LocalReducedMotion.current) Modifier.animateItem(fadeInSpec = BerthMotion.crossfade(), placementSpec = null, fadeOutSpec = BerthMotion.crossfade())
    else Modifier.animateItem()

/** Brings [index] into view: scrolled over 120 ms, or put there in a frame under reduced motion. */
suspend fun LazyListState.bring(reducedMotion: Boolean, index: Int) {
    if (reducedMotion) scrollToItem(index) else animateScrollToItem(index)
}

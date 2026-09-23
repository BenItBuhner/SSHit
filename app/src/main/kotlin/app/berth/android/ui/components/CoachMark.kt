package app.berth.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType

/** The one-time coach marks (spec A9), under the [key] the settings keep each by once it is dismissed. */
enum class CoachMarkId(val key: String) {
    /** The Nub's, the first time the Deck has it in reach (product vision, the Nub). */
    NUB("nub"),
}

/**
 * Who has the screen's one coach mark (spec A9: never more than one on screen). The marks composed
 * at once wait in the order they came and the first shows; when it goes, the next does. While a
 * sheet or the drawer covers the screen ([CoachMarkCover]) none shows, since a mark is a window of
 * its own and would float over them; each keeps its place and is back when the cover goes. One per
 * shell, through [LocalCoachMarkSlot].
 */
@Stable
class CoachMarkSlot {
    private val waiting = mutableStateListOf<Any>()
    private val covers = mutableStateListOf<Any>()

    /** Whether the mark under [key] is the one on screen now. */
    fun shows(key: Any): Boolean = covers.isEmpty() && waiting.firstOrNull() == key

    fun join(key: Any) {
        if (key !in waiting) waiting += key
    }

    fun leave(key: Any) {
        waiting -= key
    }

    fun cover(key: Any) {
        if (key !in covers) covers += key
    }

    fun uncover(key: Any) {
        covers -= key
    }
}

val LocalCoachMarkSlot = staticCompositionLocalOf { CoachMarkSlot() }

/** Keeps every coach mark off the screen for as long as it is composed: a sheet's, or the open drawer's. */
@Composable
fun CoachMarkCover() {
    val slot = LocalCoachMarkSlot.current
    DisposableEffect(slot) {
        val key = Any()
        slot.cover(key)
        onDispose { slot.uncover(key) }
    }
}

/**
 * A one-time coach mark (spec A9): Body text on `surface.3` at radius 12 and a single dismiss
 * [action], hung from the layout it is composed in the way a [BerthMenu] hangs from its box:
 * centred over it, below it where there is no room above, and kept inside the window. A window of
 * the app's ([BerthPopup]) that takes no focus, so what it explains stays in reach under it; only
 * the action puts it away, and [onDismiss] is where its owner records it seen. While another mark
 * is up, or a sheet or the drawer covers the screen, it waits its turn in the [LocalCoachMarkSlot].
 */
@Composable
fun CoachMark(text: String, onDismiss: () -> Unit, action: String = "Got it") {
    val slot = LocalCoachMarkSlot.current
    val key = remember { Any() }
    DisposableEffect(slot, key) {
        slot.join(key)
        onDispose { slot.leave(key) }
    }
    if (!slot.shows(key)) return
    val density = LocalDensity.current
    val provider = remember(density) { with(density) { CoachMarkPosition(gap = CoachMarkGap.roundToPx(), margin = CoachMarkMargin.roundToPx()) } }
    BerthPopup(provider, PopupProperties(focusable = false)) {
        val c = Berth.colors
        BoxWithConstraints {
            Column(
                Modifier
                    .widthIn(max = min(CoachMarkMaxWidth, maxWidth - CoachMarkMargin * 2))
                    .clip(RoundedCornerShape(BerthRadius.row))
                    .background(c.surface3)
                    .padding(start = 16.dp, top = 14.dp, end = 4.dp, bottom = 2.dp),
            ) {
                Text(
                    text,
                    style = BerthType.body,
                    color = c.text1,
                    modifier = Modifier.padding(end = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                BerthButton(action, onClick = onDismiss, modifier = Modifier.align(Alignment.End), kind = ButtonKind.TEXT)
            }
        }
    }
}

/**
 * Where a [CoachMark] stands: centred on its anchor, [gap] above it, or [gap] below where the
 * window has no room above, and [margin] in from the window's sides.
 */
internal class CoachMarkPosition(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val above = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (above >= margin) above else anchorBounds.bottom + gap)
    }
}

private val CoachMarkMaxWidth = 320.dp
private val CoachMarkGap = 8.dp
private val CoachMarkMargin = 8.dp

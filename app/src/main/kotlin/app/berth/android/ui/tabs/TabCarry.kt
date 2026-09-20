package app.berth.android.ui.tabs

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.berth.android.session.PaneSide
import app.berth.domain.model.SwatchColor

/** The tab in the hand while it is carried from the strip to a pane (spec C23). */
@Stable
data class CarriedTab(val id: String, val title: String, val color: SwatchColor, val monogram: String)

/**
 * A tab on its way from the strip to a pane (spec C23): the pane layer provides one while the
 * window fits two panes, feeds it the panes' bounds and which tab sits where, and the strip lifts
 * a tab into it once the finger pulls it below the strip. Positions are in the root's coordinates,
 * the one space the strip and the panes share. Without one (a phone in portrait) the strip's
 * long-press drag is the reorder it always was and its menu offers no pane.
 */
@Stable
class TabCarry {
    /** Which pane holds which tab right now, from the Stage's split. */
    var placement: Map<String, PaneSide> by mutableStateOf(emptyMap())

    /** The tab that takes the keys, so a menu can tell "beside the active tab" from "in its place". */
    var activeId: String? by mutableStateOf(null)

    /** Each pane's bounds in root coordinates, as the layer last laid them out. */
    var bounds: Map<PaneSide, Rect> by mutableStateOf(emptyMap())

    /** The tab being carried, or null. */
    var carried: CarriedTab? by mutableStateOf(null)
        private set

    /** The finger, in root coordinates, while a tab is carried. */
    var position: Offset by mutableStateOf(Offset.Zero)
        private set

    /** The pane under the finger, or null while it is over neither. */
    val target: PaneSide? by derivedStateOf {
        if (carried == null) null else bounds.entries.firstOrNull { it.value.contains(position) }?.key
    }

    fun sideOf(id: String): PaneSide? = placement[id]

    fun begin(tab: CarriedTab, at: Offset) {
        carried = tab
        position = at
    }

    fun moveTo(at: Offset) {
        if (carried != null) position = at
    }

    /** Finger up: the carried tab and the pane it was over, or null when it was over neither. Ends the carry. */
    fun drop(): Pair<CarriedTab, PaneSide>? {
        val tab = carried ?: return null
        val side = target
        carried = null
        return side?.let { tab to it }
    }

    fun cancel() {
        carried = null
    }
}

/** The carry in force, or null where the window shows one pane. */
val LocalTabCarry = compositionLocalOf<TabCarry?> { null }

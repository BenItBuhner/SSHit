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
 * window fits two panes, feeds it the drop targets' bounds and which tab sits where, and the strip
 * lifts a tab into it once the finger pulls it below the strip. The targets are the two panes, or
 * the two halves of the one body on a Stage not yet split, so dragging a tab onto the Stage splits
 * it. Positions are in the root's coordinates, the one space the strip and the panes share.
 * Without one (a phone in portrait) the strip's long-press drag is the reorder it always was and
 * its menu offers no pane.
 */
@Stable
class TabCarry {
    /** Which pane holds which tab right now, from the Stage's split. */
    var placement: Map<String, PaneSide> by mutableStateOf(emptyMap())

    /** The tab that takes the keys, so a menu can tell "beside the active tab" from "in its place". */
    var activeId: String? by mutableStateOf(null)

    /**
     * Each target's bounds in root coordinates, as the layer laying them out last placed them: a
     * pane each, or a half of the one body each. The layer that sets them clears them as it leaves,
     * so a target is never a rectangle nothing occupies any more.
     */
    var bounds: Map<PaneSide, Rect> by mutableStateOf(emptyMap())

    /**
     * Whether a drop of the tab [id] lands somewhere right now: the layer that is up has targets,
     * and [id] is not the one tab already filling an unsplit Stage, which has no beside-itself. The
     * strip lifts a tab out only while this holds; otherwise its drag stays the reorder it is on a phone.
     */
    fun accepts(id: String): Boolean = bounds.isNotEmpty() && !(placement.isEmpty() && id == activeId)

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

    /** Lifts [tab] out of the strip at [at]; false, and nothing lifted, while nothing takes it. */
    fun begin(tab: CarriedTab, at: Offset): Boolean {
        if (!accepts(tab.id)) return false
        carried = tab
        position = at
        return true
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

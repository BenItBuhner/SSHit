package app.berth.android.session

import app.berth.domain.model.StageSide
import app.berth.domain.model.StageSplit

/** One of the two panes of a split Stage (spec C23). */
enum class PaneSide {
    LEFT, RIGHT;

    val other: PaneSide get() = if (this == LEFT) RIGHT else LEFT

    /** The side as it is stored across processes. */
    val stored: StageSide get() = if (this == LEFT) StageSide.LEFT else StageSide.RIGHT

    companion object {
        fun of(side: StageSide): PaneSide = if (side == StageSide.LEFT) LEFT else RIGHT
    }
}

/**
 * The Stage split in two (spec C23): the active tab holds one pane and takes the keys, the
 * companion holds the other. The companion is on stage too while the panes show, so its bells
 * never raise attention, but nothing reaches it until it is focused. Which tab is active is the
 * manager's [SessionManager.activeTabId]; this only says who shares the Stage with it and where.
 */
data class Split(val companionId: String, val activeSide: PaneSide) {
    val companionSide: PaneSide get() = activeSide.other

    /** The split as it is written beside the last active tab (spec C23 with C3's Persistence). */
    val stored: StageSplit get() = StageSplit(companionId, activeSide.stored)

    companion object {
        /** The split a saved document names; whether its companion is still open is the manager's to check. */
        fun of(saved: StageSplit): Split = Split(saved.companionId, PaneSide.of(saved.activeSide))
    }
}

/** Two tabs side by side, resolved from the split: what the Stage lays out on a width that fits them. */
data class Panes(val left: ManagedTab, val right: ManagedTab, val focused: PaneSide) {
    val focusedTab: ManagedTab get() = on(focused)
    val otherTab: ManagedTab get() = on(focused.other)

    fun on(side: PaneSide): ManagedTab = if (side == PaneSide.LEFT) left else right

    /** Which pane holds [id], or null when the tab is in neither. */
    fun sideOf(id: String): PaneSide? = when (id) {
        left.id -> PaneSide.LEFT
        right.id -> PaneSide.RIGHT
        else -> null
    }
}

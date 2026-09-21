package app.berth.android.ui.groups

import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState

/**
 * What a group's card on the overview says of its tabs (spec C8): the count with how many need
 * the user, then up to [SHOWN] tabs, each with the one mark that matters for it. Pure, so the
 * card's words are tested without a screen.
 */
object GroupCards {
    /** How many tabs a card lists; the count line says the rest. */
    const val SHOWN = 3

    /**
     * One tab's line: its title and, at the trailing edge, that it needs the user, its state while
     * it connects or is up, or how long it has been down ([age], with [lastLiveAt] behind it for
     * the longer form a reader hears).
     */
    data class Line(val id: String, val title: String, val state: SessionState, val needsAttention: Boolean, val age: String?, val lastLiveAt: Long?)

    /** `4 tabs`, `1 tab`, `No tabs`; with `· 1 needs you` (or `· 2 need you`) when some do, the ring on the card's swatch saying the same. */
    fun countLine(tabs: List<SessionRecord>): String {
        val count = when (tabs.size) {
            0 -> "No tabs"
            1 -> "1 tab"
            else -> "${tabs.size} tabs"
        }
        val needing = tabs.count { it.needsAttention }
        return when (needing) {
            0 -> count
            1 -> "$count \u00B7 1 needs you"
            else -> "$count \u00B7 $needing need you"
        }
    }

    /**
     * The [SHOWN] tabs the card lists, in strip order except that a tab needing the user comes
     * first: the card exists to say where the user is wanted, and a count of them with none in
     * sight would send the user hunting.
     */
    fun lines(tabs: List<SessionRecord>, now: Long): List<Line> =
        tabs.sortedBy { if (it.needsAttention) 0 else 1 }.take(SHOWN).map { tab ->
            Line(
                id = tab.id,
                title = tab.displayTitle,
                state = tab.state,
                needsAttention = tab.needsAttention,
                age = if (tab.state.isActive) null else shortAge(tab.lastLiveAt, now),
                lastLiveAt = tab.lastLiveAt,
            )
        }

    /**
     * `4m`, `2h`, `3d`, `2w` (`now` under a minute): how long since a tab was last Live, at the
     * width a card's line affords. Null when the tab was never Live, which has nothing to date.
     */
    fun shortAge(since: Long?, now: Long): String? {
        if (since == null) return null
        val s = ((now - since) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> "${s / (7 * 86_400)}w"
        }
    }
}

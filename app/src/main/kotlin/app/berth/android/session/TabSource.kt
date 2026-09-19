package app.berth.android.session

import app.berth.domain.model.SessionRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * What the tab strip needs from a tab (UX spec C3): a stable id and the live record that carries
 * the seven things every tab kind supplies (swatch, monogram, title, state, attention, group,
 * position). Each tab observes its own record, so one tab's title or state change redraws that tab
 * alone. [TerminalSession] is the SSH implementation; tests and later kinds provide their own.
 */
interface TabSource {
    val id: String
    val record: StateFlow<SessionRecord>
}

/**
 * One position in the strip: a tab and the group it sits in. A list of slots is the strip's
 * skeleton; it changes only when tabs open, close, move or change group, never when a title or a
 * state changes, so the strip's structure recomposes as rarely as possible.
 */
data class TabSlot(val tab: TabSource, val groupId: String) {
    val id: String get() = tab.id
}

package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.TabKind
import kotlinx.coroutines.flow.StateFlow

/**
 * What the tab strip needs from a tab (UX spec C3): a stable id and the live record that carries
 * the seven things every tab kind supplies (swatch, monogram, title, state, attention, group,
 * position). Each tab observes its own record, so one tab's title or state change redraws that tab
 * alone. [TerminalSession] is the SSH implementation, [FilesTab] the SFTP one; tests provide their own.
 */
interface TabSource {
    val id: String
    val record: StateFlow<SessionRecord>
}

/**
 * A tab the [SessionManager] owns, whatever it runs: the operations every kind answers so the
 * manager can place, rename, stage and close tabs without asking what they are. The runtime
 * behind the tab (a connection, a browser) is the implementation's business.
 */
interface ManagedTab : TabSource {
    val kind: TabKind

    /** The host the tab is on, as it was when the tab opened. */
    val host: Host
    val state: SessionState

    /** Whether this tab is showing; off-stage events may raise attention, on-stage ones never do. */
    var onStage: Boolean

    /** Applies a group and position to the record without persisting it; the manager writes batches. */
    fun place(workspaceId: String, sortOrder: Int): SessionRecord

    /** Sets the tab's custom title; blank restores the automatic one (spec C3, Rename). */
    fun rename(title: String?)

    /** Clears attention; called when the tab comes on stage. */
    fun markSeen()

    /** Ends the tab's runtime; the record goes to [SessionState.CLOSED]. */
    fun close()
}

/**
 * One position in the strip: a tab and the group it sits in. A list of slots is the strip's
 * skeleton; it changes only when tabs open, close, move or change group, never when a title or a
 * state changes, so the strip's structure recomposes as rarely as possible.
 */
data class TabSlot(val tab: TabSource, val groupId: String) {
    val id: String get() = tab.id
}

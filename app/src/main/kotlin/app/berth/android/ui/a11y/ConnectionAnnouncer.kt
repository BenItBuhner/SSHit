package app.berth.android.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.berth.android.session.SessionFailure
import app.berth.android.session.SessionManager
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState

/**
 * Spec A11: connection state changes are announced through a live region (`prod-web reconnecting`,
 * `prod-web live`). One region for every login the app holds, not only the tab on stage, since
 * the change a reader most needs to hear about is a tab in the background dropping to
 * Reconnecting while they work in another; and it speaks the failure's reason, the same sentence
 * the Stage's panel shows, so a login that failed while the screen was elsewhere is not a silent
 * red dot. Composed once at the root, over the padding, where the one-dp region covers nothing.
 */
@Composable
fun ConnectionAnnouncer(sessions: SessionManager, modifier: Modifier = Modifier) {
    val announcer = remember { Announcer() }
    LaunchedEffect(sessions) {
        val watcher = ConnectionWatcher()
        sessions.records.collect { records ->
            val words = watcher.changes(records) { id -> sessions.sessions.value.firstOrNull { it.id == id }?.failure?.value }
            if (words != null) announcer.announce(words)
        }
    }
    LiveRegion(announcer, modifier)
}

/**
 * Turns the succession of session records into the words for each change of connection state.
 * The first look says nothing (the tabs a restore brings back are where they were left, and a
 * reader opening the app wants the screen, not a roll call); every look after that speaks each
 * tab whose state moved, several at once as one sentence each (`prod-web reconnecting. prod-db
 * reconnecting`, when the network comes back), and nothing when nothing moved.
 */
class ConnectionWatcher {
    private var known: Map<String, SessionState>? = null

    /** The words this look at [records] earns, or null; [failureOf] supplies a failed tab's reason. */
    fun changes(records: List<SessionRecord>, failureOf: (String) -> SessionFailure?): String? {
        val prior = known
        known = records.associate { it.id to it.state }
        if (prior == null) return null
        val spoken = records.mapNotNull { record ->
            val was = prior[record.id]
            if (was == record.state) null else ConnectionSpeech.describe(record.title, was, record.state) { failureOf(record.id) }
        }
        return spoken.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }
}

/** The words for one tab's change of state, in the spec's shape: the tab's title, then the state as one word. */
object ConnectionSpeech {
    /**
     * What to say when [title]'s login moves from [from] (null for a tab just opened) to [to], or
     * null when the move is not one to speak of: a tab appearing detached (restored, or opened
     * from a saved frame) has nothing to report, a closed tab has left the strip, and Idle is the
     * moment before Connecting. Detached is spoken only from an active state, so a Reconnect that
     * fails back to Detached is heard and a tab detached on arrival is not.
     */
    fun describe(title: String, from: SessionState?, to: SessionState, failure: () -> SessionFailure?): String? = when (to) {
        SessionState.CONNECTING -> "$title connecting"
        SessionState.LIVE -> "$title live"
        SessionState.RECONNECTING -> "$title reconnecting"
        SessionState.DETACHED -> if (from?.isActive == true) "$title detached" else null
        SessionState.FAILED -> failure()?.plain?.let { "$title failed. ${it.removeSuffix(".")}" } ?: "$title failed"
        SessionState.IDLE, SessionState.CLOSED -> null
    }
}

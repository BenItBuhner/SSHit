package app.berth.android.ui.a11y

import app.berth.android.session.SessionFailure
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words a reader hears for a login's change of state (spec A11), from the succession of session records. */
class ConnectionSpeechTest {
    private val watcher = ConnectionWatcher()
    private val noFailure: (String) -> SessionFailure? = { null }

    @Test
    fun `the first look says nothing, then every move is spoken in the spec's shape`() {
        assertNull(watcher.changes(listOf(record("prod-web", SessionState.DETACHED), record("prod-db", SessionState.DETACHED)), noFailure))
        assertEquals("prod-web connecting", watcher.changes(listOf(record("prod-web", SessionState.CONNECTING), record("prod-db", SessionState.DETACHED)), noFailure))
        assertEquals("prod-web live", watcher.changes(listOf(record("prod-web", SessionState.LIVE), record("prod-db", SessionState.DETACHED)), noFailure))
        assertNull("the same states again are not a change", watcher.changes(listOf(record("prod-web", SessionState.LIVE), record("prod-db", SessionState.DETACHED)), noFailure))
        assertEquals("prod-web reconnecting", watcher.changes(listOf(record("prod-web", SessionState.RECONNECTING), record("prod-db", SessionState.DETACHED)), noFailure))
        assertEquals("prod-web live", watcher.changes(listOf(record("prod-web", SessionState.LIVE), record("prod-db", SessionState.DETACHED)), noFailure))
        assertEquals("prod-web detached", watcher.changes(listOf(record("prod-web", SessionState.DETACHED), record("prod-db", SessionState.DETACHED)), noFailure))
    }

    @Test
    fun `a failure speaks its reason, the panel's sentence`() {
        watcher.changes(listOf(record("prod-web", SessionState.CONNECTING)), noFailure)
        val words = watcher.changes(listOf(record("prod-web", SessionState.FAILED))) { SessionFailure("The server did not accept the credentials for demo.", "Authentication failed") }
        assertEquals("prod-web failed. The server did not accept the credentials for demo", words)
        watcher.changes(listOf(record("prod-web", SessionState.CONNECTING)), noFailure)
        assertEquals("with no reason known, the failure alone", "prod-web failed", watcher.changes(listOf(record("prod-web", SessionState.FAILED)), noFailure))
    }

    @Test
    fun `several tabs moving at once are one sentence each, and a tab arriving detached or leaving is not spoken`() {
        watcher.changes(listOf(record("prod-web", SessionState.LIVE), record("prod-db", SessionState.LIVE)), noFailure)
        assertEquals(
            "prod-web reconnecting. prod-db reconnecting",
            watcher.changes(listOf(record("prod-web", SessionState.RECONNECTING), record("prod-db", SessionState.RECONNECTING)), noFailure),
        )
        // A tab opened from a saved frame arrives detached; a new login arrives connecting; a closed tab is gone from the list.
        assertEquals(
            "homelab connecting",
            watcher.changes(
                listOf(record("prod-web", SessionState.RECONNECTING), record("old", SessionState.DETACHED), record("homelab", SessionState.CONNECTING)),
                noFailure,
            ),
        )
        assertNull(watcher.changes(listOf(record("old", SessionState.DETACHED), record("homelab", SessionState.CONNECTING)), noFailure))
        assertNull("Closed is the tab leaving, which the strip already shows", watcher.changes(listOf(record("old", SessionState.CLOSED), record("homelab", SessionState.CONNECTING)), noFailure))
    }

    private fun record(title: String, state: SessionState): SessionRecord {
        val host = Host(id = title, name = title, color = SwatchColor.COPPER, monogram = "PW", address = "10.0.0.1", port = 22, user = "demo", auth = AuthMethod.AskEachTime, createdAt = 0L)
        return SessionRecord(id = title, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = state, title = title, createdAt = 0L)
    }
}

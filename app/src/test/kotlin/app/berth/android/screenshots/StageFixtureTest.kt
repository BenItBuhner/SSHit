package app.berth.android.screenshots

import android.app.Application
import app.berth.android.session.TerminalSession
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [StageFixture]'s tabs stand on a network that never comes back, as a phone's do between outages:
 * a tab that reconnects waits out each retry and tries again, so a Stage test that reconnected a tab
 * by mistake fails at its own assertion, not in the fixture's flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageFixtureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: TerminalSession? = null

    @After
    fun tearDown() {
        session?.close()
        scope.cancel()
    }

    @Test
    fun `a fixture tab that cannot reach its host waits out the retry and tries again`() = runBlocking {
        // A port nothing listens on, so each attempt is refused at once rather than left to a timeout.
        val host = Host(id = "closed", name = "closed", color = SwatchColor.SLATE, monogram = "CL", address = "127.0.0.1", port = 1, user = "ben", createdAt = 0)
        val record = SessionRecord(id = "s-closed", workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, createdAt = 0)
        val tab = TerminalSession(record, scope, StageFixture.NoShell) {}.also { session = it }
        tab.connect()
        assertNotNull("the first retry's wait", withTimeoutOrNull(10_000) { tab.retryIn.first { it == 1 } })
        val second = withTimeoutOrNull(10_000) { tab.retryIn.first { it == 2 } }
        assertEquals("the second retry's wait, once the first ran out and the next attempt was refused", 2, second)
    }
}

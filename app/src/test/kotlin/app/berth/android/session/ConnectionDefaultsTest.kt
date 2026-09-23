package app.berth.android.session

import app.berth.domain.model.ConnectionSettings
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Settings › Connection's Keepalive and Reconnect defaults at the session (spec C20): a host that
 * sets neither of its own logs in and retries with what Settings says when the login weighs it,
 * and a host that sets its own keeps it. The reconnect window goes through the real loop, the
 * environment's clock and a connect that fails the way an unreachable network does; the keepalive
 * through the endpoint the loop dials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDefaultsTest {
    private var clock = 1_700_000_000_000L
    private var defaults = ConnectionSettings()

    /** Each read of the defaults, in order: one per attempt the loop makes. */
    private val reads = ArrayList<ConnectionSettings>()

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = throw SshError.ConnectFailed(host.address, host.port, IOException("Network is unreachable"))
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = MutableSharedFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
        override fun now(): Long = clock
        override suspend fun connectionDefaults(): ConnectionSettings = defaults.also { reads += it }
    }

    private val host = Host(id = "h", name = "homelab", color = SwatchColor.MOSS, monogram = "HL", address = "192.168.1.20", user = "ben", createdAt = 0)

    private fun TestScope.retrying(policy: PersistencePolicy): Pair<TerminalSession, List<SessionProblem>> {
        val record = SessionRecord(id = "s", workspaceId = "home", hostId = host.id, hostSnapshot = host.copy(persistence = policy), state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)
        val session = TerminalSession(record, backgroundScope, env) {}
        val problems = ArrayList<SessionProblem>()
        backgroundScope.launch { session.problems.collect { problems += it } }
        session.connect()
        runCurrent()
        assertEquals("a transient failure starts the backoff", SessionState.RECONNECTING, session.state)
        return session to problems
    }

    /** Moves the clock [minutes] on and lets the loop's wait run out, so the next attempt fails and the window is weighed. */
    private fun TestScope.nextAttemptAfter(minutes: Long) {
        clock += minutes * 60_000L
        advanceTimeBy(61_000)
        runCurrent()
    }

    @Test
    fun `a host that inherits retries for as long as the Reconnect default says, not the fifteen minutes it used to`() = runTest {
        defaults = ConnectionSettings(reconnectMinutes = 60)
        val (session, problems) = retrying(PersistencePolicy())
        nextAttemptAfter(16)
        assertEquals("sixteen minutes in, an hour's window is still open", SessionState.RECONNECTING, session.state)
        nextAttemptAfter(45)
        assertEquals(SessionState.DETACHED, session.state)
        assertEquals(listOf<SessionProblem>(SessionProblem.GaveUp(61 * 60_000L)), problems)
    }

    @Test
    fun `a host's own Reconnect wins over the default`() = runTest {
        defaults = ConnectionSettings(reconnectMinutes = 60)
        val (session, problems) = retrying(PersistencePolicy(reconnectMinutes = 5))
        nextAttemptAfter(6)
        assertEquals(SessionState.DETACHED, session.state)
        assertEquals(listOf<SessionProblem>(SessionProblem.GaveUp(6 * 60_000L)), problems)
    }

    @Test
    fun `a default moved while a host retries reaches the next retry it weighs`() = runTest {
        val (session, _) = retrying(PersistencePolicy())
        nextAttemptAfter(10)
        assertEquals("ten minutes into the shipped fifteen", SessionState.RECONNECTING, session.state)
        defaults = ConnectionSettings(reconnectMinutes = 5)
        nextAttemptAfter(0)
        assertEquals(SessionState.DETACHED, session.state)
        assertTrue("every attempt read Settings afresh, the last one after the move", reads.size >= 3 && reads.last().reconnectMinutes == 5)
    }

    @Test
    fun `Forever as the default keeps an inheriting host retrying`() = runTest {
        defaults = ConnectionSettings(reconnectMinutes = 0)
        val (session, problems) = retrying(PersistencePolicy())
        nextAttemptAfter(24 * 60)
        assertEquals(SessionState.RECONNECTING, session.state)
        assertEquals(emptyList<SessionProblem>(), problems)
    }

    @Test
    fun `the endpoint dials with the host's keepalive, or the default's when the host inherits`() {
        val moved = ConnectionSettings(keepaliveSeconds = 60)
        assertEquals(60, sshEndpointFor(host, emptyList(), moved).keepaliveSeconds)
        assertEquals("the shipped default is what every host sent before", 15, sshEndpointFor(host, emptyList(), ConnectionSettings()).keepaliveSeconds)
        assertEquals("a host's Off is its own", 0, sshEndpointFor(host.copy(persistence = PersistencePolicy(keepaliveSeconds = 0)), emptyList(), moved).keepaliveSeconds)
        assertEquals(30, sshEndpointFor(host.copy(persistence = PersistencePolicy(keepaliveSeconds = 30)), emptyList(), moved).keepaliveSeconds)
    }
}

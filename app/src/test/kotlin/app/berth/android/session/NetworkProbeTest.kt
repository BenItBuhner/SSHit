package app.berth.android.session

import android.app.Application
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * The live-socket probe on a network change (vision §4.4, A23; spec C20 Connection), against the
 * local sshd through a relay that can stop passing bytes while both sockets stay open, which is
 * what a handoff leaves behind. A change with the server answering keeps the session Live; one with
 * the socket dead is found within the probe's two seconds, not the keepalive's minutes, and the
 * reconnect follows at once with the marker naming why; output arriving while the probe waits is
 * proof of life, so a busy shell whose reply is late is not dropped. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkProbeTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var proxy: BlackHoleProxy
    private lateinit var session: TerminalSession

    /** The network's changes, as the test fires them; the network coming back, which the reconnect's wait listens for. */
    private val changes = MutableSharedFlow<Unit>()
    private val available = MutableSharedFlow<Unit>()
    private val idleAfter = MutableStateFlow<Long?>(null)

    /** Every `connection lost` reason the session reported, in order. */
    private val drops: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = listOf(SshAuth.Password { sshPassword.toCharArray() })
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = available
        override val networkChanges: Flow<Unit> = changes
        override val idleDetachAfter: Flow<Long?> = idleAfter
        override fun onClipboardText(host: Host, text: String) = Unit
    }

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        proxy = BlackHoleProxy(sshHost, sshPort)
        // The keepalive is set far out, so anything found within the test is the probe's doing.
        val host = Host(
            id = "box", name = "box", color = SwatchColor.TEAL, monogram = "BX", address = "127.0.0.1", port = proxy.port, user = sshUser,
            createdAt = 0, persistence = PersistencePolicy(keepaliveSeconds = 120),
        )
        val record = SessionRecord(id = "s1", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)
        session = TerminalSession(record, scope, env) {}
        scope.launch { session.drops.collect { drops += it } }
        session.connect()
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        await("the prompt") { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(1, proxy.connections)
    }

    @After
    fun tearDown() {
        if (::proxy.isInitialized) {
            // Bytes flow again before the close, else sshj waits its 30 s for a channel-close reply that cannot arrive.
            proxy.swallowToServer = false
            proxy.swallowToClient = false
        }
        if (::session.isInitialized) session.close()
        if (::proxy.isInitialized) proxy.close()
        scope.cancel()
    }

    @Test
    fun `a network change with the server answering keeps the session live`() {
        repeat(3) { fire() }
        Thread.sleep(TerminalSession.PROBE_TIMEOUT_MS + 1_500)
        assertEquals(SessionState.LIVE, session.state)
        assertTrue("no drop: $drops", drops.isEmpty())
        assertFalse(screen().contains("connection lost"))
        assertEquals("the one connection, still", 1, proxy.connections)
        // The shell is still the same one: what is typed comes back.
        session.sendText("echo still-here\r")
        await("the shell answers") { session.emulator.screenText().any { it.trim() == "still-here" } }
    }

    @Test
    fun `a network change with the socket dead is found within the probe's timeout, the marker says why, and the reconnect follows at once`() {
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        val firedAt = System.currentTimeMillis()
        fire()
        await("the drop", TerminalSession.PROBE_TIMEOUT_MS + 6_000) { drops.isNotEmpty() }
        val found = System.currentTimeMillis() - firedAt
        assertEquals(listOf(TerminalSession.PROBE_LOST_REASON), drops.toList())
        assertTrue("found in $found ms, within the probe's ${TerminalSession.PROBE_TIMEOUT_MS} ms and slack", found < TerminalSession.PROBE_TIMEOUT_MS + 6_000)
        assertTrue(screen().contains("connection lost: the network changed"))

        // The network is back: the relay passes bytes again, and the reconnect's first attempt is now.
        proxy.swallowToServer = false
        proxy.swallowToClient = false
        scope.launch { available.emit(Unit) }
        await("the reconnect", 45_000) { session.state == SessionState.LIVE && proxy.connections == 2 }
        await("the reconnected marker") { screen().contains("reconnected") }
        assertEquals("one drop, no more", 1, drops.size)
        session.sendText("echo back-again\r")
        await("the new shell answers") { session.emulator.screenText().any { it.trim() == "back-again" } }

        // A session that is not live has nothing to ask: detached, a change is nothing.
        session.detach()
        assertEquals(SessionState.DETACHED, session.state)
        fire()
        Thread.sleep(500)
        assertEquals(SessionState.DETACHED, session.state)
        assertEquals(1, drops.size)
    }

    @Test
    fun `output arriving while the probe waits is proof of life, so a busy shell whose reply never comes is not dropped`() {
        // A shell printing every 200 ms, then a network whose way to the server is dead: the probe's
        // request never arrives, no reply ever comes, but the output keeps coming.
        session.sendText("while true; do echo tick; sleep 0.2; done\r")
        await("the loop to run") { session.emulator.screenText().count { it.trim() == "tick" } >= 3 }
        proxy.swallowToServer = true
        fire()
        Thread.sleep(TerminalSession.PROBE_TIMEOUT_MS + 1_500)
        assertEquals(SessionState.LIVE, session.state)
        assertTrue("no drop: $drops", drops.isEmpty())
        assertFalse(screen().contains("connection lost"))
        assertEquals(1, proxy.connections)
    }

    private fun fire() {
        scope.launch { changes.emit(Unit) }
    }

    private fun screen(): String = session.emulator.screenText().joinToString("\n")

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

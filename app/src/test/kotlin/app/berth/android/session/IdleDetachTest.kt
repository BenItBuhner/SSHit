package app.berth.android.session

import android.app.Application
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
import java.net.ServerSocket

/**
 * Settings › Connection › Detach idle sessions (spec C20, vision §4.4), against the local sshd: a
 * session off stage with nothing typed and nothing arriving for the span detaches itself and keeps
 * its frame under a marker that says why; typing or output pushes the span out; one on stage or
 * carrying the host's tunnels is left alone and looked at again; Never watches nothing, and so does
 * a Tunnels login. Spans of a second or two here stand for the quarter hour and more the picker
 * offers. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IdleDetachTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ArrayList<TerminalSession>()

    /** The policy, as Settings would hand it: the span in milliseconds, or null for Never. */
    private val idleAfter = MutableStateFlow<Long?>(null)
    private val tunnels = MutableStateFlow<List<Tunnel>>(emptyList())

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = listOf(SshAuth.Password { sshPassword.toCharArray() })
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override val idleDetachAfter: Flow<Long?> = idleAfter
        override fun tunnelsFor(hostId: String): Flow<List<Tunnel>> = tunnels
        override fun onClipboardText(host: Host, text: String) = Unit
    }

    private val host = Host(
        id = "box", name = "box", color = SwatchColor.TEAL, monogram = "BX", address = sshHost, port = sshPort, user = sshUser,
        createdAt = 0, persistence = PersistencePolicy(keepaliveSeconds = 120),
    )

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun `a session off stage with nothing typed and nothing arriving detaches after the span, the marker says why, and the frame stays`() {
        val session = live()
        session.sendText("echo before-idle\r")
        await("the shell answers") { session.emulator.screenText().any { it.trim() == "before-idle" } }
        idleAfter.value = 1_500
        val since = System.currentTimeMillis()
        await("the idle detach") { session.state == SessionState.DETACHED }
        val took = System.currentTimeMillis() - since
        assertTrue("detached after $took ms, not before the span", took >= 1_000)
        val screen = screen(session)
        assertTrue(screen, screen.contains("detached after 1 s idle"))
        assertTrue("the frame is kept", screen.contains("before-idle"))
        assertEquals("the span as the marker says it", "15 min", TerminalSession.idleSpan(15 * 60_000L))
        assertEquals("1 h", TerminalSession.idleSpan(3_600_000L))
        assertEquals("4 h", TerminalSession.idleSpan(4 * 3_600_000L))
        assertEquals("90 s", TerminalSession.idleSpan(90_000L))
    }

    @Test
    fun `typing pushes the span out, and the session detaches only once it is left alone`() {
        val session = live()
        idleAfter.value = 2_000
        // Kept in use for longer than the span: a key every half second.
        val busyUntil = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < busyUntil) {
            session.sendText("x")
            Thread.sleep(500)
            assertEquals(SessionState.LIVE, session.state)
        }
        // Left alone: gone within the span and a little.
        await("the idle detach once left alone", 6_000) { session.state == SessionState.DETACHED }
        assertTrue(screen(session).contains("detached after 2 s idle"))
    }

    @Test
    fun `a session on stage is never detached for idleness, and one taken off stage is looked at again`() {
        val session = live()
        session.onStage = true
        idleAfter.value = 1_000
        Thread.sleep(3_500)
        assertEquals(SessionState.LIVE, session.state)
        assertFalse(screen(session).contains("detached"))
        // Off stage, the watch sees it on its next look, within the span.
        session.onStage = false
        await("the idle detach off stage", 4_000) { session.state == SessionState.DETACHED }
    }

    @Test
    fun `a session carrying the host's tunnels is left alone while a tunnel is up`() {
        val port = ServerSocket(0).use { it.localPort }
        tunnels.value = listOf(Tunnel(id = "t1", hostId = host.id, type = TunnelType.LOCAL, bindPort = port, destinationHost = "127.0.0.1", destinationPort = sshPort))
        val session = live()
        session.carriesTunnels.value = true
        await("the tunnel to be carried") { session.tunnels.value.isNotEmpty() }
        idleAfter.value = 1_000
        Thread.sleep(3_500)
        assertEquals(SessionState.LIVE, session.state)
        // The tunnels taken off the host: nothing to carry, and the watch's next look detaches it.
        tunnels.value = emptyList()
        await("no tunnel carried") { session.tunnels.value.isEmpty() }
        await("the idle detach once the tunnels are gone", 4_000) { session.state == SessionState.DETACHED }
    }

    @Test
    fun `Never watches nothing, and a policy set back to Never mid-span keeps the session`() {
        val session = live()
        idleAfter.value = 1_000
        idleAfter.value = null
        Thread.sleep(3_000)
        assertEquals(SessionState.LIVE, session.state)
        assertFalse(screen(session).contains("detached"))
    }

    @Test
    fun `a Tunnels login has no terminal to be idle in, and is never detached for it`() {
        val session = live(kind = TabKind.Tunnels)
        idleAfter.value = 1_000
        Thread.sleep(3_000)
        assertEquals(SessionState.LIVE, session.state)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun live(kind: TabKind = TabKind.Ssh): TerminalSession {
        val record = SessionRecord(id = "s${sessions.size + 1}", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, kind = kind, sortOrder = 0, createdAt = 0)
        val session = TerminalSession(record, scope, env) {}
        sessions += session
        session.connect()
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        if (kind == TabKind.Ssh) await("the prompt") { session.emulator.screenText().any { it.contains("$") } }
        return session
    }

    private fun screen(session: TerminalSession): String = session.emulator.screenText().joinToString("\n")

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

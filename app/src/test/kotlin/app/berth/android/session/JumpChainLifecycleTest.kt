package app.berth.android.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.ssh.SshSecurity
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The jump chain and the Tunnels tab through the production session stack against two local sshds:
 * the target on `SSH_TEST_PORT` is reached through the bastion on `SSH_TEST_JUMP_PORT`, both
 * answering to the test user. `SessionManager` resolves the chain from the hosts table, each hop
 * raises its own trust prompt and logs in with its own secret, and a Tunnels tab carries the
 * target's forwards over the chain with no shell. Skipped unless the `SSH_TEST_*` variables are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class JumpChainLifecycleTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull() ?: 0
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private lateinit var http: HttpServer
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private val body = "served through the chain"

    /** Every trust prompt raised, in order, as the prompt's host id and the endpoint the key was offered for. */
    private val trusted = CopyOnWriteArrayList<Pair<String, Int>>()

    /** While true the collector answers a trust prompt the way the sheet's button would; a test that wants to look at a hop first turns it off. */
    @Volatile private var autoTrust = true

    private val bastion = Host(
        id = "bastion",
        name = "bastion",
        color = SwatchColor.SLATE,
        monogram = "BA",
        address = sshHost,
        port = jumpPort,
        user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("bastion")),
        createdAt = 0L,
    )

    private val target = Host(
        id = "target",
        name = "target",
        color = SwatchColor.TEAL,
        monogram = "TA",
        address = sshHost,
        port = sshPort,
        user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("target")),
        jumpHostIds = listOf("bastion"),
        createdAt = 0L,
    )

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_JUMP_PORT not set", jumpPort > 0)
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        bg.launch {
            graph.prompts.current.collect { prompt ->
                if (prompt is Prompt.TrustHostKey) {
                    trusted += prompt.host.id to prompt.request.port
                    if (autoTrust) prompt.trust()
                }
            }
        }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(bastion.id), sshPassword.toByteArray())
            graph.secrets.put(AuthResolver.passwordSecretId(target.id), sshPassword.toByteArray())
            graph.hosts.upsert(bastion)
            graph.hosts.upsert(target)
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        if (!::graph.isInitialized) return
        graph.close()
        bg.cancel()
        http.stop(0)
    }

    // ---- the chain -------------------------------------------------------------------------------

    /**
     * A terminal on the target: the bastion's key is asked about first, and while the prompt holds
     * the login the pill's text names the hop; then the target's own key, offered for the target's
     * port, so the chain leaves two known hosts, one per endpoint. The shell that comes up is the
     * target's: sshd's own SSH_CONNECTION says which port it was reached on.
     */
    @Test
    fun `a terminal through the bastion trusts each hop's own key, names the hop while it is made, and runs on the target`(): Unit = runBlocking {
        autoTrust = false
        val session = graph.sessions.open(target)
        assertEquals(TabKind.Ssh, session.kind)
        val first = awaitValue(20_000, "the bastion's trust prompt") { graph.prompts.current.value as? Prompt.TrustHostKey }
        assertEquals("the first key asked about is the bastion's", bastion.id, first.host.id)
        assertEquals(jumpPort, first.request.port)
        assertEquals(SessionState.CONNECTING, session.state)
        await(5_000, "the pill names the hop") { session.via.value == "via bastion" }
        first.trust()

        val second = awaitValue(20_000, "the target's trust prompt") { (graph.prompts.current.value as? Prompt.TrustHostKey)?.takeIf { it.host.id == target.id } }
        assertEquals("the target's key is offered for the target's own port, not the bastion's", sshPort, second.request.port)
        second.trust()

        await(45_000, "session live") { session.state == SessionState.LIVE }
        assertNull("nothing to say about hops once the login is up", session.via.value)
        assertEquals(listOf(bastion.id to jumpPort, target.id to sshPort), trusted.toList())
        val known = graph.knownHosts.items.value
        assertEquals("one known host per endpoint", setOf(jumpPort, sshPort), known.map { it.port }.toSet())

        session.sendText("echo reached:\${SSH_CONNECTION##* }\n")
        await(15_000, "the shell is the target's") { session.emulator.screenText().any { it.contains("reached:$sshPort") } }
        graph.sessions.close(session.id)
    }

    /** The bastion refuses the login: the session fails at the hop and says so, naming the hop rather than the target. */
    @Test
    fun `a wrong secret on the bastion fails the login at the hop, and the failure names it`(): Unit = runBlocking {
        graph.secrets.put(AuthResolver.passwordSecretId(bastion.id), "not-the-password".toByteArray())
        val session = graph.sessions.open(target)
        await(45_000, "session failed") { session.state == SessionState.FAILED }
        val failure = session.failure.value!!
        assertEquals("bastion (jump host) did not accept the credentials for $sshUser.", failure.plain)
        assertTrue("the raw error names the hop: ${failure.raw}", failure.raw.startsWith("Jump host $sshHost:$jumpPort (hop 1 of 1)"))
        assertEquals("the failure carries the hop, so its action opens the hop's editor rather than the target's", FailedHop(bastion.id, bastion.name), failure.hop)
        assertEquals("only the bastion's key was ever asked about", listOf(bastion.id to jumpPort), trusted.toList())
        assertNull(session.via.value)
    }

    // ---- the Tunnels tab -------------------------------------------------------------------------

    /**
     * A Tunnels tab on the target, through the bastion: a login with no shell that is Live while
     * the transport holds, carries the target's forward and counts what it moves, is never the
     * terminal on stage, and counts as a login for the Sessions notification. Terminal from its
     * menu opens a shell on the host after it; the shell, through the same bastion, then drops
     * every no-pty login of the user, which is both tabs' chains and the tunnel's own login. Both
     * reconnect through the bastion, the forward comes back on the same port with fresh counters,
     * and the Tunnels tab ends up the carrier whichever came up first.
     */
    @Test
    fun `a Tunnels tab through the bastion carries the forward with no shell, counts its bytes, and reconnects after a drop`(): Unit = runBlocking {
        val port = freePort()
        val tunnel = Tunnel("t-web", target.id, TunnelType.LOCAL, "127.0.0.1", port, "127.0.0.1", http.address.port, enabled = true)
        graph.tunnels.upsert(tunnel)

        val tunnels = graph.sessions.openTunnels(target)
        assertEquals(TabKind.Tunnels, tunnels.kind)
        assertTrue(tunnels.tunnelsOnly)
        assertEquals("Tunnels \u00B7 target", tunnels.record.value.title)
        await(45_000, "tunnels tab live") { tunnels.state == SessionState.LIVE }
        assertEquals(listOf(bastion.id to jumpPort, target.id to sshPort), trusted.toList())
        val up = awaitValue(15_000, "forward up") { tunnels.tunnels.value[tunnel.id] as? TunnelStatus.Up }
        assertEquals(port, up.localPort)
        assertTrue(tunnels.carriesTunnels.value)

        assertEquals(body, get("http://127.0.0.1:$port/"))
        val traffic = up.traffic!!
        await(5_000, "the connection is counted") { traffic.connections == 1 && traffic.openConnections == 0 }
        assertTrue("the request went up: ${traffic.bytesUp}", traffic.bytesUp > 0)
        assertTrue("the response came down: ${traffic.bytesDown}", traffic.bytesDown >= body.length)
        assertEquals(body, get("http://127.0.0.1:$port/"))
        await(5_000, "both connections counted") { traffic.connections == 2 }

        // On stage it is the active tab, yet not a terminal: nothing to type into, no Deck.
        assertEquals(tunnels.id, graph.sessions.activeTabId.value)
        assertNull(graph.sessions.activeSession.value)
        assertTrue("its screen stays blank", tunnels.emulator.screenText().all { it.isBlank() })
        // The notification counts it as a login holding a socket, under the tab's title.
        await(5_000, "the summary counts the login") { graph.notifier.summary.value.active == 1 }
        assertEquals(listOf("Tunnels \u00B7 target"), graph.notifier.summary.value.lines.map { it.title })
        assertEquals(1, graph.notifier.summary.value.tunnels)

        // Terminal from the tab's menu: a shell on the host, directly after it, through the same bastion.
        val terminal = graph.sessions.openTerminalFor(tunnels.id)!!
        assertEquals(TabKind.Ssh, terminal.kind)
        assertNotEquals(tunnels.id, terminal.id)
        await(5_000, "the strip shows the terminal after the Tunnels tab") { graph.sessions.tabs.value.map { it.id } == listOf(tunnels.id, terminal.id) }
        await(45_000, "terminal live") { terminal.state == SessionState.LIVE }
        assertEquals(terminal, graph.sessions.activeSession.value)
        delay(500)
        assertTrue("a Live Tunnels tab keeps the forwards", tunnels.carriesTunnels.value)
        assertFalse(terminal.carriesTunnels.value)
        assertTrue(terminal.tunnels.value.isEmpty())
        assertEquals(2, graph.notifier.summary.value.active)

        // The shell drops every login of the user that has no pty: both hops through the bastion and the tunnel's own login on the target.
        terminal.sendText("pkill -f '^sshd: '\"\$USER\"' *\$'\n")
        await(15_000, "tunnels tab drops") { tunnels.state != SessionState.LIVE }
        await(60_000, "tunnels tab live again") { tunnels.state == SessionState.LIVE }
        await(60_000, "terminal live again") { terminal.state == SessionState.LIVE }
        val again = awaitValue(20_000, "forward up again on the Tunnels tab") { tunnels.tunnels.value[tunnel.id] as? TunnelStatus.Up }
        assertEquals(port, again.localPort)
        assertEquals(body, get("http://127.0.0.1:$port/"))
        await(5_000, "fresh counters after the reconnect") { again.traffic!!.connections == 1 }
        await(5_000, "the terminal gives the role back") { tunnels.carriesTunnels.value && !terminal.carriesTunnels.value }
        assertTrue("the chain was made again for each login", trusted.size >= 2)

        graph.sessions.close(terminal.id)
        graph.sessions.close(tunnels.id)
        await(10_000, "statuses cleared") { graph.sessions.tunnelStatuses.value.isEmpty() }
        await(5_000, "no login left") { graph.notifier.summary.value.active == 0 }
    }

    /** Connect on a host marked tunnels only opens a Tunnels tab; on one that is not, a terminal. */
    @Test
    fun `connect opens a Tunnels tab for a tunnels-only host and a terminal otherwise`(): Unit = runBlocking {
        val only = graph.sessions.connect(target.copy(tunnelsOnly = true))
        assertEquals(TabKind.Tunnels, only.kind)
        await(45_000, "tunnels tab live") { only.state == SessionState.LIVE }
        val shell = graph.sessions.connect(target)
        assertEquals(TabKind.Ssh, shell.kind)
        await(45_000, "terminal live") { shell.state == SessionState.LIVE }
        assertEquals(shell, graph.sessions.activeSession.value)
    }

    /**
     * The process died with a Tunnels tab open. The relaunch restores it as a Tunnels tab from its
     * record, detached, with no frame to show and none saved, and Reconnect brings the login back
     * through the bastion and the forward with it.
     */
    @Test
    fun `a Tunnels record restores as a Tunnels tab and reconnects through the chain`(): Unit = runBlocking {
        val port = freePort()
        val tunnel = Tunnel("t-restored", target.id, TunnelType.LOCAL, "127.0.0.1", port, "127.0.0.1", http.address.port, enabled = true)
        graph.tunnels.upsert(tunnel)
        val fresh = TestGraph(ApplicationProvider.getApplicationContext())
        try {
            fresh.secrets.put(AuthResolver.passwordSecretId(bastion.id), sshPassword.toByteArray())
            fresh.secrets.put(AuthResolver.passwordSecretId(target.id), sshPassword.toByteArray())
            fresh.hosts.upsert(bastion)
            fresh.hosts.upsert(target)
            fresh.tunnels.upsert(tunnel)
            fresh.sessionRecords.upsert(
                SessionRecord(
                    id = "s-tunnels",
                    workspaceId = fresh.workspaces.ensureDefault().id,
                    hostId = target.id,
                    hostSnapshot = target,
                    state = SessionState.DETACHED,
                    layer = PersistenceLayer.LOCAL_FRAME,
                    title = "Tunnels \u00B7 target",
                    sortOrder = 0,
                    createdAt = 1L,
                    lastLiveAt = 2L,
                    kind = TabKind.Tunnels,
                ),
            )
            bg.launch { fresh.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
            fresh.sessions.restore()
            val restored = fresh.sessions.get("s-tunnels")!!
            assertEquals(TabKind.Tunnels, restored.kind)
            assertTrue(restored.tunnelsOnly)
            assertEquals(SessionState.DETACHED, restored.state)
            assertNull("a Tunnels tab has no frame", fresh.sessionRecords.frames["s-tunnels"])
            assertTrue(restored.emulator.screenText().all { it.isBlank() })

            fresh.sessions.reconnect("s-tunnels")
            await(45_000, "restored tab live") { restored.state == SessionState.LIVE }
            await(15_000, "forward up") { restored.tunnels.value[tunnel.id] is TunnelStatus.Up }
            assertEquals(body, get("http://127.0.0.1:$port/"))
            assertEquals(setOf(jumpPort, sshPort), fresh.knownHosts.items.value.map { it.port }.toSet())
        } finally {
            fresh.close()
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private suspend fun <T : Any> awaitValue(timeoutMs: Long, what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private suspend fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        awaitValue(timeoutMs, what) { if (condition()) Unit else null }
    }

    /** One request over one connection: keep-alive would leave it pooled, and open, past the response. */
    private fun get(url: String): String {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Connection", "close")
        return conn.getInputStream().bufferedReader().readText()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

package app.berth.android.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Tunnels through the production session stack against the local sshd: `SessionManager` elects
 * the carrier, `TerminalSession` binds the forwards on its connection, and an HTTP server in this
 * process is what sits at the far end. Skipped unless the `SSH_TEST_*` variables are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TunnelLifecycleTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private lateinit var http: HttpServer
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private val body = "hello from behind the tunnel"

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
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
        // The first connection raises trust-on-first-use; answer it the way the sheet's button would.
        bg.launch {
            graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() }
        }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        if (!::graph.isInitialized) return
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        bg.cancel()
        http.stop(0)
    }

    private val box = Host(
        id = "tunnel-box",
        name = "Tunnel box",
        color = SwatchColor.TEAL,
        monogram = "TB",
        address = sshHost,
        port = sshPort,
        user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("tunnel-box")),
        createdAt = 0L,
    )

    @Test
    fun `local forward serves HTTP, survives a dropped connection and releases its port on close`() = runBlocking {
        val port = freePort()
        val tunnel = Tunnel("t-local", box.id, TunnelType.LOCAL, "127.0.0.1", port, "127.0.0.1", http.address.port, enabled = true)
        graph.tunnels.upsert(tunnel)

        val session = graph.sessions.open(box)
        await(45_000, "session live") { session.state == SessionState.LIVE }
        val up = awaitValue(15_000, "tunnel up") { graph.sessions.tunnelStatuses.value[tunnel.id] as? TunnelStatus.Up }
        assertEquals(port, up.localPort)
        assertEquals(body, get("http://127.0.0.1:$port/"))

        // Kill the server-side session process: the transport dies under the live session.
        session.sendText("kill -9 \$PPID\n")
        await(15_000, "session drops") { session.state != SessionState.LIVE }
        await(45_000, "session live again") { session.state == SessionState.LIVE }
        val upAgain = awaitValue(15_000, "tunnel up after reconnect") { graph.sessions.tunnelStatuses.value[tunnel.id] as? TunnelStatus.Up }
        assertEquals(port, upAgain.localPort)
        assertEquals(body, get("http://127.0.0.1:$port/"))

        // Disabling closes the listener; enabling binds it again on the same connection.
        graph.tunnels.setEnabled(tunnel.id, false)
        await(10_000, "tunnel stopped") { tunnel.id !in graph.sessions.tunnelStatuses.value }
        assertTrue("port should be closed", refused(port))
        graph.tunnels.setEnabled(tunnel.id, true)
        await(15_000, "tunnel back") { graph.sessions.tunnelStatuses.value[tunnel.id] is TunnelStatus.Up }
        assertEquals(body, get("http://127.0.0.1:$port/"))

        graph.sessions.close(session.id)
        await(10_000, "statuses cleared") { graph.sessions.tunnelStatuses.value.isEmpty() }
        assertTrue("port should be free after close", refused(port))
    }

    @Test
    fun `dynamic forward answers SOCKS5 and a fixed port can be reached through it`() = runBlocking {
        val port = freePort()
        val socks = Tunnel("t-socks", box.id, TunnelType.DYNAMIC, "127.0.0.1", port, "", 0, enabled = true)
        graph.tunnels.upsert(socks)
        val session = graph.sessions.open(box)
        await(45_000, "session live") { session.state == SessionState.LIVE }
        await(15_000, "socks up") { graph.sessions.tunnelStatuses.value[socks.id] is TunnelStatus.Up }

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val conn = URL("http://127.0.0.1:${http.address.port}/").openConnection(proxy)
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        assertEquals(body, conn.getInputStream().bufferedReader().readText())
        graph.sessions.close(session.id)
    }

    @Test
    fun `a second session to the same host does not rebind, and takes over when the carrier closes`() = runBlocking {
        val port = freePort()
        val tunnel = Tunnel("t-shared", box.id, TunnelType.LOCAL, "127.0.0.1", port, "127.0.0.1", http.address.port, enabled = true)
        graph.tunnels.upsert(tunnel)

        val first = graph.sessions.open(box)
        await(45_000, "first live") { first.state == SessionState.LIVE }
        await(15_000, "tunnel up on first") { first.tunnels.value[tunnel.id] is TunnelStatus.Up }

        val second = graph.sessions.open(box)
        await(45_000, "second live") { second.state == SessionState.LIVE }
        delay(1_000)
        assertTrue("first stays the carrier while live", first.carriesTunnels.value)
        assertTrue("second does not carry", !second.carriesTunnels.value && second.tunnels.value.isEmpty())
        assertEquals(body, get("http://127.0.0.1:$port/"))

        graph.sessions.close(first.id)
        await(15_000, "second takes over") { second.tunnels.value[tunnel.id] is TunnelStatus.Up }
        assertNotEquals(first.id, second.id)
        assertEquals(body, get("http://127.0.0.1:$port/"))
        graph.sessions.close(second.id)
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

    private fun get(url: String): String {
        val conn = URL(url).openConnection()
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn.getInputStream().bufferedReader().readText()
    }

    private fun refused(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        false
    } catch (e: ConnectException) {
        true
    } catch (e: java.io.IOException) {
        true
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

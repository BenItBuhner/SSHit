package app.berth.android.session

import android.app.Application
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the battery-optimisation explainer is owed its one showing (vision §4.4): the manager
 * against the sshd through a relay that can cut or black-hole the socket, with the process
 * lifecycle moved on and off the screen. A connection lost while the app is away is the cue; one
 * lost on screen is not (the user saw it), one the probe found dead after a network change is not
 * (the network moved, not the OS), and once the explainer has been raised, from the shell or from
 * Settings, nothing is ever due again. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BatteryExplainerTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private lateinit var proxy: BlackHoleProxy
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private val connectivity by lazy { shadowOf(ApplicationProvider.getApplicationContext<Application>().getSystemService(ConnectivityManager::class.java)) }

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        proxy = BlackHoleProxy(sshHost, sshPort)
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId("box"), sshPassword.toByteArray())
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        if (::proxy.isInitialized) {
            // Bytes flow again before the close, else sshj waits its 30 s for a channel-close reply that cannot arrive.
            proxy.swallowToServer = false
            proxy.swallowToClient = false
        }
        bg.cancel()
        if (::graph.isInitialized) graph.close()
        if (::proxy.isInitialized) proxy.close()
    }

    @Test
    fun `a connection lost with the app away makes the explainer due, raised it is spent, and a second loss asks nothing`() {
        val session = live()
        graph.process.stop()
        assertFalse("nothing is due before anything is lost", graph.sessions.batteryExplainerDue.value)
        proxy.cutAll()
        await("the explainer to fall due") { graph.sessions.batteryExplainerDue.value }
        assertTrue("the login is on its way back, not gone", session.state == SessionState.RECONNECTING || session.state == SessionState.LIVE)

        // On the return the shell raises it and says so: the flag clears and the setting is written.
        graph.process.start()
        graph.viewModel.batteryExplainerRaised()
        await("the flag to clear") { !graph.sessions.batteryExplainerDue.value }
        await("the setting to be written") { runBlocking { graph.settings.connectionSettings.first().batteryExplained } }

        // The login comes back through the relay on the backoff's first second; lost again with the app away, nothing is due any more.
        await("the reconnect", 45_000) { session.state == SessionState.LIVE && proxy.connections == 2 }
        graph.process.stop()
        proxy.cutAll()
        await("the second drop") { session.state != SessionState.LIVE }
        Thread.sleep(500)
        assertFalse("explained once is explained", graph.sessions.batteryExplainerDue.value)
    }

    @Test
    fun `a connection lost with the app on screen is no cue, the user saw it happen`() {
        val session = live()
        graph.process.start()
        proxy.cutAll()
        await("the drop") { session.state != SessionState.LIVE }
        assertTrue(screen(session).contains("connection lost"))
        Thread.sleep(500)
        assertFalse(graph.sessions.batteryExplainerDue.value)
    }

    @Test
    fun `a connection the probe found dead after a network change is no cue, the network moved and not the OS`() {
        val session = live()
        graph.process.stop()
        // A live login listens for the network's changes, so the manager holds the one default callback.
        await("the network callback") { connectivity.networkCallbacks.isNotEmpty() }
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        val network = ApplicationProvider.getApplicationContext<Application>().getSystemService(ConnectivityManager::class.java).activeNetwork!!
        connectivity.networkCallbacks.toList().forEach { it.onLost(network) }
        await("the probe to find the socket dead", TerminalSession.PROBE_TIMEOUT_MS + 6_000) { session.state != SessionState.LIVE }
        assertTrue(screen(session).contains("connection lost: ${TerminalSession.PROBE_LOST_REASON}"))
        Thread.sleep(500)
        assertFalse("the probe's own reason is not the OS sleeping the app", graph.sessions.batteryExplainerDue.value)
    }

    @Test
    fun `once explained from Settings, a loss with the app away is no cue`() {
        runBlocking { graph.settings.updateConnectionSettings { it.copy(batteryExplained = true) } }
        await("the manager to read the setting") { runBlocking { graph.viewModel.connectionSettings.first().batteryExplained } }
        val session = live()
        graph.process.stop()
        proxy.cutAll()
        await("the drop") { session.state != SessionState.LIVE }
        Thread.sleep(500)
        assertFalse(graph.sessions.batteryExplainerDue.value)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A live shell on the sshd through the relay, its keepalive set far out so nothing but the test moves it. */
    private fun live(): TerminalSession {
        val box = Host(
            id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = "127.0.0.1", port = proxy.port, user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = 20L, persistence = PersistencePolicy(keepaliveSeconds = 120),
        )
        runBlocking { graph.hosts.upsert(box) }
        val session = runBlocking { graph.sessions.open(box) }
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        await("the prompt to show") { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(1, proxy.connections)
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

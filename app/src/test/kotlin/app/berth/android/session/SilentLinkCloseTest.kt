package app.berth.android.session

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.ssh.SshConnection
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Closing and detaching a tab over a link gone silent, no FIN and no reset, before the keepalive
 * has noticed: the manager against the sshd through a relay that drops every byte both ways. Close
 * and Detach run on the main thread, and a shell's channel close, or a remote forward's cancel,
 * waits for a reply such a link never carries, sshj's 30 s. The tab goes as the call returns,
 * within a second, and the connection closes behind it (spec C3, Closing); a local forward's
 * listener closes before the call returns, its port free a moment later rather than after the
 * connection's grace, so the next tab can bind it. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SilentLinkCloseTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private lateinit var proxy: BlackHoleProxy
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        proxy = BlackHoleProxy(sshHost, sshPort)
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        bg.cancel()
        if (::graph.isInitialized) graph.close()
        if (::proxy.isInitialized) proxy.close()
    }

    /** The keepalive is set far out, so the link is silent and the login still looks up when the tab goes. */
    private val box get() = Host(
        id = "silent-box", name = "Silent box", color = SwatchColor.TEAL, monogram = "SB", address = "127.0.0.1", port = proxy.port, user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("silent-box")), createdAt = 0L, persistence = PersistencePolicy(keepaliveSeconds = 120),
    )

    @Test
    fun `closing a tab over a link gone silent gives the main thread back at once, and the connection closes behind it`() {
        val session = live()
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        onMainThread("Close") { graph.sessions.close(session.id) }
        assertEquals(SessionState.CLOSED, session.state)
        assertTrue("the tab is gone as the Close returns", graph.sessions.tab(session.id) == null)
        await("the socket to close behind the tab", CLOSED_BEHIND_MS) { proxy.openLinks == 0 }
    }

    @Test
    fun `detaching a tab over a link gone silent gives the main thread back at once and keeps its frame`() {
        val session = live()
        session.sendText("echo kept-in-the-frame\r")
        await("the echo") { session.emulator.screenText().any { it.trim() == "kept-in-the-frame" } }
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        onMainThread("Detach") { graph.sessions.detach(session.id) }
        assertEquals(SessionState.DETACHED, session.state)
        val frame = session.emulator.screenText()
        assertTrue("the frame keeps what the shell drew", frame.any { it.trim() == "kept-in-the-frame" })
        assertTrue("and says it was detached", frame.any { it.contains("detached") })
        await("the socket to close behind the tab", CLOSED_BEHIND_MS) { proxy.openLinks == 0 }
    }

    @Test
    fun `a remote forward over a link gone silent is cancelled behind the Close, and a local one frees its port at once`() {
        val localPort = freePort()
        val remotePort = freePort()
        val local = Tunnel("t-local", box.id, TunnelType.LOCAL, "127.0.0.1", localPort, "127.0.0.1", freePort(), enabled = true)
        val remote = Tunnel("t-remote", box.id, TunnelType.REMOTE, "127.0.0.1", remotePort, "127.0.0.1", freePort(), enabled = true)
        runBlocking {
            graph.tunnels.upsert(local)
            graph.tunnels.upsert(remote)
        }
        val session = live()
        await("both forwards up", 15_000) { graph.sessions.tunnelStatuses.value.let { it[local.id] is TunnelStatus.Up && it[remote.id] is TunnelStatus.Up } }
        assertFalse("the device listens on the local forward's port", refused(localPort))
        assertFalse("the server listens on the remote forward's port", refused(remotePort))
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        onMainThread("Close") { graph.sessions.close(session.id) }
        await("the local forward's port to be free", LOCAL_FREED_MS) { refused(localPort) }
        await("the statuses to clear") { graph.sessions.tunnelStatuses.value.isEmpty() }
        await("the socket to close behind the tab", CLOSED_BEHIND_MS) { proxy.openLinks == 0 }
        await("the server to stop listening on the remote forward's port") { refused(remotePort) }
    }

    /**
     * Times [action] on the main thread, where the Close and Detach actions call it. A watchdog takes
     * the main thread's stack [DUMP_AFTER_MS] in, a thread dump at an ANR's five seconds, then cuts the
     * relay so a wait for a reply ends there rather than running its 30 s; a failure carries the stack.
     */
    private fun onMainThread(what: String, action: () -> Unit) {
        assertTrue("the test body runs on the main thread", Looper.getMainLooper().isCurrentThread)
        val main = Thread.currentThread()
        val returned = CountDownLatch(1)
        var stack: String? = null
        val watchdog = thread(name = "silent-close-watchdog", isDaemon = true) {
            if (!returned.await(DUMP_AFTER_MS, TimeUnit.MILLISECONDS)) {
                stack = dump(main)
                proxy.cutAll()
            }
        }
        val started = System.nanoTime()
        try {
            action()
        } finally {
            returned.countDown()
        }
        val took = (System.nanoTime() - started) / 1_000_000
        watchdog.join()
        assertTrue("$what held the main thread $took ms" + (stack?.let { "; $DUMP_AFTER_MS ms in it stood at\n$it" } ?: ""), took < MAIN_THREAD_BUDGET_MS)
    }

    /** [thread]'s stack as a thread dump prints it, down to this class's frame. */
    private fun dump(thread: Thread): String {
        val frames = thread.stackTrace
        val end = frames.indexOfFirst { it.className == SilentLinkCloseTest::class.java.name }.let { if (it < 0) frames.size else it + 1 }
        return "\"${thread.name}\" ${thread.state}\n" + frames.take(end).joinToString("\n") { "\tat $it" }
    }

    private fun live(): TerminalSession {
        val session = runBlocking { graph.sessions.open(box) }
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        await("the prompt to show") { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(1, proxy.connections)
        return session
    }

    private fun refused(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000) }
        false
    } catch (_: IOException) {
        true
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val MAIN_THREAD_BUDGET_MS = 1_000L
        const val DUMP_AFTER_MS = 5_000L

        /** The channels' grace, then the disconnect's, and slack: the socket is shut well inside the 30 s reply wait. */
        const val CLOSED_BEHIND_MS = 2 * SshConnection.CLOSE_GRACE_MS + 3_000

        /** The listener's socket is released once its accepting thread wakes; a port held until the channels' grace had run would miss this. */
        const val LOCAL_FREED_MS = SshConnection.CLOSE_GRACE_MS / 2
    }
}

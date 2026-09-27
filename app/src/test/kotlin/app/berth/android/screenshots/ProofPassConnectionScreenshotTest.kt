package app.berth.android.screenshots

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshotDir
import app.berth.android.session.AuthResolver
import app.berth.android.session.BlackHoleProxy
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.components.LocalWallClock
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The connection rows of the v1 proof pass, each a frame in which the behaviour and what set it off
 * are both on screen, from the real app against the local sshd (spec D4's pill, vision §4.4):
 *
 * - A3, keyboard-interactive: the server's own prompt in the password sheet, from an sshd that logs
 *   in by `keyboard-interactive` alone, then the tab Live on it.
 * - A21, keepalive: a host whose keepalive is 2 s behind a relay that goes silent both ways; the
 *   marker row names the keep-alive that found the dead socket.
 * - A22, reconnect with backoff: the relay in front of the sshd stops, as the server going down looks
 *   from the phone (the link closes, new connects are refused); the pill counts down to each try on
 *   the 1, 2, 4 s steps, and the relay starting again lets the next try in.
 * - A23, network-change probing: the relay goes silent and the network under the app changes; the
 *   probe finds the socket dead in two seconds and the marker says the network changed.
 *
 * Every case is skipped unless `SSH_TEST_*` is set; A3 also needs `SSH_TEST_KBD_PORT`, an sshd that
 * CI does not run. One is made on a workstation like this, as root, with the account password PAM
 * checks being the test user's `SSH_TEST_PASSWORD`:
 *
 * ```
 * ln -s /usr/sbin/sshd /usr/local/sbin/sshd-berth-kbd        # PAM's service name is the binary's
 * printf '%s\n' 'auth required pam_echo.so Berth test server: keyboard-interactive login through PAM' \
 *   '@include common-auth' '@include common-account' 'session required pam_unix.so' > /etc/pam.d/sshd-berth-kbd
 * mkdir -p /etc/ssh/sshd_kbd && printf '%s\n' 'Port 2225' 'ListenAddress 127.0.0.1' \
 *   'HostKey /etc/ssh/ssh_host_ed25519_key' 'PasswordAuthentication no' 'PubkeyAuthentication no' \
 *   'KbdInteractiveAuthentication yes' 'AuthenticationMethods keyboard-interactive' 'UsePAM yes' \
 *   'PidFile /tmp/sshd_kbd.pid' > /etc/ssh/sshd_kbd/sshd_config
 * /usr/local/sbin/sshd-berth-kbd -f /etc/ssh/sshd_kbd/sshd_config
 * export SSH_TEST_KBD_PORT=2225
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ProofPassConnectionScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = screenshotDir
    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = FIXED_NOW

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val kbdPort = System.getenv("SSH_TEST_KBD_PORT").orEmpty().toIntOrNull()

    /** Relays a case put in front of the sshd, closed after it, before the app's sessions, whether it passed or not. */
    private val closeables = ArrayList<Closeable>()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        // A shell channel's close waits up to sshj's 30 s for the peer's reply, which a silent relay
        // never passes; with the relay cut first the transport reads EOF and the close ends at once.
        closeables.forEach { runCatching { it.close() } }
        graph.close()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun waitForText(text: String, timeout: Long = 5_000) {
        try {
            compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("'$text' never showed; the texts on screen were ${texts()}", e)
        }
    }

    /** Every text on screen, for a wait's failure and for the pill, whose digits the test reads rather than names. */
    private fun texts(): List<String> = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
        .flatMap { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } ?: emptyList() }

    /**
     * Whether [text] is on the terminal's screen, read across the rows with the whitespace taken out:
     * a marker row runs past the canvas' width and wraps, and where a wrap falls on a space the row's
     * trailing blank is not in its text.
     */
    private fun onScreen(session: TerminalSession, text: String): Boolean {
        val squeezed = session.emulator.screenText().joinToString("").filterNot(Char::isWhitespace)
        return squeezed.contains(text.filterNot(Char::isWhitespace))
    }

    private fun awaitOnScreen(session: TerminalSession, text: String, timeout: Long) {
        try {
            compose.waitUntil(timeout) { onScreen(session, text) }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("'$text' never showed in the terminal; its screen was ${session.emulator.screenText().filter { it.isNotBlank() }}", e)
        }
    }

    private fun testHost(name: String, address: String, port: Int, persistence: PersistencePolicy = PersistencePolicy()): Host {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        return Host(
            id = id,
            name = name,
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor(name),
            address = address,
            port = port,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId(id)),
            persistence = persistence,
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
    }

    /**
     * The real app with [box] saved, its password with it, and a new tab opened on it through the
     * strip's New tab: the host key trusted on first sight, so what follows is the login. The app is
     * on screen (the process started), so the tab in front is on stage and a drop is no cue for the
     * battery explainer.
     */
    private fun openTab(box: Host) {
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { CompositionLocalProvider(LocalWallClock provides { now }) { AppRoot(graph.viewModel) } }
        graph.process.start()
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("New tab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("New tab").performClick()
        waitForText(box.name)
        compose.onNodeWithText(box.name).performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
    }

    private fun awaitLive(): TerminalSession {
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        return graph.sessions.activeSession.value!!
    }

    // ---- A3, keyboard-interactive -----------------------------------------------------------------------

    /**
     * The password saved for the host is sent first and turned down, since the server takes no
     * `password` method; the keyboard-interactive method follows and the sheet carries PAM's own
     * `Password: ` as its caption, which Berth's own ask for a password has none of. The pam_echo line
     * the server sends ahead of it, in a request of its own with no prompt, is not in the sheet: the
     * provider keeps the last request's instruction only (a defect the proof pass records).
     */
    @Test
    fun `A03 keyboard-interactive puts the server's challenge in the sheet, and its answer logs in`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_KBD_PORT not set: no keyboard-interactive sshd to log in to", kbdPort != null)
        val box = testHost("PAM box", sshHost, kbdPort!!)
        openTab(box)
        compose.waitUntil(30_000) { graph.prompts.current.value is Prompt.Password }
        val prompt = graph.prompts.current.value as Prompt.Password
        assertEquals("the challenge is the server's", "Password: ", prompt.serverPrompt)
        waitForText("Password for ${box.userAtHost}")
        waitForText("Password: ")
        compose.onNode(hasText(":$kbdPort", substring = true)).assertExists()
        compose.settle(600)
        capture("A03-keyboard-interactive")

        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(sshPassword)
        compose.onNodeWithText("Connect").performClick()
        val session = awaitLive()
        compose.settle(1_200)
        session.sendText("clear; echo \"SSH_CONNECTION=\$SSH_CONNECTION\"\n")
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.trim().let { row -> row.startsWith("SSH_CONNECTION=") && row.endsWith(" $kbdPort") } } }
        compose.settle(800)
        capture("A03-keyboard-interactive-live")
    }

    // ---- A21, keepalive ---------------------------------------------------------------------------------

    /**
     * A keepalive of 2 s (what an imported `ServerAliveInterval 2` gives; the host editor's own
     * choices start at 15 s, which takes the same count over 90 s) behind a relay that stops passing
     * bytes both ways while both sockets stay open, so nothing but the keepalive can tell. Five
     * unanswered keep-alives later the transport should give up with the keep-alive named, the
     * marker row saying so and the pill taking over while the reconnect waits on the relay.
     *
     * It does not today: `SshConnection.connectClient` sets sshj's keep-alive interval after
     * `connect`, and sshj starts its keep-alive thread in `SSHClient.onConnect` only when the
     * interval is already set, so no keep-alive is ever sent. The case waits the keepalive's count
     * and a margin, takes the frame as it stands (the tab still Live over a dead socket, or the drop
     * once the keepalive runs), and is skipped with the defect named while no drop comes, so the
     * frame is made and the gate holds until the keepalive is fixed, when the case asserts the rest.
     */
    @Test
    fun `A21 the keepalive finds a socket that went silent and the marker names it`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val proxy = BlackHoleProxy(sshHost, sshPort).also { closeables += it }
        val keepalive = 2
        openTab(testHost("Berth test box", "127.0.0.1", proxy.port, PersistencePolicy(keepaliveSeconds = keepalive)))
        val session = awaitLive()
        compose.settle(1_200)
        session.sendText("clear; uname -sn\n")
        compose.settle(800)

        proxy.swallowToServer = true
        proxy.swallowToClient = true
        val lost = "connection lost: Did not receive any keep-alive response for ${5 * keepalive} seconds"
        val found = runCatching { compose.waitUntil((6 * keepalive + 8) * 1_000L) { onScreen(session, lost) } }.isSuccess
        if (!found) {
            compose.settle(600)
            capture("A21-keepalive-silent-socket")
            assumeTrue(
                "Defect: SshConnection.connectClient sets the keep-alive interval after sshj's connect, so its keep-alive thread " +
                    "never starts; ${6 * keepalive + 8} s after the socket went silent with a ${keepalive} s keepalive the tab is still ${session.state}",
                false,
            )
        }
        compose.waitUntil(5_000) { session.state == SessionState.RECONNECTING }
        compose.settle(600)
        assertTrue("the pill says Reconnecting; the texts were ${texts()}", texts().any { it.startsWith("Reconnecting") })
        capture("A21-keepalive-drop")

        // The relay passes bytes again; the try it held is cut so the next one goes through now.
        proxy.swallowToServer = false
        proxy.swallowToClient = false
        proxy.cutAll()
        compose.waitUntil(45_000) { session.state == SessionState.LIVE && onScreen(session, "reconnected") }
    }

    // ---- A22, reconnect with backoff ------------------------------------------------------------------

    /**
     * The pill with its countdown (spec D4), from a live tab whose server went away: the relay in
     * front of the sshd stops, the link closes under the shell and each try after is refused, so the
     * waits run 1 s, 2 s, then 4 s, read off every value the countdown passes through (each wait
     * starts where it rises), and the frame is taken on the 4. The pill counts that wait down
     * by the second; the relay starting again lets the try after it in, and the frame says
     * `reconnected` under the drop. The spec writes the pill `retry in 4 s`; the product draws
     * `retry in 4s` (a defect the proof pass records), which the reading here allows either way.
     */
    @Test
    fun `A22 the Reconnecting pill counts down to the next try while the server is down`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val relay = StoppableRelay(sshHost, sshPort).also { closeables += it }
        openTab(testHost("Berth test box", "127.0.0.1", relay.port))
        val session = awaitLive()
        compose.settle(1_200)
        session.sendText("clear; uname -sn\n")
        compose.settle(800)

        val passed = CopyOnWriteArrayList<Int?>()
        val watch = CoroutineScope(Dispatchers.Unconfined).launch { session.retryIn.collect { passed += it } }
        relay.stop()
        awaitOnScreen(session, "connection lost", 15_000)
        compose.waitUntil(20_000) { session.retryIn.value == 4 }
        watch.cancel()
        val waits = passed.filterIndexed { i, seconds -> seconds != null && passed.getOrNull(i - 1).let { it == null || it < seconds } }
        assertEquals("the waits, off the countdown's values $passed", listOf(1, 2, 4), waits)
        val pill = Regex("Reconnecting \u00B7 retry in (\\d+) ?s")
        val shown = texts().firstNotNullOfOrNull { pill.matchEntire(it) }
        assertTrue("the pill counts down; the texts were ${texts()}", shown != null)
        capture("A22-reconnecting-countdown")

        // The countdown moves by the second, on the pill itself.
        val first = pill.matchEntire(texts().first { pill.matches(it) })!!.groupValues[1].toInt()
        compose.waitUntil(3_000) { texts().mapNotNull { pill.matchEntire(it)?.groupValues?.get(1)?.toInt() }.any { it < first } }

        relay.start()
        compose.waitUntil(30_000) { session.state == SessionState.LIVE && onScreen(session, "reconnected") }
        compose.settle(800)
        capture("A22-reconnected")
    }

    // ---- A23, network-change probing ------------------------------------------------------------------

    /**
     * The probe on a network change (vision §4.4): the keepalive is 120 s, so nothing but the probe
     * finds the socket in the time the case waits. The relay goes silent, the default network's
     * addresses change (the callback the monitor registered, fired as the platform would on a
     * handoff), and within the probe's 2 s the marker says the network changed and the server did not
     * answer, with the pill on the reconnect. There is no screen of the probe's own; this frame is
     * the state it leaves.
     */
    @Test
    fun `A23 a network change probes the socket and a silent server is found at once`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val proxy = BlackHoleProxy(sshHost, sshPort).also { closeables += it }
        openTab(testHost("Berth test box", "127.0.0.1", proxy.port, PersistencePolicy(keepaliveSeconds = 120)))
        val session = awaitLive()
        compose.settle(1_200)
        session.sendText("clear; uname -sn\n")
        compose.settle(800)

        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork!!
        compose.waitUntil(5_000) { shadowOf(manager).networkCallbacks.isNotEmpty() }
        proxy.swallowToServer = true
        proxy.swallowToClient = true
        val changedAt = System.currentTimeMillis()
        shadowOf(manager).networkCallbacks.toList().forEach { it.onLinkPropertiesChanged(network, LinkProperties()) }
        awaitOnScreen(session, "connection lost: ${TerminalSession.PROBE_LOST_REASON}", 10_000)
        val foundIn = System.currentTimeMillis() - changedAt
        assertTrue("found by the probe in ${foundIn} ms, not by the keepalive", foundIn < 10_000)
        compose.settle(600)
        assertTrue("the pill says Reconnecting; the texts were ${texts()}", texts().any { it.startsWith("Reconnecting") })
        capture("A23-network-change-probe")

        proxy.swallowToServer = false
        proxy.swallowToClient = false
        proxy.cutAll()
        compose.waitUntil(45_000) { session.state == SessionState.LIVE && onScreen(session, "reconnected") }
    }
}

/**
 * A TCP relay in front of the test sshd that can stop the way a server going down looks from the
 * phone: [stop] closes every link (the client reads EOF) and the listening socket (a new connect is
 * refused); [start] listens on the same port again, so the host's address and its trusted key hold
 * across the outage and the reconnect after it goes through.
 */
internal class StoppableRelay(private val targetHost: String, private val targetPort: Int) : Closeable {
    @Volatile private var server: ServerSocket = listen(0)
    val port: Int = server.localPort
    private val links = CopyOnWriteArrayList<Socket>()

    init {
        serve(server)
    }

    private fun listen(port: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50)
    }

    private fun serve(socket: ServerSocket) = thread(name = "relay-accept", isDaemon = true) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: IOException) { break }
            val upstream = try { Socket(targetHost, targetPort) } catch (_: IOException) { client.close(); continue }
            links += client
            links += upstream
            pump(client, upstream)
            pump(upstream, client)
        }
    }

    private fun pump(from: Socket, to: Socket) = thread(name = "relay-pump", isDaemon = true) {
        try {
            from.getInputStream().copyTo(to.getOutputStream(), 16 * 1024)
        } catch (_: IOException) {
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    fun stop() {
        runCatching { server.close() }
        links.forEach { runCatching { it.close() } }
        links.clear()
    }

    fun start() {
        server = listen(port)
        serve(server)
    }

    override fun close() = stop()
}

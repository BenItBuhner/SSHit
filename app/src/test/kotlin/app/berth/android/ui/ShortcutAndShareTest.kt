package app.berth.android.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.berth.android.files.TransferManager
import app.berth.android.files.TransferState
import app.berth.android.links.Arrival
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.session.ManagedTab
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory

/**
 * What a launcher shortcut and a share come to once they reach the view model (spec Part B, App
 * shortcuts; C24): a shortcut's host opens as the host list's Connect would, one for a host since
 * deleted is a notice; Quick connect opens its sheet empty; a share with no live terminal on stage
 * is a notice and copies nothing. Against the local sshd (`SSH_TEST_*`), a shared file lands in
 * `/tmp` on the host through the transfer queue and its path is pasted into the shell, quoted, and
 * shared text is pasted as typed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShortcutAndShareTest {
    private lateinit var graph: TestGraph
    private val vm get() = graph.viewModel
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    /** Nothing listens on port 1; a login there is refused straight away, so the tab is on stage but never live. */
    private val homelab = Host(
        id = "homelab", name = "homelab", color = SwatchColor.MOSS, monogram = "HO", address = "127.0.0.1", port = 1, user = "ben",
        auth = AuthMethod.AskEachTime, createdAt = 10L, lastConnectedAt = 5_000L,
    )
    private val box = Host(
        id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = sshHost, port = sshPort, user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = 20L,
    )

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        runBlocking {
            graph.hosts.upsert(homelab)
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        bg.cancel()
        graph.close()
    }

    // ---- shortcuts ---------------------------------------------------------------------------------

    @Test
    fun `a host shortcut opens a terminal on its host and lands on the stage, as the host list's Connect would`(): Unit = runBlocking {
        vm.arrive(Arrival.OpenHost(homelab.id))
        assertEquals(LinkOutcome.Staged, vm.linkOutcome.value)
        val tab = staged()
        assertEquals(TabKind.Ssh, tab.kind)
        assertEquals(homelab.id, tab.record.value.hostId)
        assertEquals(listOf(tab.id), strip(1).map { it.id })
    }

    @Test
    fun `a shortcut for a host no longer saved is a notice, and nothing opens`(): Unit = runBlocking {
        vm.arrive(Arrival.OpenHost("deleted-long-ago"))
        assertEquals(LinkOutcome.Notice("That host is no longer saved."), vm.linkOutcome.value)
        assertNull(graph.sessions.activeTabId.value)
        assertTrue(graph.sessions.tabs.value.isEmpty())
    }

    @Test
    fun `the Quick connect shortcut opens the sheet with an empty field and no link to speak of`(): Unit = runBlocking {
        vm.arrive(Arrival.QuickConnect)
        val outcome = vm.linkOutcome.value as LinkOutcome.QuickConnect
        assertEquals("", outcome.spec)
        assertNull(outcome.fingerprint)
        assertNull(graph.sessions.activeTabId.value)
    }

    /** The activity's route: the intent's arrival goes into the inbox, and the view model, up before any intent is read, acts on it. */
    @Test
    fun `a shortcut handed to the inbox reaches the view model and opens its host`() {
        assertNotNull(vm)
        graph.inbox.offer(Arrival.OpenHost(homelab.id))
        awaitOnMain("the host opens from the inbox") { vm.linkOutcome.value == LinkOutcome.Staged }
        assertEquals(homelab.id, staged().record.value.hostId)
    }

    // ---- shares with nowhere to land ---------------------------------------------------------------

    @Test
    fun `a share with nothing on stage is a notice, and nothing is copied or pasted`(): Unit = runBlocking {
        vm.arrive(Arrival.Files(listOf(Uri.parse("content://media/external/file/12"))))
        assertEquals(LinkOutcome.Notice(AppViewModel.NO_LIVE_SESSION_FOR_SHARE), vm.linkOutcome.value)
        vm.clearLinkOutcome()
        vm.arrive(Arrival.Text("rm -rf /"))
        assertEquals(LinkOutcome.Notice(AppViewModel.NO_LIVE_SESSION_FOR_SHARE), vm.linkOutcome.value)
        assertTrue("no transfer was queued", graph.files.transfers.transfers.value.isEmpty())
        assertNull(graph.sessions.activeTabId.value)
    }

    @Test
    fun `a share with a tab on stage that is not live is the same notice`(): Unit = runBlocking {
        vm.arrive(Arrival.OpenHost(homelab.id))
        val tab = staged()
        val session = graph.sessions.get(tab.id)!!
        // The login is refused at once; whatever the tab settles to, it is not Live.
        withTimeoutOrNull(10_000) { session.record.first { it.state != SessionState.CONNECTING } }
        assertNotEquals(SessionState.LIVE, session.state)
        vm.clearLinkOutcome()
        vm.arrive(Arrival.Files(listOf(Uri.parse("content://media/external/file/12"))))
        assertEquals(LinkOutcome.Notice(AppViewModel.NO_LIVE_SESSION_FOR_SHARE), vm.linkOutcome.value)
        assertTrue(graph.files.transfers.transfers.value.isEmpty())
    }

    // ---- shares into a live shell (the local sshd) -------------------------------------------------

    @Test
    fun `shared text is pasted into the live terminal on stage`() {
        val session = live()
        runBlocking { vm.arrive(Arrival.Text("echo shared-text-marker")) }
        assertEquals(LinkOutcome.Staged, vm.linkOutcome.value)
        await("the shell echoes the pasted text") { session.emulator.screenText().any { it.contains("echo shared-text-marker") } }
    }

    @Test
    fun `a shared file lands in the host's tmp through the transfer queue, and its quoted path is pasted`() {
        val session = live()
        val local = createTempDirectory("berth-share").toFile()
        val stem = "berth drop ${UUID.randomUUID().toString().take(8)}"
        val report = File(local, "$stem report.txt").apply { writeText("quarterly numbers\n") }
        val plain = File(local, "${stem.replace(' ', '-')}.log").apply { writeText("plain\n") }
        try {
            runBlocking { vm.arrive(Arrival.Files(listOf(Uri.fromFile(report), Uri.fromFile(plain)))) }
            assertEquals(LinkOutcome.Staged, vm.linkOutcome.value)
            val rows = awaitTransfers(2)
            assertEquals(listOf("/tmp/${report.name}", "/tmp/${plain.name}"), rows.map { it.remotePath })
            assertTrue(rows.all { it.state == TransferState.DONE })
            // The sshd is this machine: the copies are in its /tmp, as the shell will find them.
            val landedReport = File("/tmp", report.name)
            val landedPlain = File("/tmp", plain.name)
            assertEquals("quarterly numbers\n", landedReport.readText())
            assertEquals("plain\n", landedPlain.readText())
            // Pasted as one word each, in the order shared, a space between: the path with spaces in
            // quotes, the plain one bare. The shell's line may wrap on the screen; read rejoined.
            val quoted = TransferManager.shellQuote("/tmp/${report.name}")
            assertEquals("'/tmp/${report.name}'", quoted)
            await("both paths on the shell's line") { session.emulator.cursorLineText().endsWith("$quoted /tmp/${plain.name}") }
            // Cleared through the shell, since /tmp keeps the owner's files from anyone else.
            session.sendText("\u0015rm -f $quoted /tmp/${plain.name}\r")
            await("the shell removes the copies") { !landedReport.exists() && !landedPlain.exists() }
        } finally {
            local.deleteRecursively()
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A live shell on the sshd on stage, or the test is skipped; the trust prompt is answered as the sheet's button would. */
    private fun live(): TerminalSession {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        val session = runBlocking { graph.sessions.open(box) }
        await("the login to be live") { session.state == SessionState.LIVE }
        await("the prompt to show") { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(session.id, graph.sessions.activeTabId.value)
        return session
    }

    private fun awaitTransfers(count: Int): List<app.berth.android.files.Transfer> {
        await("$count transfers to finish") {
            val rows = graph.files.transfers.transfers.value
            rows.size == count && rows.none { it.state.isActive }
        }
        return graph.files.transfers.transfers.value
    }

    private fun staged(): ManagedTab = graph.sessions.tab(graph.sessions.activeTabId.value ?: throw AssertionError("nothing on stage"))!!

    private suspend fun strip(count: Int): List<ManagedTab> = withTimeoutOrNull(5_000) { graph.sessions.tabs.first { it.size == count } }
        ?: throw AssertionError("timed out waiting for $count tabs on the strip; it shows ${graph.sessions.tabs.value.size}")

    private fun awaitOnMain(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

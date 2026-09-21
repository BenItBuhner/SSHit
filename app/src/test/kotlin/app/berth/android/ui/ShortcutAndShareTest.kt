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
import app.berth.sftp.SftpFileSystem
import app.berth.sftp.SftpPaths
import app.berth.sftp.TextRead
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
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createTempDirectory

/**
 * What a launcher shortcut and a share come to once they reach the view model (spec Part B, App
 * shortcuts; C24): a shortcut's host opens as the host list's Connect would, one for a host since
 * deleted is a notice; Quick connect opens its sheet empty; a share with no live terminal on stage
 * is a notice and copies nothing. Against the local sshd (`SSH_TEST_*`), a shared file lands in a
 * folder of the login's own under `/tmp` on the host (0700, the file 0600, a same-name drop kept
 * beside the first, a link planted at `/tmp/<name>` never touched) through the transfer queue and
 * its path is pasted into the shell, quoted; shared text waits for the Stage's paste gate (spec
 * C18) and reaches the shell through nothing else.
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

    /**
     * A share's text is a paste like the clipboard's, so it goes where the Stage's paste gate is
     * (`StageTools.paste`, spec C18) and nowhere else: the view model holds it for the Stage of the
     * session it is for, and with no Stage composed, as here, nothing reaches the shell, however
     * many lines the share has. The Stage's side, the preview rising for two lines and the paste
     * waiting on its confirmation, is `ShortcutAndShareScreenshotTest`'s.
     */
    @Test
    fun `shared text waits for the Stage's paste gate, and nothing reaches the shell on its own`() {
        val session = live()
        val text = "echo shared-text-marker\necho a second line"
        runBlocking { vm.arrive(Arrival.Text(text)) }
        assertEquals(LinkOutcome.Staged, vm.linkOutcome.value)
        assertEquals(SharedPaste(session.id, text), vm.sharedPaste.value)
        // Long enough for a paste to have echoed, had one been made.
        Thread.sleep(1_500)
        assertTrue("nothing was pasted around the gate", session.emulator.screenText().none { it.contains("shared-text-marker") })
        // The Stage takes it once, and a second take of the same share is nothing.
        vm.sharedPasteTaken(vm.sharedPaste.value!!)
        assertNull(vm.sharedPaste.value)
        vm.sharedPasteTaken(SharedPaste(session.id, text))
        assertNull(vm.sharedPaste.value)
    }

    @Test
    fun `a shared file lands in a private folder under the host's tmp through the transfer queue, and its quoted path is pasted`() {
        val session = live()
        val local = createTempDirectory("berth-share").toFile()
        val stem = "berth drop ${UUID.randomUUID().toString().take(8)}"
        val report = File(local, "$stem report.txt").apply { writeText("quarterly numbers\n") }
        val plain = File(local, "${stem.replace(' ', '-')}.log").apply { writeText("plain\n") }
        // Another user of the host planted a link at the name a drop into /tmp itself would take, pointing at a file of theirs.
        val victim = File(local, "victim.txt").apply { writeText("theirs\n") }
        val planted = File(TransferManager.DROP_DIR, report.name).toPath()
        Files.createSymbolicLink(planted, victim.toPath())
        var folder: String? = null
        try {
            runBlocking { vm.arrive(Arrival.Files(listOf(Uri.fromFile(report), Uri.fromFile(plain)))) }
            assertEquals(LinkOutcome.Staged, vm.linkOutcome.value)
            val rows = awaitTransfers(2)
            assertTrue(rows.all { it.state == TransferState.DONE })
            // Both in one folder of the login's own, named at random under /tmp; neither at /tmp/<name>.
            val dir = SftpPaths.parent(rows[0].remotePath)
            folder = dir
            assertTrue(dir, Regex("/tmp/${TransferManager.DROP_FOLDER_PREFIX}[0-9a-f]{8}").matches(dir))
            assertEquals(listOf("$dir/${report.name}", "$dir/${plain.name}"), rows.map { it.remotePath })
            // Read as the login, over its own sftp channel, since the folder admits nobody else: 700 on
            // the folder, 600 on each file, and the bytes as they were shared.
            sftp(session) { fs ->
                assertEquals("the folder's mode", 0b111_000_000, fs.stat(dir).permissions and 0b111_111_111)
                for (row in rows) assertEquals("${row.name}'s mode", 0b110_000_000, fs.stat(row.remotePath).permissions and 0b111_111_111)
                assertEquals("quarterly numbers\n", fs.text(rows[0].remotePath))
                assertEquals("plain\n", fs.text(rows[1].remotePath))
            }
            // Pasted as one word each, in the order shared, a space between: the path with spaces in
            // quotes, the plain one bare. The shell's line may wrap on the screen; read rejoined.
            val quoted = TransferManager.shellQuote(rows[0].remotePath)
            assertEquals("'$dir/${report.name}'", quoted)
            await("both paths on the shell's line") { session.emulator.cursorLineText().endsWith("$quoted ${rows[1].remotePath}") }
            // The planted link was neither followed nor written: the name it stands at is not one a drop takes.
            assertTrue("the link is still a link", Files.isSymbolicLink(planted))
            assertEquals("theirs\n", victim.readText())

            // The same name shared again on the same session: the folder is kept, the first copy is kept
            // as it was, and the second is saved beside it, the way a folder transfer keeps both.
            session.sendText("\u0015")
            report.writeText("revised numbers\n")
            runBlocking { vm.arrive(Arrival.Files(listOf(Uri.fromFile(report)))) }
            val again = awaitTransfers(3).last()
            assertEquals(TransferState.DONE, again.state)
            assertEquals("$dir/$stem report (1).txt", again.remotePath)
            assertEquals("$stem report (1).txt", again.name)
            assertEquals("${report.name} was there \u00B7 saved as $stem report (1).txt", again.note)
            sftp(session) { fs ->
                assertEquals("quarterly numbers\n", fs.text(rows[0].remotePath))
                assertEquals("revised numbers\n", fs.text(again.remotePath))
                assertEquals(0b110_000_000, fs.stat(again.remotePath).permissions and 0b111_111_111)
            }
            await("the kept copy's path on the shell's line") { session.emulator.cursorLineText().endsWith(TransferManager.shellQuote(again.remotePath)) }
        } finally {
            Files.deleteIfExists(planted)
            // Cleared through the shell, since the folder is the login's alone.
            folder?.let { dir ->
                session.sendText("\u0015rm -rf ${TransferManager.shellQuote(dir)}\r")
                await("the shell removes the folder") { !File(dir).exists() }
            }
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

    /** [block] on an sftp channel over [session]'s login, closed after; what the login can see of its own files, and nothing the test's user could. */
    private fun sftp(session: TerminalSession, block: suspend (SftpFileSystem) -> Unit) = runBlocking {
        val fs = session.openSftp()
        try {
            block(fs)
        } finally {
            fs.close()
        }
    }

    private suspend fun SftpFileSystem.text(path: String): String = (readText(path, 4096) as TextRead.Text).content

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

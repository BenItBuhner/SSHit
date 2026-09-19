package app.berth.android.screenshots

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import app.berth.android.files.FilesBrowser
import app.berth.android.files.Transfer
import app.berth.android.files.TransferKind
import app.berth.android.files.TransferState
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.files.FilesActions
import app.berth.android.ui.files.FilesPane
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The Files screen in Berth Dark on a Pixel-class phone. Offline cases drive [FilesPane] over an
 * in-memory server so every state the screen has is photographed deterministically; `files live
 * flow` opens the real app against the local sshd, browses through the session sheet's Files
 * button, reads a file, downloads one through the transfer queue and uploads another.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class FilesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = System.currentTimeMillis()

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun waitForText(text: String, timeout: Long = 10_000, substring: Boolean = false) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForNoText(text: String, timeout: Long = 10_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }
    }

    /** Taps the scrim above a modal sheet; its centre would land on a tall sheet, so the tap goes near the top. */
    private fun dismissSheet() {
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(centerX, 12f)) }
    }

    private fun browser(fs: SftpFileSystem, start: String?): FilesBrowser = FilesBrowser("s-demo", scope, open = { fs }).also { it.start(start) }

    private val recent = listOf("/home/demo", "/var/log", "/home/demo/projects/berth", "/etc/nginx/sites-enabled")

    /** The pane with local preference state, so sort and hidden chips work without the settings document. */
    @Composable
    private fun Pane(
        browser: FilesBrowser,
        transfers: List<Transfer> = emptyList(),
        cwd: String? = "/home/demo/projects/berth/app",
        connected: Boolean = true,
        initial: FilesPrefs = FilesPrefs(),
    ) {
        var prefs by remember { mutableStateOf(initial) }
        FilesPane(
            hostName = "prod-web",
            browser = browser,
            prefs = prefs,
            onSort = { sort -> prefs = prefs.copy(sort = sort, ascending = if (prefs.sort == sort) !prefs.ascending else true) },
            onShowHidden = { prefs = prefs.copy(showHidden = it) },
            transfers = transfers,
            onCancelTransfer = {},
            onClearFinished = {},
            terminalCwd = cwd,
            recent = recent,
            actions = FilesActions.None,
            onBack = {},
            connected = connected,
            onReconnect = {},
        )
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    // ---- offline -------------------------------------------------------------------------------

    @Test
    fun `browser sorts and hidden`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        capture("files-browser")

        compose.onNodeWithText("Size").performClick()
        waitForText("Size \u2191")
        compose.onNodeWithText("Size \u2191").performClick()
        waitForText("Size \u2193")
        capture("files-browser-by-size")

        compose.onNodeWithText("Hidden 4").performClick()
        waitForText(".bashrc")
        capture("files-browser-hidden")
    }

    @Test
    fun `breadcrumb and path editor`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo/projects/berth")
        themed { Pane(b) }
        waitForText("settings.gradle.kts")
        capture("files-breadcrumb-deep")

        compose.onNodeWithText("projects").performClick()
        waitForText("berth")
        assertEquals("/home/demo/projects", b.state.value.path)

        compose.onNodeWithContentDescription("Edit path").performClick()
        waitForText("Go to folder")
        capture("files-path-editor")

        compose.onNode(hasSetTextAction()).performTextReplacement("/var/log")
        compose.onNodeWithText("Go").performClick()
        compose.waitUntil(10_000) { b.state.value.path == "/var/log" && !b.state.value.loading }
        waitForText("Empty folder.")
        capture("files-empty-folder")
    }

    @Test
    fun `multi select and the sheets`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")

        compose.onNodeWithText("deploy.sh").performTouchInput { longClick() }
        waitForText("1 selected")
        compose.onNodeWithText("notes.txt").performClick()
        compose.onNodeWithText("backups").performClick()
        waitForText("3 selected")
        capture("files-multi-select")

        compose.onNodeWithText("Mode").performClick()
        waitForText("Permissions")
        capture("files-chmod")
        compose.onNodeWithText("Cancel").performClick()
        waitForNoText("Permissions")

        compose.onNodeWithText("Delete").performClick()
        waitForText("Delete 3 items?")
        capture("files-delete-confirm")
        compose.onNodeWithText("Cancel").performClick()
        waitForNoText("Delete 3 items?")

        compose.onNodeWithContentDescription("Clear selection").performClick()
        waitForNoText("3 selected")
        compose.onNodeWithText("deploy.sh").performTouchInput { longClick() }
        waitForText("1 selected")
        compose.onNodeWithText("Rename").performClick()
        waitForText("Rename", timeout = 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("-prod")
        capture("files-rename")
        compose.onAllNodes(hasText("Rename")).fetchSemanticsNodes()
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("Delete").performClick()
        waitForText("Delete deploy.sh?")
        compose.onAllNodes(hasText("Delete")).onLast().performClick()
        waitForText("Deleted deploy.sh")
        waitForNoText("deploy.sh")
        capture("files-deleted-notice")
        assertTrue("/home/demo/deploy.sh" !in fs.nodes)
    }

    @Test
    fun `new folder from the menu`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("New folder")
        capture("files-more-menu")
        compose.onNodeWithText("New folder").performClick()
        waitForText("Create")
        compose.onNode(hasSetTextAction()).performTextInput("releases")
        capture("files-new-folder")
        compose.onNodeWithText("Create").performClick()
        waitForText("Created releases")
        waitForText("releases")
        capture("files-new-folder-created")
        assertTrue("/home/demo/releases" in fs.nodes)
    }

    @Test
    fun `viewer for text and binary`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        compose.onNodeWithText("deploy.sh").performClick()
        waitForText("set -euo pipefail", substring = true)
        capture("files-viewer")
        dismissSheet()
        waitForNoText("Copy path")

        compose.onNodeWithText("berth-arm64.apk").performClick()
        waitForText("This is a binary file.")
        capture("files-viewer-binary")
    }

    @Test
    fun `transfers strip and sheet`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val mb = 1024L * 1024
        val transfers = listOf(
            Transfer("t1", "s-demo", "prod-web", TransferKind.DOWNLOAD, "site-backup-2026-09-18.tar.gz", "/home/demo/site-backup-2026-09-18.tar.gz", total = 348 * mb, bytes = 131 * mb, bytesPerSecond = 4.2 * mb, state = TransferState.RUNNING, startedAt = now - 31_000),
            Transfer("t2", "s-demo", "prod-web", TransferKind.UPLOAD, "berth-arm64.apk", "/home/demo/berth-arm64.apk", total = 48 * mb, state = TransferState.QUEUED),
            Transfer("t3", "s-demo", "prod-web", TransferKind.DOWNLOAD, "notes.txt", "/home/demo/notes.txt", total = 120, bytes = 120, state = TransferState.DONE, startedAt = now - 61_000, finishedAt = now - 60_000),
            Transfer("t4", "s-demo", "build box", TransferKind.UPLOAD, "config.yaml", "/etc/app/config.yaml", total = 2048, bytes = 0, state = TransferState.FAILED, error = "The server refused access to /etc/app/config.yaml.", startedAt = now - 120_000, finishedAt = now - 119_000),
        )
        themed { Pane(b, transfers = transfers) }
        waitForText("deploy.sh")
        waitForText("131 MB of 348 MB", substring = true)
        capture("files-transfer-strip")
        compose.onNodeWithContentDescription("Transfers, 2 running").performClick()
        waitForText("Clear finished")
        capture("files-transfers-sheet")
    }

    @Test
    fun `refreshing on a pull`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        val hold = CompletableDeferred<Unit>()
        fs.holdList = hold
        compose.onRoot().performTouchInput {
            swipe(start = Offset(centerX, centerY), end = Offset(centerX, centerY + 600f), durationMillis = 400)
        }
        compose.waitUntil(10_000) { b.state.value.refreshing }
        capture("files-refreshing")
        fs.holdList = null
        hold.complete(Unit)
        compose.waitUntil(10_000) { !b.state.value.refreshing }
    }

    @Test
    fun `error states`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val denied = browser(fs, "/root")
        themed { Pane(denied) }
        compose.waitUntil(10_000) { denied.state.value.error is SftpError.PermissionDenied }
        waitForText("No access.")
        capture("files-error-denied")
    }

    @Test
    fun `error state missing folder`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        b.navigate("/home/demo/projects/gone")
        compose.waitUntil(10_000) { b.state.value.error is SftpError.NotFound }
        waitForText("Nothing here.")
        capture("files-error-missing")
    }

    @Test
    fun `error state not connected`() {
        val b = FilesBrowser("s-demo", scope, open = { throw SftpError.NotConnected() }).also { it.start("/home/demo") }
        themed { Pane(b, connected = false) }
        compose.waitUntil(10_000) { b.state.value.error is SftpError.NotConnected }
        waitForText("Not connected.")
        capture("files-error-not-connected")
    }

    @Test
    fun `operation error becomes a notice`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b) }
        waitForText("deploy.sh")
        fs.locked += "/home/demo/nginx-access.log"
        compose.onNodeWithText("nginx-access.log").performTouchInput { longClick() }
        waitForText("1 selected")
        compose.onNodeWithText("Delete").performClick()
        waitForText("Delete nginx-access.log?")
        compose.onAllNodes(hasText("Delete")).onLast().performClick()
        waitForText("The server refused access to /home/demo/nginx-access.log.")
        capture("files-error-notice")
    }

    // ---- live against the local sshd ---------------------------------------------------------------

    @Test
    fun `files live flow`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = Host(
            id = "berth-test-box",
            name = "Berth test box",
            color = SwatchColor.TEAL,
            monogram = Host.monogramFor("Berth test box"),
            address = sshHost,
            port = sshPort,
            user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            tags = listOf("local"),
            createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }

        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { graph.viewModel.workspaces.value.isNotEmpty() }
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!

        // A demo tree through the client under test, with a payload big enough to watch move.
        val random = Random(20260919)
        val payload = ByteArray(40 * 1024 * 1024).also(random::nextBytes)
        val readme = "Berth files demo\n\nThis folder was created by the live screenshot test through the sftp channel\nof the session on the Stage. Everything in it is disposable.\n"
        val dir = runBlocking {
            val fs = session.openSftp()
            try {
                val home = fs.home()
                val d = "$home/berth-files-demo"
                runCatching { fs.delete(d) }
                fs.mkdir(d)
                fs.mkdir("$d/logs")
                fs.mkdir("$d/releases")
                fs.upload(ByteArrayInputStream(readme.toByteArray()), readme.length.toLong(), "$d/README.txt")
                fs.upload(ByteArrayInputStream(payload), payload.size.toLong(), "$d/site-backup.tar.gz")
                fs.upload(ByteArrayInputStream("server {\n    listen 80;\n    server_name prod-web;\n}\n".toByteArray()), 52, "$d/nginx.conf")
                fs.upload(ByteArrayInputStream("2026-09-19 10:31:02 started\n".toByteArray()), 28, "$d/logs/app.log")
                fs.chmod("$d/nginx.conf", 0b110_100_000)
                d
            } finally {
                fs.close()
            }
        }
        // The shell reports its directory over OSC 7 so the Files screen can jump to it.
        session.sendText("PROMPT_COMMAND='printf \"\\e]7;file://%s%s\\e\\\\\\\\\" \"\$HOSTNAME\" \"\$PWD\"' && cd $dir/logs && clear\n")
        compose.waitUntil(10_000) { session.cwd == "$dir/logs" }

        compose.onNodeWithText("Berth test box").performClick()
        waitForText("Detach", 5_000)
        capture("files-live-session-sheet")
        // The rail's library link is composed off screen with the same label; the sheet's button sits beside Detach.
        compose.onNode(hasText("Files") and hasAnySibling(hasText("Detach"))).performClick()
        // The browser opens where the shell is.
        waitForText("app.log", 20_000)
        capture("files-live-opened")

        compose.onNodeWithText("berth-files-demo").performClick()
        waitForText("README.txt", 15_000)
        capture("files-live-folder")

        compose.onNodeWithText("README.txt").performClick()
        waitForText("Everything in it is disposable.", 10_000, substring = true)
        capture("files-live-viewer")
        dismissSheet()
        waitForNoText("Copy path")

        // Download through the transfer queue into a file:// document, the way a SAF result lands.
        val out = File.createTempFile("berth-files-live", ".bin")
        val entry = runBlocking { session.openSftp().use { it.stat("$dir/site-backup.tar.gz") } }
        val id = graph.files.transfers.download(session, entry, Uri.fromFile(out))
        fun transfer() = graph.files.transfers.transfers.value.first { it.id == id }
        compose.waitUntil(30_000) { transfer().let { (it.state == TransferState.RUNNING && it.bytes > 0) || it.state == TransferState.DONE } }
        capture("files-live-download")
        compose.waitUntil(120_000) { transfer().state == TransferState.DONE }
        assertArrayEquals(sha256(payload), sha256(out.readBytes()))
        compose.onNodeWithContentDescription("Transfers").performClick()
        waitForText("Clear finished", 10_000)
        capture("files-live-transfers")
        dismissSheet()
        waitForNoText("Clear finished")

        // Upload a local document into the folder being shown; the listing picks it up on its own.
        val uploads = createTempDirectory("berth-upload").toFile()
        val local = File(uploads, "app-2026-09-19.log").apply { writeBytes(ByteArray(3 * 1024 * 1024).also(random::nextBytes)) }
        val up = graph.files.transfers.upload(session, listOf(Uri.fromFile(local)), dir).single()
        compose.waitUntil(60_000) { graph.files.transfers.transfers.value.first { it.id == up }.state == TransferState.DONE }
        waitForText(local.name, 15_000)
        val uploaded = runBlocking { session.openSftp().use { it.stat("$dir/${local.name}") } }
        assertEquals(local.length(), uploaded.size)
        capture("files-live-uploaded")

        // The shell moves on; the screen follows it through the menu into the empty releases folder.
        session.sendText("cd $dir/releases\n")
        compose.waitUntil(10_000) { session.cwd == "$dir/releases" }
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Terminal directory", 5_000)
        compose.onNodeWithText("Terminal directory").performClick()
        waitForText("Empty folder.", 15_000)
        capture("files-live-terminal-directory")

        // A folder from the empty state's button, its mode changed through the sheet, then deleted.
        compose.onNodeWithText("New folder").performClick()
        waitForText("Create", 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("archive")
        compose.onNodeWithText("Create").performClick()
        waitForText("Created archive", 15_000)
        waitForText("archive", 15_000)
        capture("files-live-new-folder")
        compose.onNodeWithText("archive").performTouchInput { longClick() }
        waitForText("1 selected", 5_000)
        compose.onNodeWithText("Mode").performClick()
        waitForText("Permissions", 5_000)
        compose.onNode(hasSetTextAction()).performTextReplacement("700")
        waitForText("drwx------", 5_000)
        capture("files-live-chmod")
        compose.onNodeWithText("Apply").performClick()
        waitForText("Changed archive", 15_000)
        waitForText("drwx------", 15_000, substring = true)
        val archive = runBlocking { session.openSftp().use { it.stat("$dir/releases/archive") } }
        assertEquals(0b111_000_000, archive.permissions and 0b111_111_111)
        compose.onNodeWithText("archive").performTouchInput { longClick() }
        waitForText("1 selected", 5_000)
        compose.onNodeWithText("Delete").performClick()
        waitForText("Delete archive?", 5_000)
        compose.onAllNodes(hasText("Delete")).onLast().performClick()
        waitForText("Deleted archive", 15_000)
        waitForNoText("archive", 15_000)
        capture("files-live-deleted")

        runBlocking { session.openSftp().use { runCatching { it.delete(dir) } } }
        out.delete()
        uploads.deleteRecursively()
        graph.sessions.close(session.id)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

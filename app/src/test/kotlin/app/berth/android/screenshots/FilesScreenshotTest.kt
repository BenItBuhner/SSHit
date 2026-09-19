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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
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
import app.berth.android.ui.files.formatModified
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.FilesPrefs
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.sftp.ConflictChoice
import app.berth.sftp.FolderConflict
import app.berth.sftp.FolderFailure
import app.berth.sftp.FolderPhase
import app.berth.sftp.FolderProgress
import app.berth.sftp.SftpError
import app.berth.sftp.SftpFileSystem
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The Files pane in Berth Dark on a Pixel-class phone. Offline cases drive [FilesPane] over an
 * in-memory server so every state the pane has is photographed deterministically; `files live
 * flow` opens the real app against the local sshd, opens the host's Files tab from the session
 * sheet's Files button, reads a file, downloads one through the transfer queue and uploads
 * another. The Files tab on the Stage with seeded data is in `BerthScreenshotTest`.
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

    private fun browser(fs: SftpFileSystem, start: String?): FilesBrowser = FilesBrowser("f-demo", scope, open = { fs }).also { it.start(start) }

    private val recent = listOf("/home/demo", "/var/log", "/home/demo/projects/berth", "/etc/nginx/sites-enabled")

    /** The pane with local preference state, so sort and hidden chips work without the settings document. */
    @Composable
    private fun Pane(
        browser: FilesBrowser,
        transfers: List<Transfer> = emptyList(),
        cwd: String? = "/home/demo/projects/berth/app",
        connected: Boolean = true,
        initial: FilesPrefs = FilesPrefs(),
        header: Boolean = true,
        actions: FilesActions = FilesActions.None,
        onResolveConflict: (id: String, choice: ConflictChoice, applyToAll: Boolean) -> Unit = { _, _, _ -> },
        onRetryFailed: (id: String) -> Unit = {},
    ) {
        var prefs by remember { mutableStateOf(initial) }
        FilesPane(
            hostName = "prod-web",
            browser = browser,
            header = header,
            prefs = prefs,
            onSort = { sort -> prefs = prefs.copy(sort = sort, ascending = if (prefs.sort == sort) !prefs.ascending else true) },
            onShowHidden = { prefs = prefs.copy(showHidden = it) },
            transfers = transfers,
            onCancelTransfer = {},
            onClearFinished = {},
            terminalCwd = cwd,
            recent = recent,
            actions = actions,
            onBack = {},
            connected = connected,
            onReconnect = {},
            onResolveConflict = onResolveConflict,
            onRetryFailed = onRetryFailed,
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
        // "berth" is a crumb before the jump and the one row after it, so the listing itself is what to wait for.
        compose.waitUntil(10_000) { b.state.value.path == "/home/demo/projects" && !b.state.value.loading }
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

        // A long press on the row is one way in; the glyph is the other, and it is the checkbox from then on.
        compose.onNodeWithText("deploy.sh").performTouchInput { longClick() }
        waitForText("1 selected")
        compose.onNodeWithContentDescription("Select notes.txt").performClick()
        compose.onNodeWithContentDescription("Select backups").performClick()
        waitForText("3 selected")
        compose.onNodeWithContentDescription("Deselect backups").assertExists()
        capture("files-multi-select")

        // The row itself still opens: a folder tap enters it and the selection goes with the old folder.
        compose.onNodeWithText("projects").performClick()
        waitForText("berth")
        assertEquals("/home/demo/projects", b.state.value.path)
        waitForNoText("3 selected")
        compose.onNodeWithText("demo").performClick()
        waitForText("deploy.sh")

        compose.onNodeWithContentDescription("Select deploy.sh").performClick()
        compose.onNodeWithContentDescription("Select notes.txt").performClick()
        compose.onNodeWithContentDescription("Select backups").performClick()
        waitForText("3 selected")
        compose.onNodeWithText("Mode").performClick()
        waitForText("Permissions")
        // Two files at 644 and a folder at 755 do not agree, so the field waits for an explicit mode.
        waitForText("modes differ", substring = true)
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
        // The stem opens selected, so typing replaces `deploy` and keeps `.sh`.
        compose.onNode(hasSetTextAction()).performTextInput("deploy-prod")
        waitForText("deploy-prod.sh")
        capture("files-rename")
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
        compose.onNodeWithContentDescription("Folder options").performClick()
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

        // The name settles it before any read: a compact sheet with one line and the three actions.
        val reads = fs.reads
        compose.onNodeWithText("berth-arm64.apk").performClick()
        waitForText("Binary file. Download it or share it to open it elsewhere.")
        assertEquals(reads, fs.reads)
        capture("files-viewer-binary")
    }

    @Test
    fun `pane without its own header`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        themed { Pane(b, header = false) }
        waitForText("deploy.sh")
        // No screen header or status-bar inset; the header's actions ride the breadcrumb row instead.
        assertTrue(compose.onAllNodes(hasText("Files \u00B7 prod-web")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Upload").assertExists()
        compose.onNodeWithContentDescription("Transfers").assertExists()
        val crumbs = compose.onNodeWithContentDescription("Edit path").fetchSemanticsNode().boundsInRoot
        val firstRow = compose.onNodeWithText("deploy.sh").fetchSemanticsNode().boundsInRoot
        capture("files-headerless")
        // A selection takes over the breadcrumb row at the same height, so a strip above stays alone and the listing holds still.
        compose.onNodeWithContentDescription("Select notes.txt").performClick()
        waitForText("1 selected")
        assertTrue(compose.onAllNodes(hasContentDescription("Edit path")).fetchSemanticsNodes().isEmpty())
        val bar = compose.onNodeWithContentDescription("Clear selection").fetchSemanticsNode().boundsInRoot
        assertTrue("selection bar at ${bar.top}..${bar.bottom}, crumbs were at ${crumbs.top}..${crumbs.bottom}", bar.top >= crumbs.top - 1f && bar.bottom <= crumbs.bottom + 1f)
        assertEquals(firstRow.top, compose.onNodeWithText("deploy.sh").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("files-headerless-selected")
        compose.onNodeWithContentDescription("Clear selection").performClick()
        waitForNoText("1 selected")
        compose.onNodeWithContentDescription("Edit path").assertExists()
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
        waitForText("131 MB of 348 MB \u00B7 4.2 MB/s \u00B7 1 more")
        capture("files-transfer-strip")
        compose.onNodeWithContentDescription("Transfers, 2 running").performClick()
        waitForText("Clear finished")
        waitForText("1 failed", substring = true)
        capture("files-transfers-sheet")
    }

    @Test
    fun `transfers sheet keeps clear finished under a long history`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val mb = 1024L * 1024
        val names = listOf("site-backup-2026-09-18.tar.gz", "berth-arm64.apk", "notes.txt", "config.yaml", "nginx.conf", "app.log", "deploy.sh", "schema.sql", "README.txt", "photo.jpg", "vendor.tar", "metrics.csv")
        val transfers = names.mapIndexed { i, name ->
            Transfer(
                "t$i", "s-demo", "prod-web",
                if (i % 2 == 0) TransferKind.DOWNLOAD else TransferKind.UPLOAD,
                name, "/home/demo/$name",
                total = (i + 1) * 3 * mb, bytes = if (i == 3) (i + 1) * mb else (i + 1) * 3 * mb,
                state = if (i == 3) TransferState.CANCELLED else TransferState.DONE,
                startedAt = now - (i + 2) * 60_000L, finishedAt = now - (i + 1) * 60_000L,
            )
        }
        themed { Pane(b, transfers = transfers) }
        waitForText("deploy.sh")
        compose.onNodeWithContentDescription("Transfers").performClick()
        waitForText("Clear finished")
        // Twelve rows outgrow the screen; the list scrolls and the button keeps its height at the foot.
        // The sheet is its own window, so there are two roots; the button's own root is the sheet's.
        val button = compose.onNodeWithText("Clear finished").fetchSemanticsNode()
        assertTrue("Clear finished collapsed to ${button.size.height} px", button.size.height > 0)
        val root = compose.onAllNodes(isRoot()).onLast().fetchSemanticsNode()
        assertTrue(button.boundsInRoot.bottom <= root.size.height)
        waitForText("11 done \u00B7 1 cancelled")
        capture("files-transfers-sheet-long")
    }

    // ---- folders in the queue --------------------------------------------------------------------

    /** A folder download mid-way: 513 of 1240 files behind it, the file moving now, and three things that did not copy so far. */
    private fun folderMidway(mb: Long) = Transfer(
        "f1", "s-demo", "prod-web", TransferKind.DOWNLOAD, "berth", "/home/demo/projects/berth",
        total = 612 * mb, bytes = 233 * mb, bytesPerSecond = 3.8 * mb, state = TransferState.RUNNING, startedAt = now - 62_000,
        folder = FolderProgress(
            phase = FolderPhase.COPYING, filesTotal = 1240, filesCopied = 511, bytesTotal = 612 * mb, bytesDone = 233 * mb, bytesFailed = 2 * mb,
            current = "app/build/intermediates/dex/debug/classes.dex", currentBytes = 9 * mb, currentTotal = 24 * mb,
            failures = listOf(
                FolderFailure("core/ssh/build/tmp/kotlin-classes/debug/.lock", "The server refused access to /home/demo/projects/berth/core/ssh/build/tmp/kotlin-classes/debug/.lock."),
                FolderFailure("app/build/tmp/lock", "The server refused access to /home/demo/projects/berth/app/build/tmp/lock."),
                FolderFailure("www", "Link to a folder; left out.", retryable = false, isLink = true),
            ),
        ),
    )

    /** A folder upload that is over: most of it copied, three files and a folder refused, one file lost with the connection, two skipped, a link left out. */
    private fun folderFinished(mb: Long) = Transfer(
        "f0", "s-demo", "prod-web", TransferKind.UPLOAD, "photos", "/home/demo/photos",
        total = 1843 * mb, bytes = 1801 * mb, state = TransferState.FAILED, error = "3 files and 1 folder didn't copy.", startedAt = now - 900_000, finishedAt = now - 300_000,
        folder = FolderProgress(
            phase = FolderPhase.FINISHED, filesTotal = 2048, filesCopied = 2043, filesSkipped = 2, bytesTotal = 1843 * mb, bytesDone = 1801 * mb, bytesSkipped = 12 * mb, bytesFailed = 30 * mb,
            failures = listOf(
                FolderFailure("2024/IMG_0412.HEIC", "The server refused access to /home/demo/photos/2024/IMG_0412.HEIC."),
                FolderFailure("2024/IMG_0413.HEIC", "The server refused access to /home/demo/photos/2024/IMG_0413.HEIC."),
                FolderFailure("2025/raw", "The server refused access to /home/demo/photos/2025/raw.", isDirectory = true),
                FolderFailure("2025/IMG_1190.MOV", "The connection dropped."),
                FolderFailure("Camera Roll", "Link to a folder; left out.", retryable = false, isLink = true),
            ),
        ),
    )

    /** A Caption pair as the formatter holds it together: every space a non-breaking one. */
    private fun nb(text: String) = text.replace(' ', '\u00A0')

    @Test
    fun `folder transfer on the strip, its row expanded, and the end summary`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val mb = 1024L * 1024
        val transfers = listOf(
            folderMidway(mb),
            Transfer("t2", "s-demo", "prod-web", TransferKind.UPLOAD, "berth-arm64.apk", "/home/demo/berth-arm64.apk", total = 48 * mb, state = TransferState.QUEUED),
            folderFinished(mb),
            Transfer("t3", "s-demo", "prod-web", TransferKind.DOWNLOAD, "notes.txt", "/home/demo/notes.txt", total = 120, bytes = 120, state = TransferState.DONE, startedAt = now - 61_000, finishedAt = now - 60_000),
            Transfer("t4", "s-demo", "build box", TransferKind.UPLOAD, "config.yaml", "/etc/app/config.yaml", total = 2048, bytes = 2048, state = TransferState.DONE, startedAt = now - 121_000, finishedAt = now - 120_000),
        )
        val retried = ArrayList<String>()
        themed { Pane(b, transfers = transfers, onRetryFailed = { retried += it }) }
        waitForText("deploy.sh")
        // The whole folder is one row on the strip: the percentage where it always goes (the line's measure), then files done of total
        // (copied and failed alike), speed and what waits behind it on the one Caption line; bytes of total belong to the sheet.
        waitForText("${nb("513 of 1240 files")} \u00B7 3.8 MB/s \u00B7 1 more")
        waitForText("38%")
        capture("files-folder-transfer-strip")

        compose.onNodeWithContentDescription("Transfers, 2 running").performClick()
        waitForText("Clear finished")
        // The folder rows open on a tap; the per-file rows are as they were, with nothing to open.
        compose.onNode(hasText("berth") and hasStateDescription("Collapsed")).assertExists()
        compose.onNode(hasText("photos") and hasStateDescription("Collapsed")).assertExists()
        compose.onAllNodes(hasText("berth-arm64.apk") and hasStateDescription("Collapsed")).assertCountEquals(0)
        compose.onAllNodes(hasText("notes.txt") and hasStateDescription("Collapsed")).assertCountEquals(0)
        // The sheet's row keeps files and bytes of total together, each pair unbroken; the finished one counts the user's Skips apart from the link the policy left out.
        waitForText("prod-web \u00B7 ${nb("513 of 1240 files")} \u00B7 ${nb("233 MB of 612 MB")} \u00B7 3.8 MB/s")
        waitForText("2043 files copied \u00B7 4 failed \u00B7 2 skipped \u00B7 1 link left out", substring = true)
        waitForText("4 failed")
        capture("files-folder-transfers-sheet")

        // Open, a running folder shows the file moving now with its own line and the last few failures; no retry while it runs.
        compose.onNode(hasText("berth") and hasStateDescription("Collapsed")).performClick()
        waitForText("app/build/intermediates/dex/debug/classes.dex")
        waitForText("9 MB of 24 MB")
        waitForText("Didn't copy so far")
        compose.onNode(hasText("berth") and hasStateDescription("Expanded")).assertExists()
        compose.onAllNodes(hasText("Retry failed")).assertCountEquals(0)
        capture("files-folder-transfer-expanded")

        // Open, a finished one is the summary: what failed, with the reason, and Retry failed for the part that can go again.
        compose.onNode(hasText("photos") and hasStateDescription("Collapsed")).performClick()
        waitForText("Didn't copy")
        waitForText("Retry failed")
        compose.onNodeWithText("2025/raw \u00B7 The server refused access to /home/demo/photos/2025/raw.").assertExists()
        capture("files-folder-transfer-summary")
        compose.onNodeWithText("Retry failed").performClick()
        assertEquals(listOf("f0"), retried)

        // A second tap closes the detail again.
        compose.onNode(hasText("berth") and hasStateDescription("Expanded")).performClick()
        waitForNoText("app/build/intermediates/dex/debug/classes.dex")
    }

    @Test
    fun `folder conflict sheet`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val mb = 1024L * 1024
        val answers = ArrayList<Triple<String, ConflictChoice, Boolean>>()
        // The file coming in was changed yesterday evening; the one on the device dates from half a year back.
        val incomingAt = now - TimeUnit.HOURS.toMillis(25)
        val existingAt = now - TimeUnit.DAYS.toMillis(199)
        val waiting = folderMidway(mb).let { t ->
            t.copy(
                folder = t.folder!!.copy(
                    current = null, currentBytes = 0L, currentTotal = 0L,
                    conflict = FolderConflict("app/build.gradle.kts", incomingSize = 2418, existingSize = 2390, remaining = 728, incomingModified = incomingAt, existingModified = existingAt),
                ),
            )
        }
        themed { Pane(b, transfers = listOf(waiting), onResolveConflict = { id, choice, all -> answers += Triple(id, choice, all) }) }
        waitForText("deploy.sh")
        // The question opens over the pane on its own while the copy waits: which file, where in the folder and on which host, what is coming
        // and what is there by size and modified time, the newer one saying so; the answer for the rest covers only the files that turn out to exist.
        waitForText("In berth/app \u00B7 prod-web")
        waitForText("On the server")
        waitForText("2.4 KB \u00B7 ${formatModified(incomingAt, now)} \u00B7 newer \u00B7 coming in")
        waitForText("2.3 KB \u00B7 ${formatModified(existingAt, now)} \u00B7 already there")
        waitForText("For any of the 728 files still to copy that already exist")
        compose.onNodeWithText("Apply to all").performClick()
        compose.onNode(isToggleable()).assertIsOn()
        // Overwrite, the destructive one, sits furthest from the thumb; Skip, the safe one, in the middle; Keep both last.
        val buttons = listOf("Overwrite", "Skip", "Keep both").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(buttons, buttons.sorted())
        capture("files-folder-conflict")
        compose.onNodeWithText("Keep both").performClick()
        assertEquals(listOf(Triple("f1", ConflictChoice.KEEP_BOTH, true)), answers)

        // Dismissed, the copy keeps waiting: the strip says so in a word and that a tap answers, and the tap brings the question back.
        dismissSheet()
        waitForNoText("In berth/app \u00B7 prod-web")
        waitForText("Waiting")
        waitForText("build.gradle.kts already exists \u00B7 tap to answer")
        capture("files-folder-conflict-put-aside")
        compose.onNodeWithText("build.gradle.kts already exists \u00B7 tap to answer").performClick()
        waitForText("In berth/app \u00B7 prod-web")
    }

    @Test
    fun `a single file of a selection asks through the same sheet`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val answers = ArrayList<Triple<String, ConflictChoice, Boolean>>()
        val incomingAt = now - TimeUnit.DAYS.toMillis(41)
        val existingAt = now - TimeUnit.HOURS.toMillis(3)
        // notes.txt of a three-file selection, going into the device's Download folder where a newer notes.txt already is; two files of the selection still to come.
        val waiting = Transfer(
            "t9", "s-demo", "prod-web", TransferKind.DOWNLOAD, "notes.txt", "/home/demo/notes.txt",
            total = 4820, state = TransferState.RUNNING, startedAt = now - 3_000,
            conflict = FolderConflict("Download/notes.txt", incomingSize = 4820, existingSize = 4102, remaining = 2, incomingModified = incomingAt, existingModified = existingAt),
        )
        val queued = Transfer("t10", "s-demo", "prod-web", TransferKind.DOWNLOAD, "deploy.sh", "/home/demo/deploy.sh", total = 1207, state = TransferState.QUEUED)
        themed { Pane(b, transfers = listOf(waiting, queued), onResolveConflict = { id, choice, all -> answers += Triple(id, choice, all) }) }
        waitForText("deploy.sh")
        // The same sheet a file inside a folder gets: the picked folder is where it is going, and this time the file already there is the newer one.
        waitForText("notes.txt already exists")
        waitForText("In Download \u00B7 prod-web")
        waitForText("4.7 KB \u00B7 ${formatModified(incomingAt, now)} \u00B7 coming in")
        waitForText("4 KB \u00B7 ${formatModified(existingAt, now)} \u00B7 newer \u00B7 already there")
        waitForText("For any of the 2 files still to copy that already exist")
        capture("files-folder-file-conflict")
        compose.onNodeWithText("Skip").performClick()
        assertEquals(listOf(Triple("t9", ConflictChoice.SKIP, false)), answers)

        // Put aside, the strip reads the same as a folder's: Waiting, the file, and that a tap answers.
        dismissSheet()
        waitForNoText("In Download \u00B7 prod-web")
        waitForText("Waiting")
        waitForText("notes.txt already exists \u00B7 tap to answer \u00B7 1 more")
        compose.onNodeWithText("notes.txt already exists \u00B7 tap to answer \u00B7 1 more").performClick()
        waitForText("In Download \u00B7 prod-web")
    }

    @Test
    fun `a selection with a folder downloads but does not share, and the folder menu uploads a folder`() {
        val fs = FakeSftpFileSystem.demoTree(now)
        val b = browser(fs, "/home/demo")
        val downloaded = ArrayList<Set<String>>()
        var folderUploads = 0
        val actions = FilesActions(download = { entries -> downloaded += entries.mapTo(HashSet()) { it.name } }, share = {}, upload = {}, uploadFolder = { folderUploads++ })
        themed { Pane(b, actions = actions) }
        waitForText("deploy.sh")

        compose.onNodeWithContentDescription("Select backups").performClick()
        compose.onNodeWithContentDescription("Select notes.txt").performClick()
        waitForText("2 selected")
        // Download takes the folder with the file; Share keeps its name and is off, and a tap on it says why where the bar already tells reasons.
        compose.onNodeWithText("Share").assertIsNotEnabled()
        compose.onNodeWithText("Download").assertIsEnabled()
        compose.onNodeWithText("Share").performClick()
        waitForText("Share takes one file; a folder has no single document to hand on. Download takes folders.")
        capture("files-folder-multi-select")
        compose.onNodeWithText("Download").performClick()
        assertEquals(listOf(setOf("backups", "notes.txt")), downloaded)

        // One folder alone: still Share, still off.
        compose.onNodeWithContentDescription("Deselect notes.txt").performClick()
        waitForText("1 selected")
        compose.onNodeWithText("Share").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear selection").performClick()
        waitForNoText("1 selected")

        compose.onNodeWithContentDescription("Folder options").performClick()
        waitForText("Upload folder")
        capture("files-folder-upload-menu")
        compose.onNodeWithText("Upload folder").performClick()
        assertEquals(1, folderUploads)
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
        // A cold start with no tabs is the empty Stage; the plus tab's sheet lists the host.
        compose.onAllNodesWithContentDescription("New tab").onFirst().performClick()
        waitForText("Berth test box", 5_000)
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

        // The Session sheet sits behind the Stage overflow; its Files button, beside Detach, opens the host's Files tab riding this terminal.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Session").performClick()
        waitForText("Detach", 5_000)
        capture("files-live-session-sheet")
        compose.onNode(hasText("Files") and hasAnySibling(hasText("Detach"))).performClick()
        // The browser opens where the shell is, as a second tab after the terminal's, named for the folder and live with it.
        waitForText("app.log", 20_000)
        val filesTab = graph.sessions.filesTabFor(box.id)!!
        assertEquals(filesTab.id, graph.sessions.activeTabId.value)
        assertEquals(session.id, filesTab.ride.value?.id)
        assertEquals(listOf(session.id, filesTab.id), graph.viewModel.tabs.value.map { it.id })
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 logs, live", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("files-live-opened")

        compose.onNodeWithText("berth-files-demo").performClick()
        waitForText("README.txt", 15_000)
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Files \u00B7 berth-files-demo, live", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("files-live-folder")

        // The terminal is one tap away on the strip (its title is the shell's, so the tab is found by its slot) and the browser is where it was left on the way back.
        compose.onNode(hasContentDescription(", live, tab 1 of 2", substring = true)).performClick()
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == session.id }
        waitForNoText("README.txt", 5_000)
        compose.onNode(hasContentDescription("Files \u00B7 berth-files-demo, live", substring = true)).performClick()
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == filesTab.id }
        waitForText("README.txt", 5_000)
        assertEquals(dir, filesTab.folder)

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

        // The shell moves on; the pane follows it into the empty releases folder through the Stage's one overflow,
        // which hosts the folder rows ahead of the tab's own (the pane draws no ⋮ of its own under the strip).
        session.sendText("cd $dir/releases\n")
        compose.waitUntil(10_000) { session.cwd == "$dir/releases" }
        compose.onAllNodesWithContentDescription("Folder options").assertCountEquals(0)
        compose.onNodeWithContentDescription("More").performClick()
        waitForText("Terminal directory", 5_000)
        compose.onNodeWithText("Terminal").assertExists()
        capture("files-live-overflow")
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

    /**
     * A folder both ways against the local sshd: a three-level tree of 84 small files, a 32 MB file
     * and two empty folders goes up through the client under test, comes down as one folder transfer
     * into a directory standing for the picked tree, and is checked file by file against the hashes
     * of what went up; then a local tree goes up as one folder transfer, the listing picks it up on
     * its own, and every file is read back and hashed. The channel is paced a little so the aggregate
     * row is on screen long enough to photograph; the bytes still cross the real sshd.
     */
    @Test
    fun `files folder live flow`() {
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
        compose.onAllNodesWithContentDescription("New tab").onFirst().performClick()
        waitForText("Berth test box", 5_000)
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val session = graph.sessions.activeSession.value!!

        // The tree goes up through the sftp channel of the session on the Stage; every file's hash is kept for the check.
        val random = Random(20260920)
        val expected = LinkedHashMap<String, String>()
        val (home, dir) = runBlocking {
            val fs = session.openSftp()
            try {
                val home = fs.home()
                val d = "$home/berth-folder-demo"
                runCatching { fs.delete(d) }
                runCatching { fs.delete("$home/berth-folder-upload") }
                fs.mkdir(d)
                for (sub in listOf("src", "src/main", "src/main/kotlin", "src/main/res", "src/main/nested-empty", "src/test", "assets", "empty")) fs.mkdir("$d/$sub")
                val put: suspend (String, ByteArray) -> Unit = { rel, bytes ->
                    fs.upload(ByteArrayInputStream(bytes), bytes.size.toLong(), "$d/$rel")
                    expected[rel] = hex(sha256(bytes))
                }
                put("README.txt", "Berth folder demo\n\nA tree the live screenshot test copies both ways. Everything in it is disposable.\n".toByteArray())
                for (i in 1..40) put("src/main/kotlin/Screen%02d.kt".format(i), "package app.berth.demo\n\nclass Screen$i {\n    val title = \"Screen $i\"\n}\n".toByteArray())
                for (i in 1..20) put("src/main/res/strings-%02d.xml".format(i), "<resources>\n    <string name=\"title_$i\">Screen $i</string>\n</resources>\n".toByteArray())
                for (i in 1..23) put("src/test/Screen%02dTest.kt".format(i), "package app.berth.demo\n\nclass Screen${i}Test\n".toByteArray())
                put("assets/site-backup.tar.gz", ByteArray(32 * 1024 * 1024).also(random::nextBytes))
                home to d
            } finally {
                fs.close()
            }
        }
        assertEquals(85, expected.size)

        // The host's Files tab from the session sheet; it opens at home, where the tree is a row.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Session").performClick()
        waitForText("Detach", 5_000)
        compose.onNode(hasText("Files") and hasAnySibling(hasText("Detach"))).performClick()
        waitForText("berth-folder-demo", 20_000)

        // The folder comes down as one transfer into a directory standing for the picked tree; the channel is paced so it can be watched.
        val queue = graph.files.transfers
        queue.channelFor = { s -> PacedChannel(s.openSftp(), perFileMs = 60, perChunkMs = 8) }
        val dest = createTempDirectory("berth-folder-down").toFile()
        val entry = runBlocking { session.openSftp().use { it.stat(dir) } }
        val down = queue.downloadFolder(session, entry, Uri.fromFile(dest))
        fun transfer(id: String) = queue.transfers.value.first { it.id == id }
        // Photographed once a third of the bytes are across: the big file is moving, the speed has settled.
        fun Transfer.aThirdIn() = state == TransferState.RUNNING && folder?.let { f -> f.phase == FolderPhase.COPYING && f.filesCopied > 0 && f.bytesDone * 3 > f.bytesTotal } == true
        compose.waitUntil(30_000) { transfer(down).aThirdIn() }
        capture("files-folder-live-download")
        compose.onNodeWithContentDescription("Transfers, 1 running").performClick()
        waitForText("1 running", 5_000)
        compose.onNode(hasText("berth-folder-demo") and hasStateDescription("Collapsed")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("berth-folder-demo") and hasStateDescription("Expanded")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("still moving while the row is open", TransferState.RUNNING, transfer(down).state)
        capture("files-folder-live-sheet")
        compose.waitUntil(180_000) { transfer(down).state == TransferState.DONE }
        waitForText("Done", 5_000)
        capture("files-folder-live-done")
        dismissSheet()
        waitForNoText("Clear finished", 5_000)

        val downloaded = transfer(down).folder!!
        assertEquals(85, downloaded.filesCopied)
        assertEquals(emptyList<Any>(), downloaded.failures)
        val root = File(dest, "berth-folder-demo")
        assertEquals("every file reaches the device with the bytes that went up", expected, hashTree(root))
        assertTrue(File(root, "empty").isDirectory && File(root, "empty").list()!!.isEmpty())
        assertTrue(File(root, "src/main/nested-empty").isDirectory)

        // A local tree goes up as one transfer into the folder being shown; the listing picks the folder up on its own.
        val ups = createTempDirectory("berth-folder-up").toFile()
        val local = File(ups, "berth-folder-upload").apply { mkdirs() }
        val expectedUp = LinkedHashMap<String, String>()
        fun write(rel: String, bytes: ByteArray) {
            File(local, rel).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            expectedUp[rel] = hex(sha256(bytes))
        }
        write("notes.txt", "Uploaded by the live screenshot test.\n".toByteArray())
        for (i in 1..30) write("photos/2026/IMG_%04d.jpg".format(i), ByteArray(16 * 1024 + i).also(random::nextBytes))
        write("photos/clip.mp4", ByteArray(8 * 1024 * 1024).also(random::nextBytes))
        File(local, "photos/empty").mkdirs()
        val up = queue.uploadFolder(session, Uri.fromFile(local), home)
        compose.waitUntil(30_000) { transfer(up).aThirdIn() }
        capture("files-folder-live-upload")
        compose.waitUntil(180_000) { transfer(up).state == TransferState.DONE }
        waitForText("berth-folder-upload", 15_000)
        capture("files-folder-live-uploaded")
        compose.onNodeWithContentDescription("Transfers").performClick()
        waitForText("2 done", 5_000)
        capture("files-folder-live-transfers")
        dismissSheet()

        // Read back through a fresh channel: every file with the bytes that went up, folders 755 and files 644, the empty folder there.
        assertEquals(32, transfer(up).folder!!.filesCopied)
        runBlocking {
            val fs = session.openSftp()
            try {
                val remoteRoot = "$home/berth-folder-upload"
                assertEquals(expectedUp, remoteHashTree(fs, remoteRoot))
                assertEquals(0b111_101_101, fs.stat("$remoteRoot/photos").permissions and 0b111_111_111)
                assertEquals(0b111_101_101, fs.stat("$remoteRoot/photos/empty").permissions and 0b111_111_111)
                assertEquals(0b110_100_100, fs.stat("$remoteRoot/notes.txt").permissions and 0b111_111_111)
                assertTrue(fs.list("$remoteRoot/photos/empty").isEmpty())
                runCatching { fs.delete(dir) }
                runCatching { fs.delete(remoteRoot) }
            } finally {
                fs.close()
            }
        }
        dest.deleteRecursively()
        ups.deleteRecursively()
        graph.sessions.close(session.id)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Relative path to SHA-256 of every file under [root]. */
    private fun hashTree(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile }.associateTo(LinkedHashMap()) { it.relativeTo(root).path.replace(File.separatorChar, '/') to hex(sha256(it.readBytes())) }

    private suspend fun remoteHashTree(fs: SftpFileSystem, root: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        suspend fun walk(path: String, rel: String) {
            for (e in fs.list(path)) {
                val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                if (e.isDirectory) walk(e.path, childRel) else out[childRel] = hex(sha256(ByteArrayOutputStream().also { fs.download(e.path, it) { _, _ -> } }.toByteArray()))
            }
        }
        walk(root, "")
        return out
    }

    /**
     * The real channel with a short pause before each file and after each chunk, so a copy over
     * localhost lasts long enough for the strip and the sheet to be photographed mid-way.
     */
    private class PacedChannel(private val inner: SftpFileSystem, private val perFileMs: Long, private val perChunkMs: Long) : SftpFileSystem by inner {
        override suspend fun download(path: String, sink: OutputStream, onProgress: (Long, Long) -> Unit) {
            delay(perFileMs)
            inner.download(path, sink) { bytes, total -> onProgress(bytes, total); Thread.sleep(perChunkMs) }
        }

        override suspend fun upload(source: InputStream, size: Long, path: String, onProgress: (Long, Long) -> Unit) {
            delay(perFileMs)
            inner.upload(source, size, path) { bytes, total -> onProgress(bytes, total); Thread.sleep(perChunkMs) }
        }
    }
}

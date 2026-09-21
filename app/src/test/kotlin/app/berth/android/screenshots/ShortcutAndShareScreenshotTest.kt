package app.berth.android.screenshots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.R
import app.berth.android.createBerthComposeRule
import app.berth.android.files.TransferManager
import app.berth.android.files.TransferState
import app.berth.android.links.Arrival
import app.berth.android.links.LauncherShortcuts
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.theme.BerthType
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.sftp.SftpPaths
import app.berth.sftp.TextRead
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The surfaces of share-to-session and the launcher's shortcuts (spec C24, Part B App shortcuts,
 * A80): the notice a share meets with nothing live on stage, at 1× and at the interface's 1.3×
 * font cap (A11), the Stage after a file shared to Berth has landed in a folder of the login's own
 * under the live shell's `/tmp` with its path pasted, the paste preview a shared note of two lines
 * meets (both of which need the sshd), and the five icons a long press on Berth offers, drawn as a
 * launcher masks them. The icons do not scale with the font, so they are held once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ShortcutAndShareScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val bg = CoroutineScope(Dispatchers.Default + Job())
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
        graph = TestGraph(app)
    }

    @After
    fun tearDown() {
        bg.cancel()
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The system's largest font size, set before the first composition: interface text then sits at its 1.3× cap. */
    private fun atTheCap() = RuntimeEnvironment.setFontScale(2f)

    private fun waitForText(text: String, substring: Boolean = false) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    /** Real time passes while the compose clock keeps ticking, so a bar's entrance finishes. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    // ---- C24: a share with nothing live on stage -----------------------------------------------------------

    @Test
    fun `share notice`() = shareNotice("share-notice")

    @Test
    fun `share notice at the font cap`() {
        atTheCap()
        shareNotice("share-notice-font-cap")
    }

    /**
     * Three detached frames on the strip, the last active on stage, and a file shared to Berth: no
     * live terminal to drop it into, so the notice bar says so on its one line, nothing is queued,
     * and OK takes it down. The line is whole at 1× and at the cap: the bar has one line by design.
     */
    private fun shareNotice(name: String) {
        StageFixture.seed(graph)
        runBlocking { graph.settings.setLastActiveSessionId("s-homelab") }
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        // Under the lock a share waits for the unlock; a phone without the lock set is unlocked on its first start.
        graph.appLock.onForeground()
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 && graph.viewModel.activeTabId.value == "s-homelab" }
        graph.inbox.offer(Arrival.Files(listOf(Uri.parse("content://media/external/file/12"))))
        waitForText(AppViewModel.NO_LIVE_SESSION_FOR_SHARE)
        assertTrue("nothing was queued", graph.files.transfers.transfers.value.isEmpty())
        settle(500)
        capture(name)
        val line = compose.onNode(hasText(AppViewModel.NO_LIVE_SESSION_FOR_SHARE), useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertNotNull("the notice has a layout", line)
        assertEquals("the notice is one line", 1, line!!.lineCount)
        assertFalse("the notice is cut", line.didOverflowHeight || line.isLineEllipsized(0))

        compose.onNodeWithText("OK").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(AppViewModel.NO_LIVE_SESSION_FOR_SHARE)).fetchSemanticsNodes().isEmpty() }
    }

    // ---- C24: the share landed -----------------------------------------------------------------------------

    /**
     * A live shell on the sshd on stage and a file shared to Berth: it goes to a folder of the
     * login's own under the host's `/tmp` through the transfer queue and its path, quoted for the
     * space in its name, is pasted onto the shell's line, where the Stage shows it. The folder is
     * removed through the shell afterwards.
     */
    @Test
    fun `a shared file lands in tmp and its path is pasted onto the live shell's line`() {
        val session = liveOnStage()
        val local = createTempDirectory("berth-share").toFile()
        val report = File(local, "berth drop ${UUID.randomUUID().toString().take(8)}.txt").apply { writeText("quarterly numbers\n") }
        var folder: String? = null
        try {
            graph.inbox.offer(Arrival.Files(listOf(Uri.fromFile(report))))
            compose.waitUntil(45_000) {
                val rows = graph.files.transfers.transfers.value
                rows.size == 1 && rows.single().state == TransferState.DONE
            }
            val landed = graph.files.transfers.transfers.value.single().remotePath
            val dir = SftpPaths.parent(landed)
            folder = dir
            assertTrue(landed, Regex("/tmp/${TransferManager.DROP_FOLDER_PREFIX}[0-9a-f]{8}").matches(dir))
            assertEquals("$dir/${report.name}", landed)
            val quoted = TransferManager.shellQuote(landed)
            compose.waitUntil(10_000) { session.emulator.cursorLineText().endsWith(quoted) }
            settle(600)
            capture("share-landed-on-stage")
            // The folder is the login's alone, so the bytes are read back over its own sftp channel.
            runBlocking {
                val fs = session.openSftp()
                try {
                    assertEquals("quarterly numbers\n", (fs.readText(landed, 1024) as TextRead.Text).content)
                    assertEquals(0b110_000_000, fs.stat(landed).permissions and 0b111_111_111)
                } finally {
                    fs.close()
                }
            }
        } finally {
            folder?.let { dir ->
                session.sendText("\u0015rm -rf ${TransferManager.shellQuote(dir)}\r")
                compose.waitUntil(10_000) { !File(dir).exists() }
            }
            local.deleteRecursively()
        }
    }

    /**
     * A note of two lines shared to Berth with a live shell on stage: it is a paste like the
     * clipboard's, so it meets the Stage's preview (spec C18) and nothing reaches the shell until
     * the sheet says so. Cancel keeps it off the wire; Paste sends it, and the shell shows both lines.
     */
    @Test
    fun `shared text of two lines meets the paste preview, and nothing lands until it is confirmed`() {
        val session = liveOnStage()
        val marker = "shared-${UUID.randomUUID().toString().take(8)}"
        val text = "echo $marker one\necho $marker two"
        fun onScreen() = session.emulator.screenText().any { it.contains(marker) }

        graph.inbox.offer(Arrival.Text(text))
        waitForText("Paste 2 lines")
        compose.waitUntil(5_000) { graph.viewModel.sharedPaste.value == null }
        assertFalse("nothing reached the shell ahead of the sheet", onScreen())
        settle(600)
        capture("share-text-preview")
        compose.onNodeWithText("Cancel").performClick()
        waitForNoText("Paste 2 lines")
        settle(1_000)
        assertFalse("Cancel kept the text off the wire", onScreen())

        // Shared again and confirmed: the two lines land at the prompt, as one block under the shell's bracketed paste.
        graph.inbox.offer(Arrival.Text(text))
        waitForText("Paste 2 lines")
        compose.onNodeWithText("Paste").performClick()
        waitForNoText("Paste 2 lines")
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.contains("echo $marker two") } }
        // The line is left unrun and cleared, so the box is as it was.
        session.sendText("\u0003")
    }

    /** The shell up with a live login on the sshd on stage, or the test is skipped. */
    private fun liveOnStage(): TerminalSession {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = Host(
            id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = sshHost, port = sshPort, user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = 20L,
        )
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        graph.appLock.onForeground()
        compose.waitUntil(10_000) { graph.sessions.restored.value }
        val session = runBlocking { graph.sessions.open(box) }
        compose.waitUntil(45_000) { session.state == SessionState.LIVE }
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(session.id, graph.sessions.activeTabId.value)
        return session
    }

    // ---- Part B, A80: the launcher's shortcuts -----------------------------------------------------------

    /**
     * What a long press on Berth's icon offers: the four hosts connected most recently, newest
     * first, each as its swatch with its monogram, and Quick connect on the launcher's graphite
     * with the prompt glyph. Each icon is drawn as a launcher draws an adaptive icon, through the
     * system's mask, at the 48 dp a shortcut row shows it.
     */
    @Test
    fun `launcher shortcut icons`() {
        val hosts = listOf(
            host("homelab", "homelab", "ben", "192.168.1.20", SwatchColor.VERDIGRIS, minutesAgo = 18),
            host("pi-hole", "pi-hole", "pi", "192.168.1.2", SwatchColor.MOSS, minutesAgo = 95),
            host("prod-web", "prod web", "deploy", "web.example.net", SwatchColor.COPPER, minutesAgo = 400),
            host("build-box", "build box", "ci", "build.internal", SwatchColor.SLATE, minutesAgo = 3_000),
            // A fifth, older than the four: the launcher is not offered it.
            host("nas", "nas", "ben", "192.168.1.40", SwatchColor.PLUM, minutesAgo = 9_000),
        )
        val shortcuts = LauncherShortcuts(app, graph.hosts)
        val recent = shortcuts.recent(hosts.shuffled())
        assertEquals(listOf("homelab", "pi-hole", "prod-web", "build-box"), recent.map { it.id })

        val icons = recent.map { it.name to masked(IconCompat.createWithAdaptiveBitmap(shortcuts.swatch(it)).loadDrawable(app)!!) } +
            (app.getString(R.string.shortcut_quick_connect) to masked(ResourcesCompat.getDrawable(app.resources, R.drawable.ic_shortcut_quick_connect, app.theme)!!))
        themed {
            val c = Berth.colors
            Column(
                Modifier.fillMaxSize().background(c.surface0).padding(horizontal = 24.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The shape of a launcher's shortcut popup: one row an icon and its label, on the next surface up.
                Column(
                    Modifier
                        .width(248.dp)
                        .background(c.surface2, RoundedCornerShape(20.dp))
                        .padding(vertical = 8.dp),
                ) {
                    for ((label, icon) in icons) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Image(icon.asImageBitmap(), contentDescription = "$label shortcut icon", Modifier.size(48.dp))
                            Text(label, style = BerthType.body, color = c.text1)
                        }
                    }
                }
            }
        }
        waitForText("Quick connect")
        capture("launcher-shortcuts")
        compose.assertNoTextCut("the launcher's shortcuts")
    }

    private fun host(id: String, name: String, user: String, address: String, color: SwatchColor, minutesAgo: Long) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user, auth = AuthMethod.AskEachTime,
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(minutesAgo), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    /** The adaptive icon drawn through the system's mask at 48 dp on this screen's density, as a launcher's row shows it. */
    private fun masked(drawable: Drawable): Bitmap {
        val px = (48 * app.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}

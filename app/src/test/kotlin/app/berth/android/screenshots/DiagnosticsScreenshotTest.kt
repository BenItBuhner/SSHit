package app.berth.android.screenshots

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.diagnostics.BerthLog
import app.berth.android.diagnostics.CrashReporter
import app.berth.android.diagnostics.LogRing
import app.berth.android.diagnostics.ReportKind
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.components.LocalWallClock
import app.berth.android.ui.diagnostics.DiagnosticsScreen
import app.berth.android.ui.diagnostics.formatDateTime
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The crash sheet on the launch after a crash, Settings \u203A Diagnostics with its reports, one
 * report open from the list and one whose file is gone, through Robolectric's native graphics; each
 * capture is an accessibility audit as well ([captureAudited]). The crash is a real one through
 * the installed handler's path ([CrashReporter.onCrash]) into this graph's store, with Android's
 * own handler stood in for; the launch after is the graph's reporter re-reading the store.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DiagnosticsScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph
    /**
     * The fixture's clock: the reports are written at it and the interface reads it, so a Written line, a
     * date and an age come out the same on every run. It steps between reports so they have an order.
     */
    private var now = FIXED_NOW

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        outDir.mkdirs()
        graph = TestGraph(context, wallClock = { now }, install = { FIXED_INSTALL })
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalWallClock provides { now }) {
                BerthTheme(InterfaceTheme.DEFAULT) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private val clipText: String? get() = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    /**
     * The previous run: its log, then a crash through the handler's path, with Android's handler stood in
     * for. Its lines are stamped on the fixture's clock and the crash is on a thread named `main`, as it
     * would be, so the report's text is the same on every run.
     */
    private fun crashPreviousRun(): String {
        BerthLog.ring.log(LogRing.Level.INFO, "App", "process started", at = now - 4_000)
        BerthLog.ring.log(LogRing.Level.INFO, "Session", "[homelab] detached \u2192 connecting", at = now - 3_000)
        BerthLog.ring.log(LogRing.Level.INFO, "Session", "[homelab] connecting \u2192 live", at = now - 2_000)
        BerthLog.ring.log(LogRing.Level.DEBUG, "Session", "app left the screen; saving 1 frame", at = now - 1_000)
        val previousRun = CrashReporter(graph.reportsDir, { FIXED_INSTALL }, BerthLog.ring, now = { now }, zone = { ZoneOffset.UTC })
        val error = IllegalStateException("Frame 1 of 1 has no cells for row 24", ArrayIndexOutOfBoundsException("Index 24 out of bounds for length 24"))
        previousRun.onCrash(Thread("main"), error)
        val written = graph.reportsDir.listFiles()!!.single { it.name.startsWith("crash-") }
        assertTrue(File(graph.reportsDir, "unread").readText() == written.name)
        // This launch finds the store as the crash left it.
        graph.reports.reload()
        return written.readText()
    }

    @Test
    fun `crash sheet on the launch after a crash`() {
        seedHomelab()
        val text = crashPreviousRun()
        assertEquals("the crash of the previous run, unread", ReportKind.CRASH, graph.reports.unread.value?.kind)

        compose.setContent { CompositionLocalProvider(LocalWallClock provides { now }) { AppRoot(graph.viewModel) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Berth crashed last time").fetchSemanticsNodes().isNotEmpty() }
        // The file is read off the main thread; the box holds it once it is here, whole and once.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth crash report", substring = true)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Share report").assertIsDisplayed()
        compose.onNodeWithText("Copy report").assertIsDisplayed()
        compose.onNodeWithText("Keep for later").assertIsDisplayed()
        compose.onAllNodes(hasText("java.lang.IllegalStateException: Frame 1 of 1 has no cells for row 24", substring = true)).assertCountEquals(1)
        // What the report holds is said once, on the sheet and not inside the file, and says what in it names the
        // user's own hosts; the caption alone says nothing was sent.
        compose.onAllNodes(hasText("which name your hosts, accounts and files but hold no passwords, keys, passphrases, clipboard text or terminal output", substring = true)).assertCountEquals(1)
        compose.onAllNodes(hasText("Nothing has been sent anywhere", substring = true)).assertCountEquals(1)
        compose.onAllNodes(hasText("leaves the phone", substring = true)).assertCountEquals(0)
        assertFalse(text, text.contains("leaves the phone"))
        compose.settle(300)
        capture("crash-sheet")

        // A prompt the restore raises while the sheet is up (a password for a reconnecting tab) waits behind it:
        // one sheet at a time, the crash sheet first, the prompt when it is closed.
        val homelab = graph.hosts.items.value.first { it.id == "homelab" }
        val asking = CoroutineScope(Dispatchers.IO).launch { graph.prompts.password(homelab) }
        compose.waitUntil(5_000) { graph.prompts.current.value is Prompt.Password }
        compose.settle(200)
        compose.onAllNodes(hasText("Password for ", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Berth crashed last time").assertIsDisplayed()

        // Copy puts the whole report on the clipboard, through the app's own clipboard path; nothing was sent anywhere.
        compose.onNodeWithText("Copy report").performClick()
        compose.waitUntil(5_000) { clipText == text }

        // Keep for later: seen, kept under Diagnostics, not shown again; the waiting prompt rises now.
        compose.onNodeWithText("Keep for later").performClick()
        compose.waitUntil(5_000) { graph.reports.unread.value == null }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Berth crashed last time").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Password for ", substring = true)).fetchSemanticsNodes().size == 1 }
        (graph.prompts.current.value as Prompt.Password).cancel()
        compose.waitUntil(5_000) { graph.prompts.current.value == null }
        asking.cancel()
        assertFalse(File(graph.reportsDir, "unread").exists())
        assertEquals(1, graph.reports.reports.value.size)
        graph.reports.reload()
        assertNull("read stays read on the launch after", graph.reports.unread.value)
    }

    /** Two connection reports and a crash already seen, the way a phone that has had a rough week holds them. */
    private fun seedReports() {
        val homelab = host()
        val hostLine = "${homelab.name} \u00B7 ${homelab.user}@${homelab.address}:${homelab.port}"
        // Five seconds apart on the fixture's clock, so the list has an order and the files distinct names; the
        // session layer reports from its own thread, which a test runner names after itself, so the thread is named here.
        graph.reports.report(ReportKind.TRANSPORT, "Connection lost: homelab", IOException("Broken pipe"), details = listOf("Host" to hostLine, "Phase" to "Connection lost", "Reason" to "Broken pipe"), thread = Thread("DefaultDispatcher-worker-3"))
        now += 5_000
        graph.reports.report(ReportKind.TRANSPORT, "Gave up reconnecting: homelab", null, details = listOf("Host" to hostLine, "Phase" to "Gave up reconnecting", "Reason" to "after 960 s and 6 retries"), thread = Thread("DefaultDispatcher-worker-3"))
        now += 5_000
        crashPreviousRun()
        graph.reports.markRead()
        assertEquals(3, graph.reports.reports.value.size)
    }

    @Test
    fun `settings counts the reports on the phone`() {
        seedReports()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Crash and connection reports").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 on this phone, 1 crash \u00B7 never sent by themselves").assertIsDisplayed()
    }

    @Test
    fun `diagnostics lists the reports newest first and opens one`() {
        seedReports()
        val homelab = host()
        themed { DiagnosticsScreen(graph.reports, onBack = {}) }
        compose.onNodeWithText("Uncaught exception on thread main").assertIsDisplayed()
        compose.onNodeWithText("Connection lost: homelab").assertIsDisplayed()
        compose.onNodeWithText("Gave up reconnecting: homelab").assertIsDisplayed()
        compose.onNodeWithText("Connection \u00B7 java.io.IOException: Broken pipe").assertIsDisplayed()
        compose.onNodeWithText("Connection \u00B7 ${homelab.name} \u00B7 ${homelab.user}@${homelab.address}:${homelab.port}").assertIsDisplayed()
        // The age in the trailing slot the way the Hosts rows give it, not a date that takes a third of the row.
        compose.onAllNodesWithText("just now").assertCountEquals(3)
        compose.onAllNodes(hasText(formatDateTime(graph.reports.reports.value.first().at))).assertCountEquals(0)
        compose.settle(200)
        capture("diagnostics")

        compose.onNodeWithText("Connection lost: homelab").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Share report").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth connection report", substring = true)).fetchSemanticsNodes().size == 1 }
        compose.onAllNodes(hasText("Reason    Broken pipe", substring = true)).assertCountEquals(1)
        compose.settle(300)
        capture("diagnostics-report")

        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { graph.reports.reports.value.size == 2 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Connection lost: homelab").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Delete all").performClick()
        compose.waitUntil(5_000) { graph.reports.reports.value.isEmpty() }
        compose.onNodeWithText("No reports.").assertIsDisplayed()
        assertTrue(graph.reportsDir.listFiles()!!.isEmpty())
    }

    /**
     * A report whose file went from under the list (a storage cleaner, a full disk): the sheet says so and
     * Share and Copy, which would send its text, are disabled; Delete stays. The capture is audited, so
     * the two disabled buttons pass under the contrast check by being disabled controls (WCAG 1.4.3's
     * inactive-component exemption, [captureAudited]), the way the Deck's keys do on a Stage not connected.
     */
    @Test
    fun `a report that cannot be read has Share and Copy disabled, and the audit reads them as disabled controls`() {
        seedReports()
        val crash = graph.reports.reports.value.first { it.kind == ReportKind.CRASH }
        assertTrue(crash.file.delete())
        themed { DiagnosticsScreen(graph.reports, onBack = {}) }
        compose.onNodeWithText("Uncaught exception on thread main").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("The report could not be read.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Share report").assertIsNotEnabled()
        compose.onNodeWithText("Copy report").assertIsNotEnabled()
        compose.onNodeWithText("Delete").assertIsEnabled()
        compose.settle(300)
        capture("diagnostics-report-unreadable")
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun host() = Host(
        id = "homelab",
        name = "homelab",
        color = SwatchColor.VERDIGRIS,
        monogram = Host.monogramFor("homelab"),
        address = "192.168.1.20",
        user = "ben",
        auth = AuthMethod.Password("host-password:homelab"),
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18),
        createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    /** One detached tab with a saved frame, so the sheet lies over a Stage with something on it. */
    private fun seedHomelab() = runBlocking {
        val homelab = host()
        graph.hosts.upsert(homelab)
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "s-homelab",
                workspaceId = Workspace.DEFAULT_ID,
                hostId = homelab.id,
                hostSnapshot = homelab,
                state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME,
                title = homelab.name,
                cwd = "~/srv",
                lastCommand = "docker compose ps",
                sortOrder = 0,
                createdAt = now - TimeUnit.HOURS.toMillis(5),
                lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
            ),
        )
        graph.sessionRecords.saveFrame(
            "s-homelab",
            frame(
                listOf(
                    "ben@homelab:~/srv$ docker compose ps",
                    "NAME      IMAGE             STATUS",
                    "caddy     caddy:2           Up 3 days",
                    "gitea     gitea/gitea:1.22  Up 3 days",
                    "postgres  postgres:16       Up 3 days",
                    "ben@homelab:~/srv$ ",
                ),
            ),
        )
    }

    /** A version-2 frame: the text, and no history. */
    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(2)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
            d.writeInt(0)
        }
        return out.toByteArray()
    }
}

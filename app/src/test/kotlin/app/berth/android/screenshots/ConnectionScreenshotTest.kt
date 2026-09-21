package app.berth.android.screenshots

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.AuthResolver
import app.berth.android.session.BlackHoleProxy
import app.berth.android.session.Prompt
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.BACKGROUND_NOTICE
import app.berth.android.ui.settings.BackgroundSheet
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.IdleDetach
import app.berth.domain.model.InterfaceTheme
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The surfaces of Settings › Connection and the background (spec C20, Part B; vision §4.4), each
 * at 1× and at the interface's 1.3× font cap (A11): the Connection panel with Never in force, the
 * idle-detach picker open, the Background sheet on stock Android without the exemption and on a
 * Samsung with it, Back's one line over a Stage with a login up, and the sheet the app raises on
 * its own after a connection was lost while it was away, which needs the sshd.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ConnectionScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private var proxy: BlackHoleProxy? = null

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    /** Nothing listens on port 1: a login there is refused at once and retries on the backoff, so the tab holds a socket's place without a server. */
    private val refused = Host(
        id = "build-box", name = "build box", color = SwatchColor.SLATE, monogram = Host.monogramFor("build box"), address = "127.0.0.1", port = 1, user = "ci",
        auth = AuthMethod.AskEachTime, createdAt = 1L,
    )

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
        proxy?.let {
            it.swallowToServer = false
            it.swallowToClient = false
        }
        bg.cancel()
        graph.close()
        proxy?.close()
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

    /** Real time passes while the compose clock keeps ticking, so a bar's entrance or a sheet's finishes. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Taps the modal sheet's scrim near the top of the screen, where the sheet itself is not. */
    private fun dismissSheet() {
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(width / 2f, 60f)) }
        compose.waitForIdle()
    }

    private fun connectionSettings() = runBlocking { graph.settings.connectionSettings.first() }

    /**
     * The explainer fell due for a loss with the app away: the flag is up, or it has already been
     * spent into the setting. On a phone the shell's effects wait for the return (the frame clock
     * pauses at ON_STOP), so the flag stands until then; here only the fake process lifecycle is
     * stopped and the compose host keeps running, so [AppRoot] may spend the flag the instant it
     * is raised, before a poll on the flag alone could see it. Either reading is the same proof.
     */
    private fun explainerFired() = graph.sessions.batteryExplainerDue.value || connectionSettings().batteryExplained

    private companion object {
        const val CONNECTION_NOTE = "Sessions keep running while the app is away; detach them all from the notification. Idle-detach is off unless you turn it on."
    }

    /** The whole Connection panel in view: its note is its last line, so scrolling to it brings the rows above along. */
    private fun scrollToConnectionPanel() {
        compose.onNodeWithText(CONNECTION_NOTE).performScrollTo()
        compose.waitForIdle()
        waitForText("Detach idle sessions")
    }

    /** The sheet's `On <skin>` section label, which a [SectionLabel] sets in capitals; absent on stock Android. */
    private fun oemSectionLabel() = SemanticsMatcher("an On <skin> section label") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith("ON ") } == true
    }

    // ---- C20: the Connection panel and its picker ----------------------------------------------------------

    @Test
    fun `settings connection`() = settingsConnection("settings-connection")

    @Test
    fun `settings connection at the font cap`() {
        atTheCap()
        settingsConnection("settings-connection-font-cap")
    }

    /** The panel as a fresh install reads it: idle-detach Never, the Background row, and the note that sessions keep running. */
    private fun settingsConnection(name: String) {
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        scrollToConnectionPanel()
        waitForText("Never")
        waitForText("Background")
        assertEquals(IdleDetach.NEVER, connectionSettings().idleDetach)
        capture(name)
        compose.assertNoTextCut("Settings \u203A Connection")
    }

    @Test
    fun `idle detach picker`() = idlePicker("idle-detach-picker")

    @Test
    fun `idle detach picker at the font cap`() {
        atTheCap()
        idlePicker("idle-detach-picker-font-cap")
    }

    /** The row opens its four spans as a menu under it; a pick is written and the row reads it back. */
    private fun idlePicker(name: String) {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        scrollToConnectionPanel()
        compose.onNodeWithText("Detach idle sessions").performClick()
        waitForText("15 min")
        waitForText("1 hour")
        waitForText("4 hours")
        assertEquals("Never, selected, and Never on the row", 2, compose.onAllNodesWithText("Never").fetchSemanticsNodes().size)
        capture(name)
        compose.assertNoTextCut("the idle-detach picker")

        compose.onNodeWithText("1 hour").performClick()
        compose.waitUntil(5_000) { connectionSettings().idleDetach == IdleDetach.ONE_HOUR }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("15 min").fetchSemanticsNodes().isEmpty() }
        waitForText("1 hour")
        assertEquals(TimeUnit.HOURS.toMillis(1), IdleDetach.ONE_HOUR.millis)
    }

    // ---- C20, vision §4.4: the Background sheet -----------------------------------------------------------

    @Test
    fun `background sheet`() = backgroundSheet("background-sheet")

    @Test
    fun `background sheet at the font cap`() {
        atTheCap()
        backgroundSheet("background-sheet-font-cap")
    }

    /**
     * Stock Android, nothing running, the exemption not granted: the sheet says no service is up,
     * that the system may sleep Berth, offers Allow and Battery settings, and names no extra step.
     * Allow hands over to the system's own exemption dialog for Berth.
     */
    private fun backgroundSheet(name: String) {
        val dialog = ComponentName("com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations")
        shadowOf(app.packageManager).addActivityIfNotPresent(dialog)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            dialog,
            IntentFilter(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        themed {
            SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
            BackgroundSheet(graph.viewModel, onDismiss = {})
        }
        waitForText("How Berth stays connected when the screen is off")
        waitForText("Nothing is running now, so no service is up.")
        waitForText("The system may sleep Berth when the screen is off, which drops idle connections. Exempting Berth keeps them up.")
        compose.onNodeWithText("Allow").assertExists()
        compose.onNodeWithText("Battery settings").assertExists()
        assertTrue("not exempt: two buttons and no third; the handle and the scrim dismiss", compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty())
        assertTrue("stock Android has no extra step", compose.onAllNodes(oemSectionLabel()).fetchSemanticsNodes().isEmpty())
        capture(name)
        compose.assertNoTextCut("the Background sheet")

        compose.onNodeWithText("Allow").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("Allow opens the system's dialog", started)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
    }

    /**
     * A Samsung with the exemption granted and a login up: the running line counts it, the battery
     * section says exempt in the live colour with no buttons to press, and the skin's own step is named.
     */
    @Test
    fun `background sheet exempt on a Samsung with a login up`() {
        ShadowBuild.setManufacturer("samsung")
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        runBlocking {
            graph.hosts.upsert(refused)
            graph.sessions.open(refused)
        }
        compose.waitUntil(10_000) { graph.notifier.summary.value.active == 1 }
        themed {
            SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
            BackgroundSheet(graph.viewModel, onDismiss = {})
        }
        waitForText("1 session is keeping Berth in the foreground.")
        waitForText("Berth is exempt: the system leaves it running when the screen is off.")
        // A section label reads in capitals, so the skin's name does too.
        compose.waitUntil(5_000) { compose.onAllNodes(oemSectionLabel()).fetchSemanticsNodes().isNotEmpty() }
        waitForText("ON SAMSUNG")
        waitForText("Settings \u203A Battery \u203A Background usage limits", substring = true)
        assertTrue("exempt already: nothing to allow", compose.onAllNodesWithText("Allow").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Done").assertExists()
        capture("background-sheet-exempt-samsung")
        compose.assertNoTextCut("the Background sheet, exempt")
    }

    // ---- Part B: Back's one line, once ---------------------------------------------------------------------

    @Test
    fun `back notice`() = backNotice("back-notice")

    @Test
    fun `back notice at the font cap`() {
        atTheCap()
        backNotice("back-notice-font-cap")
    }

    /**
     * On the Stage with a login up, the first Back shows the line instead of leaving and marks it
     * shown; the second Back leaves. The line is never owed again: a later launch's first Back goes.
     */
    private fun backNotice(name: String) {
        StageFixture.seed(graph)
        runBlocking {
            graph.hosts.upsert(refused)
            graph.settings.setLastActiveSessionId("s-homelab")
        }
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 && graph.viewModel.activeTabId.value == "s-homelab" }
        val session = runBlocking { graph.sessions.open(refused) }
        compose.waitUntil(10_000) { graph.notifier.summary.value.active == 1 && graph.sessions.activeTabId.value == session.id }
        compose.waitUntil(5_000) { session.state == SessionState.RECONNECTING }
        settle(600)
        assertFalse(connectionSettings().backgroundNoticeShown)

        pressBack()
        waitForText(BACKGROUND_NOTICE)
        compose.waitUntil(5_000) { connectionSettings().backgroundNoticeShown }
        assertFalse("the first Back stays", compose.activity.isFinishing)
        settle(500)
        capture(name)
        // The line itself is whole on its one or two lines (A11); the strip's tab titles ellipsize by
        // design once four of them share the width at the cap, which is the strip's own business.
        val line = compose.onNode(hasText(BACKGROUND_NOTICE), useUnmergedTree = true).fetchSemanticsNode().textLayout()
        assertNotNull("Back's line has a layout", line)
        assertFalse("Back's line is cut: ${line!!.lineCount} lines", line.didOverflowHeight || line.isLineEllipsized(line.lineCount - 1))

        pressBack()
        compose.waitUntil(5_000) { compose.activity.isFinishing }
    }

    @Test
    fun `back leaves at once when the line has been shown before, and when nothing is running`() {
        StageFixture.seed(graph)
        runBlocking {
            graph.hosts.upsert(refused)
            graph.settings.setLastActiveSessionId("s-homelab")
            graph.settings.updateConnectionSettings { it.copy(backgroundNoticeShown = true) }
        }
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 && graph.viewModel.activeTabId.value == "s-homelab" }
        // Three detached frames: nothing holds a socket, so even a first Back would leave. With the line spent, a login up changes nothing.
        assertEquals(0, graph.notifier.summary.value.active)
        val session = runBlocking { graph.sessions.open(refused) }
        compose.waitUntil(10_000) { graph.notifier.summary.value.active == 1 && graph.sessions.activeTabId.value == session.id }
        compose.waitUntil(5_000) { graph.viewModel.connectionSettings.value.backgroundNoticeShown }
        settle(300)
        pressBack()
        compose.waitUntil(5_000) { compose.activity.isFinishing }
        assertTrue(compose.onAllNodesWithText(BACKGROUND_NOTICE).fetchSemanticsNodes().isEmpty())
    }

    // ---- vision §4.4: the explainer raised on its own ---------------------------------------------------------

    /**
     * A live login through the relay, the app away, the socket cut: the explainer falls due, and
     * on the return the Background sheet is up over the Stage on its own, marked so it never is
     * again. Done closes it; the login has meanwhile reconnected on the backoff's first second.
     */
    @Test
    fun `the background sheet raises itself once after a connection lost while away`() {
        val session = liveOnStage()
        graph.process.stop()
        proxy!!.cutAll()
        compose.waitUntil(10_000) { explainerFired() }
        graph.process.start()
        waitForText("How Berth stays connected when the screen is off")
        compose.waitUntil(5_000) { !graph.sessions.batteryExplainerDue.value && connectionSettings().batteryExplained }
        compose.waitUntil(45_000) { session.state == SessionState.LIVE && proxy!!.connections == 2 }
        waitForText("1 session is keeping Berth in the foreground.")
        settle(800)
        capture("background-sheet-raised-on-return")
        // The sheet's own texts; under it the strip's one tab carries the shell's title, ellipsized at the tab's width by design.
        compose.assertNoTextCut("the Background sheet over the Stage", within = isDialog())
        // Not exempt, the sheet has Allow and Battery settings and no Done: the scrim takes it down, as on every sheet.
        assertTrue(compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty())
        dismissSheet()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("How Berth stays connected when the screen is off").fetchSemanticsNodes().isEmpty() }

        // Lost again while away: explained is explained, and nothing comes up on the next return.
        graph.process.stop()
        proxy!!.cutAll()
        compose.waitUntil(10_000) { session.state != SessionState.LIVE }
        graph.process.start()
        settle(700)
        assertFalse(graph.sessions.batteryExplainerDue.value)
        assertTrue(compose.onAllNodesWithText("How Berth stays connected when the screen is off").fetchSemanticsNodes().isEmpty())
    }

    /** With the exemption already granted there is nothing to explain: the one showing is spent quietly and no sheet comes up. */
    @Test
    fun `with the exemption granted the explainer is spent without a sheet`() {
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        val session = liveOnStage()
        graph.process.stop()
        proxy!!.cutAll()
        // The loss is published to the manager before the login's state leaves Live, on another
        // thread: the state alone could be read before the manager has heard, and the return below
        // would then come first. The explainer's own reading says the manager has.
        compose.waitUntil(10_000) { explainerFired() }
        graph.process.start()
        compose.waitUntil(5_000) { connectionSettings().batteryExplained }
        assertFalse(graph.sessions.batteryExplainerDue.value)
        compose.waitUntil(10_000) { session.state != SessionState.LIVE }
        settle(700)
        assertTrue(compose.onAllNodesWithText("How Berth stays connected when the screen is off").fetchSemanticsNodes().isEmpty())
    }

    /** The shell up with a live login on the sshd through the relay on stage, or the test is skipped. */
    private fun liveOnStage(): TerminalSession {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val relay = BlackHoleProxy(sshHost, sshPort).also { proxy = it }
        val box = Host(
            id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = "127.0.0.1", port = relay.port, user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = 20L, persistence = PersistencePolicy(keepaliveSeconds = 120),
        )
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
        }
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        compose.waitUntil(10_000) { graph.sessions.restored.value }
        val session = runBlocking { graph.sessions.open(box) }
        compose.waitUntil(45_000) { session.state == SessionState.LIVE }
        compose.waitUntil(10_000) { session.emulator.screenText().any { it.contains("$") } }
        assertEquals(session.id, graph.sessions.activeTabId.value)
        return session
    }
}

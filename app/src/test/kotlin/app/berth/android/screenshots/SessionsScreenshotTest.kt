package app.berth.android.screenshots

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.AuthResolver
import app.berth.android.session.ManagedTab
import app.berth.android.session.NotificationPrompt
import app.berth.android.session.Prompt
import app.berth.android.session.SessionNotifier
import app.berth.android.session.SessionService
import app.berth.android.ui.AppRoot
import app.berth.android.ui.prompts.NotificationPermissionHost
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.NOTICE_BAR_MS
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The session model's surfaces (vision §4.3, §4.5; spec C3, C21) rendered through the real
 * screens in Berth Dark on a Pixel-class phone: the notification permission's rationale and its
 * refused notice, the attention states on the strip and jump-to-unread, a cold start after the OS
 * killed a live tab, and the Settings row for notifications. PNGs land in `build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class SessionsScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
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
        // This suite is about the ask, so the process starts the way a fresh install does: without the permission.
        graph = TestGraph(ApplicationProvider.getApplicationContext(), notificationsGranted = false)
    }

    @After
    fun tearDown() {
        // The live flow holds a real socket; a failure part-way must not leave it, or the manager's coroutines, under the next test.
        graph.close()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    @Composable
    private fun tabActions(): TabActions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }

    @Composable
    private fun Stage(tab: ManagedTab?) {
        StageScreen(graph.viewModel, tab, tabActions(), onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    /** Real time passes while the compose clock keeps ticking, so a pulse or a sheet's entrance finishes. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            compose.mainClock.advanceTimeBy(64)
            compose.waitForIdle()
            Thread.sleep(16)
        }
    }

    // ---- the permission (spec C1 note, C21) -------------------------------------------------------

    /** The first tab of the process came up Live: the rationale sheet over the Stage, before the system dialog. */
    @Test
    fun `notification rationale after the first connect`() {
        seed()
        restore()
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            Stage(session)
            NotificationPermissionHost(graph.notifier)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        assertNull("nothing is asked on a cold launch", graph.notifier.prompt.value)
        assertTrue("API 35 has the runtime permission and Robolectric starts without it", graph.notifier.needsPermission)
        graph.notifier.onFirstLive()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Allow notifications")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("To hear from your sessions while you're in another app.").assertExists()
        settle(400)
        capture("notification-rationale")
        // A tap on the scrim puts the sheet away and decides nothing: the next process asks again.
        compose.onNodeWithContentDescription("Close sheet").performClick()
        compose.waitUntil(5_000) { graph.notifier.prompt.value == null }
        assertFalse("a dismissal is not a decline", graph.notifier.asked)
        graph.notifier.onFirstLive()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Allow notifications")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntil(5_000) { graph.notifier.prompt.value == null }
        assertTrue("Not now counts as asked; there is no second ask", graph.notifier.asked)
        graph.notifier.onFirstLive()
        assertNull(graph.notifier.prompt.value)
    }

    /**
     * The system dialog was refused: `Notifications are off · Settings` at the bottom for six
     * seconds, once. (The capture of it is the live flow's, over the tab that just came up Live,
     * which is where the moment happens.)
     */
    @Test
    fun `notification denied notice`() {
        seed()
        restore()
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            Stage(session)
            NotificationPermissionHost(graph.notifier)
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        graph.notifier.onFirstLive()
        graph.notifier.onPermissionResult(granted = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notifications are off")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").assertExists()
        compose.onAllNodesWithText("Open settings").assertCountEquals(0)
        assertTrue("a refusal is an answer, like Not now", graph.notifier.asked)
        // Six seconds and it is gone on its own; nothing to dismiss.
        compose.mainClock.advanceTimeBy(NOTICE_BAR_MS + 100)
        compose.waitUntil(5_000) { graph.notifier.prompt.value == null }
        // The notice is one-time: a refusal in a later process says nothing more.
        val later = SessionNotifier(ApplicationProvider.getApplicationContext())
        later.onPermissionResult(granted = false)
        assertNull(later.prompt.value)
    }

    /** The notice's one action is the system page for Berth's notifications. */
    @Test
    fun `the denied notice's Settings action opens the system page`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        themed { NotificationPermissionHost(graph.notifier) }
        graph.notifier.onFirstLive()
        graph.notifier.onPermissionResult(granted = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { graph.notifier.prompt.value == null }
        val started = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    /** Settings › Notifications: the row to the system page, off while the permission is missing. */
    @Test
    fun `settings notifications row`() {
        seed()
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        // The caption is the panel's last line; scrolling to it brings the whole section into the frame.
        compose.onNode(hasText("Sessions is silent", substring = true)).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("System notifications").assertExists()
        compose.onNodeWithText("Off \u00B7 nothing reaches you outside the app").assertExists()
        capture("settings-notifications")
    }

    // ---- attention (vision §4.5, spec C3) --------------------------------------------------------

    /**
     * Two off-stage tabs rang their bells. pi-hole sits on the strip beside the active tab, so its
     * ring is on the tab; build box lives in the Work group past the strip's edge, so the count tile
     * carries its ring (spec C3: "ring on the count tile if it is scrolled out of view"). Then the
     * tile is held: the most recent bell, build box, comes on stage and its ring clears, and
     * pi-hole stays lit wherever the scroll left it; if that is cut by the strip's edge, the tile
     * lights too, since a tab is in view only when wholly inside the strip.
     */
    @Test
    fun `attention on the strip and jump to unread`() {
        seed()
        restore()
        graph.sessions.setActive("s-homelab")
        themed { Stage(graph.viewModel.activeTab.collectAsState().value) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { graph.sessions.get("s-homelab")!!.onStage && !graph.sessions.get("s-pihole")!!.onStage }
        // Tabs read "title, state, tab n of m, group, needs attention"; the tile says it in other words.
        val litTabs = hasContentDescription("needs attention", substring = true) and hasContentDescription(", tab ", substring = true)
        val litTile = hasContentDescription("a tab needs attention", substring = true)
        compose.onAllNodes(litTile).assertCountEquals(0)
        // On this phone the Work group starts past the strip's edge, so build box's tab is not laid out; a wider
        // strip would show it, and then its ring would be on the tab, with the tile lit only if the edge cut it.
        val piholeInView = tabInView("pi-hole, detached")
        val buildInView = tabInView("build box, detached")
        assertEquals("pi-hole sits whole beside the active tab", true, piholeInView)
        val tabsLit = listOfNotNull(piholeInView, buildInView).size
        val tileLit = if (piholeInView == true && buildInView == true) 0 else 1

        graph.sessions.get("s-pihole")!!.emulator.write("\u0007")
        Thread.sleep(20)
        graph.sessions.get("s-build")!!.emulator.write("\u0007")
        compose.waitUntil(5_000) {
            compose.onAllNodes(litTabs).fetchSemanticsNodes().size == tabsLit && compose.onAllNodes(litTile).fetchSemanticsNodes().size == tileLit
        }
        compose.onNode(hasContentDescription("pi-hole, detached", substring = true) and litTabs).assertExists()
        compose.onNode(hasContentDescription("homelab, detached", substring = true) and !hasContentDescription("needs attention", substring = true)).assertExists()
        assertFalse("the tab on stage sees its own bell", graph.sessions.get("s-homelab")!!.record.value.needsAttention)
        assertTrue(graph.sessions.get("s-build")!!.record.value.needsAttention)
        settle(1_200)
        capture("stage-attention")

        compose.onNode(hasContentDescription("open the tab switcher", substring = true)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == "s-build" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("build box, detached", substring = true) and !hasContentDescription("needs attention", substring = true)).fetchSemanticsNodes().size == 1 }
        assertFalse(graph.sessions.get("s-build")!!.record.value.needsAttention)
        assertTrue(graph.sessions.get("s-pihole")!!.record.value.needsAttention)
        // pi-hole's ring is on its tab wherever the scroll left it laid out; the tile carries it too unless the
        // tab lies wholly inside the strip. A cut tab speaks twice rather than not at all.
        settle(600)
        val piholeAfter = tabInView("pi-hole, detached")
        assertEquals(if (piholeAfter != null) 1 else 0, compose.onAllNodes(litTabs).fetchSemanticsNodes().size)
        assertEquals(if (piholeAfter == true) 0 else 1, compose.onAllNodes(litTile).fetchSemanticsNodes().size)
        capture("stage-attention-jumped")
    }

    /**
     * Whether the tab described is laid out in the strip, and if so whether it lies wholly inside
     * the strip's bounds, which is what the count tile's ring takes as "in view" (spec C3). Null
     * when the tab is not laid out at all.
     */
    private fun tabInView(description: String): Boolean? {
        val strip = compose.onNode(hasContentDescription("Tabs, ", substring = true) and hasContentDescription(" open", substring = true)).fetchSemanticsNode()
        val tab = compose.onAllNodes(hasContentDescription(description, substring = true)).fetchSemanticsNodes().firstOrNull() ?: return null
        val left = strip.positionInRoot.x
        val right = left + strip.size.width
        return tab.positionInRoot.x >= left - 0.5f && tab.positionInRoot.x + tab.size.width <= right + 0.5f
    }

    // ---- cold start after a kill (vision §4.3 L0, spec C3 Persistence) ---------------------------

    /**
     * The OS killed Berth while homelab was Live. The relaunch opens on that tab: its last frame,
     * dimmed, ending in `detached HH:MM` stamped with the last save, the pill's own word, and the
     * pill counting from then. (The capture keeps its original filename.)
     */
    @Test
    fun `cold start after the OS killed a live tab`() {
        val killedAt = now - TimeUnit.MINUTES.toMillis(4)
        seed(homelabState = SessionState.LIVE, homelabLayer = PersistenceLayer.IN_APP, homelabLastLiveAt = killedAt)
        runBlocking { graph.settings.setLastActiveSessionId("s-homelab") }
        // The strip is read back before the Stage composes, as in the rest of this suite: the test is
        // about what the relaunch shows for the tab, so the first composition holds it. (Composing
        // first and letting the manager's worker-thread restore race the Stage's first frame proved
        // flaky under the test rule, whose effects resume on the emitting thread rather than the main
        // thread's dispatcher a device uses.)
        restore()
        runBlocking { graph.sessions.activeTab.first { it?.id == "s-homelab" } }
        compose.setContent { AppRoot(graph.viewModel) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        val session = graph.sessions.get("s-homelab")!!
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Detached \u00B7 4 min ago")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(SessionState.DETACHED, session.state)
        assertTrue(session.emulator.screenText().any { it.contains("detached") })
        assertFalse(session.emulator.screenText().any { it.contains("paused") })
        compose.onNodeWithText("Reconnect").assertExists()
        compose.onAllNodesWithText("No tabs").assertCountEquals(0)
        settle(400)
        capture("app-cold-start-paused")
    }

    // ---- live, against the local sshd -------------------------------------------------------------

    /**
     * One real session through the whole model, when the `SSH_TEST_*` variables are set. The first
     * Live of the process raises the rationale over the Stage; the system dialog is refused, which
     * earns the six-second notice on the live Stage, and Settings is the way back, the switch read
     * again on ON_START. Off stage, bash's own OSC 133 marks decide: a quick command is
     * not news, an 11 s one lights the tab. Held, the count tile stages it. With the app away the
     * frame is on disk at once and a bell in the tab that was on stage reaches the shade with a
     * deep link back into it; on return the tab is seen and the notification goes. Detach all from
     * the Sessions notification leaves the tab detached on its frame.
     */
    @Test
    fun `live tab through the rationale, OSC 133 off stage, the shade while away and detach all`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        seed()
        val box = Host(
            id = "berth-test-box", name = "Berth test box", color = SwatchColor.TEAL, monogram = Host.monogramFor("Berth test box"),
            address = sshHost, port = sshPort, user = sshUser, auth = AuthMethod.Password(AuthResolver.passwordSecretId("berth-test-box")),
            tags = listOf("local"), createdAt = now - TimeUnit.HOURS.toMillis(1),
        )
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.settings.setLastActiveSessionId("s-homelab")
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        compose.setContent { AppRoot(graph.viewModel) }
        graph.process.start()
        compose.waitUntil(10_000) { graph.viewModel.tabs.value.size >= 3 && graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        assertNull("a cold launch asks nothing", graph.notifier.prompt.value)

        compose.onNode(hasContentDescription("Tabs, ", substring = true)).performScrollToNode(hasContentDescription("New tab"))
        compose.onNodeWithContentDescription("New tab").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth test box")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Berth test box").performClick()
        compose.waitUntil(20_000) { graph.prompts.current.value is Prompt.TrustHostKey }
        compose.onNodeWithText("Trust and connect").performClick()
        compose.waitUntil(45_000) { graph.sessions.activeSession.value?.state == SessionState.LIVE }
        val live = graph.sessions.activeSession.value!!

        // The first Live of the process is the moment (spec C1 note): the rationale, over the live Stage.
        compose.waitUntil(5_000) { graph.notifier.prompt.value == NotificationPrompt.Rationale }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Allow notifications")).fetchSemanticsNodes().isNotEmpty() }
        settle(800)
        capture("notification-rationale-live")
        // Continue hands over to the system dialog, which is not Compose; its answer arrives the way the launcher's does.
        // Refused: the six-second notice at the bottom of the live Stage, on the Deck, and nothing argues.
        compose.onNodeWithText("Continue").performClick()
        graph.notifier.onPermissionResult(granted = false)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notifications are off")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("a refusal is an answer: no second ask", graph.notifier.asked)
        settle(300)
        capture("notification-denied")
        // Settings is the way back: the system page for Berth's notifications, and the notice goes with the tap.
        // (The drawer keeps its own Settings row composed off the start edge, so the bar's action is named by its line.)
        compose.onNode(hasText("Settings") and hasAnySibling(hasText("Notifications are off"))).performClick()
        compose.waitUntil(5_000) { graph.notifier.prompt.value == null }
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(app).nextStartedActivity.action)
        // Turned on there and back on the Stage: ON_START re-reads the switch, and the shade is on.
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.process.stop()
        graph.process.start()
        compose.waitUntil(5_000) { graph.notifier.enabled.value }
        // The Sessions notification counts the socket and carries Detach all; the service that shows it was asked for.
        compose.waitUntil(5_000) { graph.notifier.summary.value.active == 1 }
        val sessionsNotification = graph.notifier.sessionsNotification(graph.notifier.summary.value)
        assertEquals(SessionNotifier.CHANNEL_SESSIONS, sessionsNotification.channelId)
        assertEquals("Detach all", sessionsNotification.actions.single().title.toString())
        val started = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertTrue("the foreground service was started for the live tab", started.any { it.component?.className == SessionService::class.java.name })

        // bash marks its own commands: OSC 133 C as a command starts, D with its exit status before the next prompt.
        live.sendText("PS0=$'\\e]133;C\\a'; PROMPT_COMMAND='printf \"\\e]133;D;%s\\a\" \"$?\"'; clear\n")
        settle(1_000)
        compose.onNode(hasContentDescription("homelab, detached", substring = true)).performClick()
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == "s-homelab" && !live.onStage }
        live.sendText("true\n")
        settle(1_500)
        assertFalse("a quick command off stage is not news", live.record.value.needsAttention)
        live.sendText("sleep 11; echo done\n")
        settle(2_000)
        assertFalse("not before the threshold", live.record.value.needsAttention)
        compose.waitUntil(15_000) { live.record.value.needsAttention }
        assertEquals("Command finished", live.record.value.attentionReason)
        val litTabs = hasContentDescription("needs attention", substring = true) and hasContentDescription(", tab ", substring = true)
        val litTile = hasContentDescription("a tab needs attention", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(litTabs).fetchSemanticsNodes().size + compose.onAllNodes(litTile).fetchSemanticsNodes().size >= 1 }
        // Its own ring where the tab is laid out; the tile's too unless the tab lies wholly inside the strip.
        // (The tab reads under bash's own title, `demo@box: ~`, not the host's name.)
        val liveInView = tabInView("${live.record.value.displayTitle}, live")
        assertEquals(if (liveInView != null) 1 else 0, compose.onAllNodes(litTabs).fetchSemanticsNodes().size)
        assertEquals(if (liveInView == true) 0 else 1, compose.onAllNodes(litTile).fetchSemanticsNodes().size)
        assertNull("on screen, the ring says it; the shade stays quiet", notifications.getNotification(SessionNotifier.attentionTag(live.id), 2))
        settle(1_200)
        capture("stage-attention-live")

        // Held, the count tile stages the tab that needs the user; arriving clears its ring.
        compose.onNode(hasContentDescription("open the tab switcher", substring = true)).performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == live.id && !live.record.value.needsAttention }
        assertTrue(live.emulator.screenText().any { it.contains("done") })
        settle(600)
        capture("stage-live-jumped")

        // Away, with this tab on stage: its frame is on disk at once, and nothing is on stage any more, so a bell
        // in it reaches the shade with the way back into the tab.
        graph.sessionRecords.frames.remove(live.id)
        graph.process.stop()
        compose.waitUntil(5_000) { graph.sessionRecords.frames[live.id] != null }
        assertTrue(frameLines(graph.sessionRecords.frames.getValue(live.id)).any { it.contains("done") })
        live.sendText("printf '\\a'\n")
        compose.waitUntil(10_000) { notifications.getNotification(SessionNotifier.attentionTag(live.id), 2) != null }
        val posted = notifications.getNotification(SessionNotifier.attentionTag(live.id), 2)
        assertEquals(SessionNotifier.CHANNEL_ATTENTION, posted.channelId)
        assertEquals("${live.record.value.displayTitle} needs you", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(live.id, shadowOf(posted.contentIntent).savedIntent.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals(SessionNotifier.ACTION_OPEN_TAB, shadowOf(posted.contentIntent).savedIntent.action)
        // Back: the tab in front is seen, and its notification goes.
        graph.process.start()
        compose.waitUntil(5_000) { !live.record.value.needsAttention && notifications.getNotification(SessionNotifier.attentionTag(live.id), 2) == null }

        // Detach all, as the Sessions notification's action sends it: the tab detaches onto its frame.
        assertTrue(graph.notifier.dispatch(shadowOf(sessionsNotification.actions[0].actionIntent).savedIntent, graph.sessions))
        compose.waitUntil(15_000) { live.state == SessionState.DETACHED }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Detached \u00B7 just now")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(frameLines(graph.sessionRecords.frames.getValue(live.id)).any { it.contains("done") })
        compose.waitUntil(5_000) { graph.notifier.summary.value.active == 0 }
        settle(600)
        capture("stage-live-detached-all")
    }

    private fun frameLines(frame: ByteArray): List<String> = DataInputStream(frame.inputStream()).use { d ->
        d.readInt()
        List(d.readInt()) { d.readUTF() }
    }

    // ---- fixture ---------------------------------------------------------------------------------

    /** Home: homelab, pi-hole. Work: build box. Each with a frame; homelab's state at death is the parameter. */
    private fun seed(
        homelabState: SessionState = SessionState.DETACHED,
        homelabLayer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME,
        homelabLastLiveAt: Long = now - TimeUnit.MINUTES.toMillis(12),
    ) = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        val homelab = host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS, AuthMethod.Password("host-password:homelab"))
        val pihole = host("pi-hole", "pi-hole", "192.168.1.2", "pi", SwatchColor.MOSS, AuthMethod.AskEachTime)
        val build = host("build-box", "build box", "build.internal", "ci", SwatchColor.SLATE, AuthMethod.AskEachTime)
        listOf(homelab, pihole, build).forEach { graph.hosts.upsert(it) }
        graph.sessionRecords.upsert(record("s-homelab", homelab, Workspace.DEFAULT_ID, 0, homelabState, homelabLayer, homelabLastLiveAt, "~/srv", "docker compose ps"))
        graph.sessionRecords.upsert(record("s-pihole", pihole, Workspace.DEFAULT_ID, 1, lastLiveAt = now - TimeUnit.MINUTES.toMillis(95), cwd = "/etc/pihole", lastCommand = "tail -f pihole.log"))
        graph.sessionRecords.upsert(record("s-build", build, "ws-work", 0, lastLiveAt = now - TimeUnit.MINUTES.toMillis(400), cwd = "~/work/berth", lastCommand = "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame(
            "s-homelab",
            frame(
                listOf(
                    "ben@homelab:~/srv$ docker compose ps",
                    "NAME        IMAGE               STATUS        PORTS",
                    "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
                    "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
                    "postgres    postgres:16         Up 3 days     5432/tcp",
                    "ben@homelab:~/srv$ docker compose logs -f caddy",
                    "caddy  | {\"level\":\"info\",\"msg\":\"serving initial configuration\"}",
                    "caddy  | {\"level\":\"info\",\"msg\":\"certificate obtained successfully\",\"identifier\":\"git.home.arpa\"}",
                ),
            ),
        )
        graph.sessionRecords.saveFrame("s-pihole", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app from 192.168.1.30")))
        graph.sessionRecords.saveFrame("s-build", frame(listOf("ci@build:~/work/berth$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s", "ci@build:~/work/berth$ ")))
    }

    private fun host(id: String, name: String, address: String, user: String, color: SwatchColor, auth: AuthMethod) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user, auth = auth,
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun record(
        id: String,
        host: Host,
        ws: String,
        order: Int,
        state: SessionState = SessionState.DETACHED,
        layer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME,
        lastLiveAt: Long,
        cwd: String?,
        lastCommand: String?,
    ) = SessionRecord(
        id = id, workspaceId = ws, hostId = host.id, hostSnapshot = host, state = state, layer = layer, title = host.name,
        cwd = cwd, lastCommand = lastCommand, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = lastLiveAt,
    )

    private fun restore() = runBlocking { graph.sessions.restore() }

    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
        }
        return out.toByteArray()
    }
}

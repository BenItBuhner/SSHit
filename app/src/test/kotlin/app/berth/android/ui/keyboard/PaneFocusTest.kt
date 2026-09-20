package app.berth.android.ui.keyboard

import android.app.Application
import android.content.res.Configuration
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_O
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.session.PaneSide
import app.berth.android.session.Prompt
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit

/**
 * The keyboard on a Stage laid out in panes (spec C22, C23, A11), through the shell as [AppRoot]
 * mounts it on a tablet on its side, with a hardware keyboard declared in the configuration:
 * Ctrl+Shift+D splits the Stage beside the tab on stage and the new pane's terminal takes the keys;
 * Ctrl+Shift+O moves them to the other pane and the pane model follows (the header's × goes with
 * them); a plain Tab that walks out of the strip and lands in a pane focuses that pane the way a
 * touch on it would, and the focus stays on the control it landed on; Ctrl+Shift+D with two panes
 * makes one Stage of them again and the terminal keeps the keys. Beside the Stage stands the rail:
 * a focus moved onto its rows is where the user put it and stays there, and Tab from its last row
 * is back on the strip. A live tab over the local sshd (its Split opens a second shell there);
 * skipped unless `SSH_TEST_*` is set.
 *
 * The first case keeps the window in touch mode, as [StageFocusTest] does: the stricter case, in
 * which only a control marked focusable takes the focus. The rail's rows are `clickable`, which
 * takes the focus once a key has left touch mode, so the second case asks for keyboard mode first,
 * as a typed letter would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-320dpi")
class PaneFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private val now = System.currentTimeMillis()

    private val box = Host(
        id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = sshHost, port = sshPort, user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = now - TimeUnit.DAYS.toMillis(3),
    )
    private val homelab = Host(
        id = "homelab", name = "homelab", color = SwatchColor.MOSS, monogram = "HO", address = "192.168.1.20", port = 22, user = "ben",
        auth = AuthMethod.AskEachTime, lastConnectedAt = now - TimeUnit.MINUTES.toMillis(40), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        // The first login raises trust-on-first-use; answered the way the sheet's button would.
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
            graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.hosts.upsert(homelab)
            // A detached shell tab in the strip beside the live one, so the strip has more than one tab to walk.
            graph.sessionRecords.upsert(
                SessionRecord(
                    id = "s-homelab", workspaceId = Workspace.DEFAULT_ID, hostId = homelab.id, hostSnapshot = homelab, state = SessionState.DETACHED,
                    layer = PersistenceLayer.LOCAL_FRAME, title = homelab.name, cwd = "~/srv", lastCommand = "docker compose ps", sortOrder = 0,
                    createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
                ),
            )
            graph.sessionRecords.saveFrame("s-homelab", frame(listOf("ben@homelab:~/srv$ docker compose ps", "ben@homelab:~/srv$ ")))
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        if (!::graph.isInitialized) return
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        bg.cancel()
        graph.close()
    }

    @Test
    fun `Ctrl+Shift+D splits the Stage and the new pane has the keys, Ctrl+Shift+O and a Tab into a pane move them and the pane model follows, Ctrl+Shift+D again is one Stage`() {
        val live = runBlocking { graph.sessions.open(box) }
        mount(keyboardMode = false)
        compose.waitUntil(45_000) { live.state == SessionState.LIVE }
        // The rail stands beside the Stage, and a keyboard attached puts the keys in the live terminal without a touch.
        compose.onNode(hasText("New group") and hasClickAction()).assertExists()
        assertNull(graph.sessions.panes.value)
        awaitFocused(hasTestTag(TerminalTag), "the live terminal, on coming on stage")

        // Ctrl+Shift+D: a second shell on the box opens in the right pane, focused, and its terminal has the keys.
        chord(KEYCODE_D, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(20_000) { graph.sessions.panes.value?.focused == PaneSide.RIGHT }
        val panes = graph.sessions.panes.value!!
        assertEquals(live.id, panes.left.id)
        val twin = panes.right
        assertNotEquals(live.id, twin.id)
        assertEquals(twin.id, graph.sessions.activeTabId.value)
        awaitFocused(hasTestTag(TerminalTag) and inPane("right"), "the new pane's terminal after Ctrl+Shift+D")
        compose.onAllNodesWithTag(TerminalTag).assertCountEquals(2)

        // Ctrl+Shift+O: the other pane. The live tab takes the keys, the focus and the header's ×, and both panes stay where they were.
        chord(KEYCODE_O, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.focused == PaneSide.LEFT }
        assertEquals(live.id, graph.sessions.activeTabId.value)
        assertEquals(live.id, graph.sessions.panes.value!!.left.id)
        assertEquals(twin.id, graph.sessions.panes.value!!.right.id)
        awaitFocused(hasTestTag(TerminalTag) and inPane("left"), "the left pane's terminal after Ctrl+Shift+O")
        compose.onNode(hasContentDescription("Close pane")).assert(inPane("left"))
        // And back.
        chord(KEYCODE_O, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.focused == PaneSide.RIGHT }
        assertEquals(twin.id, graph.sessions.activeTabId.value)
        awaitFocused(hasTestTag(TerminalTag) and inPane("right"), "the right pane's terminal after the second Ctrl+Shift+O")
        compose.onNode(hasContentDescription("Close pane")).assert(inPane("right"))

        // A plain Tab out of the strip walks into the left pane's terminal: to the pane model that is a touch on
        // the pane, so it is focused now, × and all, and the focus stays on the terminal Tab put it on.
        chord(KEYCODE_S, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(role(Role.Tab), "a tab of the strip after Ctrl+Shift+S")
        var steps = 0
        while (compose.onAllNodes(isFocused() and inPane("left")).fetchSemanticsNodes().isEmpty()) {
            assertTrue("Tab walked $steps controls from the strip without reaching the left pane", steps++ < 20)
            press(KEYCODE_TAB)
            assertEquals("the focus fell off the window on step $steps", 1, compose.onAllNodes(isFocused()).fetchSemanticsNodes().size)
        }
        awaitFocused(hasTestTag(TerminalTag) and inPane("left"), "the left pane's terminal, reached by Tab from the strip")
        compose.waitUntil(5_000) { graph.sessions.panes.value?.focused == PaneSide.LEFT }
        assertEquals(live.id, graph.sessions.activeTabId.value)
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasTestTag(TerminalTag) and inPane("left"))
        compose.onNode(hasContentDescription("Close pane")).assert(inPane("left"))

        // Ctrl+Shift+D with two panes: one Stage again, the focused tab's, and its terminal keeps the keys; the twin stays a tab.
        chord(KEYCODE_D, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(5_000) { graph.sessions.panes.value == null }
        assertEquals(live.id, graph.sessions.activeTabId.value)
        assertTrue(graph.sessions.sessions.value.any { it.id == twin.id })
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().size == 1 }
        awaitFocused(hasTestTag(TerminalTag), "the one terminal after the Stage became one again")
    }

    @Test
    fun `a focus moved onto the rail stays there, and Tab from the rail's last row is back on the strip`() {
        val live = runBlocking { graph.sessions.open(box) }
        mount(keyboardMode = true)
        compose.waitUntil(45_000) { live.state == SessionState.LIVE }
        awaitFocused(hasTestTag(TerminalTag), "the live terminal, on coming on stage")

        // The focus onto Settings, the rail's last row: the Stage does not take it back, frame after frame.
        val settings = compose.onNode(hasText("Settings") and hasClickAction())
        settings.requestFocus()
        compose.waitForIdle()
        settings.assertIsFocused()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        settings.assertIsFocused()
        assertEquals(1, compose.onAllNodes(isFocused()).fetchSemanticsNodes().size)

        // Tab from the rail's last row: the strip, the Stage's first controls; Shift+Tab is the rail's last row again.
        press(KEYCODE_TAB)
        awaitFocused(inStrip(), "the strip after Tab from the rail")
        press(KEYCODE_TAB, META_SHIFT_ON)
        compose.waitForIdle()
        settings.assertIsFocused()
    }

    /** The shell on the tablet, with a hardware keyboard in the configuration, in touch mode or out of it. */
    private fun mount(keyboardMode: Boolean) {
        compose.setContent {
            WithHardwareKeyboard {
                if (keyboardMode) InKeyboardMode { AppRoot(graph.viewModel) } else AppRoot(graph.viewModel)
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The configuration a hardware keyboard shows up in: a QWERTY keyboard, and not hidden. */
    @Composable
    private fun WithHardwareKeyboard(content: @Composable () -> Unit) {
        val current = LocalConfiguration.current
        val withKeyboard = remember(current) {
            Configuration(current).apply {
                keyboard = Configuration.KEYBOARD_QWERTY
                hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO
            }
        }
        CompositionLocalProvider(LocalConfiguration provides withKeyboard, content = content)
    }

    /** The window out of touch mode, as a typed key leaves it: every `clickable` is focusable. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun InKeyboardMode(content: @Composable () -> Unit) {
        val modes = LocalInputModeManager.current
        LaunchedEffect(modes) { modes.requestInputMode(InputMode.Keyboard) }
        content()
    }

    private fun inPane(side: String) = hasAnyAncestor(hasContentDescription(", $side pane", substring = true))

    private fun inStrip(): SemanticsMatcher {
        val strip = hasContentDescription("Tabs, ", substring = true)
        return strip or hasAnyAncestor(strip)
    }

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** Waits for the one focused control to be [what], failing with [where] the focus was expected and where it is. */
    private fun awaitFocused(what: SemanticsMatcher, where: String): SemanticsNodeInteraction {
        try {
            compose.waitUntil("the focus on $where", 10_000) { compose.onAllNodes(isFocused() and what).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val holding = compose.onAllNodes(isFocused(), useUnmergedTree = true).printToString(maxDepth = 0)
            throw AssertionError("Expected the focus on $where; it is on:\n$holding", e)
        }
        return compose.onNode(isFocused() and what)
    }

    /** A chord's down, the way a hardware keyboard's arrives: through the focused control's ancestors, the Stage's preview first. */
    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }

    /** A key pressed and released: a control's press fires on the release, a focus move on the press. */
    private fun press(code: Int, meta: Int = 0) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_UP, code, 0, meta)))
        compose.waitForIdle()
    }

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

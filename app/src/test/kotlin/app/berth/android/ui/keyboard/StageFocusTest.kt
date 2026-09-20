package app.berth.android.ui.keyboard

import android.app.Application
import android.content.res.Configuration
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_K
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.session.Prompt
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.stage.StageTools
import app.berth.android.ui.tabs.ShellTabActions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The keyboard's way around the Stage (spec A11, focus order), through the real [StageScreen] with
 * a hardware keyboard declared in the configuration: Ctrl+Shift+S puts the focus on the strip and
 * Ctrl+Shift+K on the Deck, Escape brings it back to the terminal, Enter presses the control it
 * is on, and while the keyboard is attached the focus never falls off the Stage: a tab switched
 * under it hands it to the new tab's terminal, a Deck hidden under it to the strip that stands in
 * for the Deck, and that strip pressed hands it back to the Deck. A live tab over the local sshd,
 * since only a live tab has a Deck; skipped unless `SSH_TEST_*` is set.
 *
 * The window stays in touch mode throughout: Robolectric starts it there and the injected keys do
 * not leave it, the way a device's Ctrl chords do not either (a typed character or a navigation key
 * does). That is the stricter case, in which no control shows its focus and a `clickable` takes it
 * only when marked to (`alwaysFocusable`, the collapsed strip); a keyboard that has typed a letter
 * is out of touch mode and every control here is focusable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class StageFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(Dispatchers.Default + Job())
    private val now = System.currentTimeMillis()
    private val tools = StageTools()
    /** The grip's tap opens the session sheet; the count says a keyboard's Enter on the grip did too. */
    private var sheetOpens = 0

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
            // A detached shell tab with a frame beside the live one: a terminal to land on, and no Deck.
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
    fun `the chords move the focus between the terminal, the strip and the Deck, Enter presses, and the focus stays on the Stage as the controls under it come and go`() {
        val live = runBlocking { graph.sessions.open(box) }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                WithHardwareKeyboard {
                    Box(Modifier.fillMaxSize()) {
                        val tab by graph.viewModel.activeTab.collectAsState()
                        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                        StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = { sheetOpens++ }, onEditHost = {}, tools = tools)
                    }
                }
            }
        }
        compose.waitUntil(45_000) { live.state == SessionState.LIVE }
        // A keyboard attached, a shell tab on stage: the Deck folds to its strip (C4), and the terminal is focused without a touch.
        awaitDeckStrip()
        assertFalse(compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty())
        awaitFocused(hasTestTag(TerminalTag), "the live terminal, on coming on stage")

        // Ctrl+Shift+S: the strip, entered at its first tab, homelab's; Enter there switches to it,
        // and the focus stays on the tab it pressed, since the strip stayed.
        chord(KEYCODE_S, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(tab(), "a tab of the strip after Ctrl+Shift+S")
        compose.onNode(isFocused()).assert(hasContentDescription("homelab", substring = true))
        press(KEYCODE_ENTER)
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == "s-homelab" }
        awaitFocused(tab(), "the tab pressed, homelab now on stage")
        // Escape from the strip: the tab's body, homelab's terminal.
        chord(KEYCODE_ESCAPE, 0)
        awaitFocused(hasTestTag(TerminalTag), "homelab's terminal after Escape from the strip")
        // A detached tab has no Deck and no strip for it: Ctrl+Shift+K finds no region and moves nothing.
        assertFalse(compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty())
        chord(KEYCODE_K, META_CTRL_ON or META_SHIFT_ON)
        compose.waitForIdle()
        compose.onNodeWithTag(TerminalTag).assertIsFocused()

        // Ctrl+Tab back to the live tab: the body under the focus is swapped, and the focus lands on the new one's terminal.
        chord(KEYCODE_TAB, META_CTRL_ON)
        compose.waitUntil(5_000) { graph.sessions.activeTabId.value == live.id }
        awaitFocused(hasTestTag(TerminalTag), "the live terminal after Ctrl+Tab")
        // The strip stands still folded, the fold being the Stage's; Ctrl+Shift+E opens the Deck, and the terminal keeps the focus.
        awaitDeckStrip()
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty() }
        awaitFocused(hasTestTag(TerminalTag), "the terminal after Ctrl+Shift+E opened the Deck")

        // Ctrl+Shift+K: the Deck, entered at its grip; Enter on the grip is its tap, the session sheet.
        chord(KEYCODE_K, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(hasContentDescription("Grip", substring = true), "the Deck's grip after Ctrl+Shift+K")
        val opens = sheetOpens
        press(KEYCODE_ENTER)
        compose.waitUntil(5_000) { sheetOpens == opens + 1 }
        // Escape from the Deck: the terminal.
        chord(KEYCODE_ESCAPE, 0)
        awaitFocused(hasTestTag(TerminalTag), "the terminal after Escape from the Deck")

        // The Deck hidden under the focus (Ctrl+Shift+E while its grip holds it): the strip that
        // stands in for the Deck takes the focus; Enter on that strip brings the Deck back and the
        // focus with it, on the grip again.
        chord(KEYCODE_K, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(hasContentDescription("Grip", substring = true), "the grip before hiding the Deck")
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isEmpty() }
        awaitFocused(hasText("Keyboard", substring = true) and role(Role.Button), "the collapsed Deck strip after Ctrl+Shift+E")
        press(KEYCODE_ENTER)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty() }
        awaitFocused(hasContentDescription("Grip", substring = true), "the grip after the strip brought the Deck back")

        // Ctrl+Shift+F: the search bar's field takes the focus; Escape closes the search, the way its × does, and the terminal has the focus again.
        chord(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(5_000) { tools.search.open }
        awaitFocused(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), "the search field after Ctrl+Shift+F")
        chord(KEYCODE_ESCAPE, 0)
        compose.waitUntil(5_000) { !tools.search.open }
        awaitFocused(hasTestTag(TerminalTag), "the terminal after Escape closed the search")

        // From the terminal itself Escape is the host's: the Stage lets it through, and the focus does not move.
        chord(KEYCODE_ESCAPE, 0)
        compose.waitForIdle()
        compose.onNodeWithTag(TerminalTag).assertIsFocused()
        assertEquals(live.id, graph.sessions.activeTabId.value)
        assertTrue(compose.onAllNodes(isFocused()).fetchSemanticsNodes().size == 1)
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

    private fun tab() = role(Role.Tab)

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    /** The 20 dp strip a folded Deck leaves (C4), the one button that says so. */
    private fun awaitDeckStrip() {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Deck collapsed", substring = true) and role(Role.Button)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the Deck's strip never stood", e)
        }
    }

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

    /** A key pressed and released: a control's press fires on the release. */
    private fun press(code: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, 0)))
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_UP, code, 0, 0)))
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

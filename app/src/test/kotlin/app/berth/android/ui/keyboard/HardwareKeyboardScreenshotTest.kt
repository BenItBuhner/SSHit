package app.berth.android.ui.keyboard

import android.app.Application
import android.content.res.Configuration
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_K
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.captureAudited
import app.berth.android.session.AuthResolver
import app.berth.android.session.PaneSide
import app.berth.android.session.Prompt
import app.berth.android.session.SessionEnvironment
import app.berth.android.session.TerminalSession
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.hosts.HostEditorScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageScreen
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
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The screens a hardware keyboard brings (spec C22, C4 "Hardware keyboard attached", A11), written
 * to `build/outputs/roborazzi` with the accessibility audit on each: the Stage with a keyboard
 * attached and the Deck folded to its 20 dp strip, that strip opened to the one row of modifiers
 * and actions, the same with the compact setting off and the whole Deck standing, the focus a
 * keyboard shows on a tab of the strip, on a Deck key and on a Settings row, the shortcut sheet
 * Ctrl+Shift+/ opens, Settings › Hardware keyboard with its Alt key menu, a host's own Alt key in
 * its editor; and on a tablet, two panes under one compact Deck with the sheet as a dialog naming
 * the pane chords. The phone's Stage rides a live session with nowhere to send (the way
 * `TerminalToolsScreenshotTest` does), so those frames need no sshd; the tablet's two panes are two
 * shells on the local sshd, skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class HardwareKeyboardScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph
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
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.sessions.sessions.value.forEach { graph.sessions.close(it.id) }
        bg.cancel()
        graph.close()
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    // ---- the Stage on a phone, a keyboard attached ------------------------------------------------

    @Test
    fun `the Deck folded to its strip under a keyboard, opened to its compact row, the focus shown on the strip and on a key, and the shortcut sheet`() {
        val session = liveHomelab()
        mount(keyboardMode = true) { Stage(session) }
        // A keyboard attached folds the Deck to its strip (C4): the Keyboard layer's name on it, no key on the Stage, the terminal focused.
        awaitDeckStrip()
        assertTrue("no Deck key stands under a keyboard", compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isEmpty())
        assertEquals("the strip is 20 dp", 20f, compose.onNode(deckStrip()).fetchSemanticsNode().size.height / compose.density.density, 0.5f)
        awaitFocused(hasTestTag(TerminalTag), "the terminal, with a keyboard attached and a shell tab on stage")
        capture("stage-hardware-keyboard")

        // Ctrl+Shift+E opens the strip to the compact Deck: one row, the Keyboard layer, the layout's
        // modifiers, then Paste since no key of it pastes; the letters and Esc are the keyboard's.
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        awaitDeck()
        assertTrue("Ctrl stays", compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Ctrl", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("Paste is added", compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Paste", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue("Esc goes", compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Esc", substring = true)).fetchSemanticsNodes().isEmpty())
        // One layer has nothing to cycle: the row ends in the Deck editor key, and no key says "Layer".
        assertTrue("the trailing key is the Deck editor", compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Deck editor")).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no layer key on the compact Deck", compose.onAllNodes(hasContentDescription("Layer")).fetchSemanticsNodes().isEmpty())
        awaitFocused(hasTestTag(TerminalTag), "the terminal still, after the Deck opened")
        capture("stage-hardware-keyboard-compact-deck")

        // Ctrl+Shift+S: the active tab has the focus, and out of touch mode shows it: the tone, the
        // title in accent, and the 2 dp accent bar at its foot, a shape as well as a colour (nit 1).
        chord(KEYCODE_S, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(role(Role.Tab) and hasContentDescription("homelab", substring = true), "the active tab, homelab's, after Ctrl+Shift+S")
        capture("stage-hardware-keyboard-focus-strip")

        // Ctrl+Shift+K: the Deck at its grip; Tab is its first key, Ctrl.
        chord(KEYCODE_K, META_CTRL_ON or META_SHIFT_ON)
        awaitFocused(hasContentDescription("Grip", substring = true), "the Deck's grip after Ctrl+Shift+K")
        press(KEYCODE_TAB)
        awaitFocused(hasTestTag(DeckKeyTag) and hasContentDescription("Ctrl", substring = true), "the Deck's first key, Ctrl, after Tab from the grip")
        capture("stage-hardware-keyboard-focus-deck")

        // Ctrl+Shift+/: the shortcut sheet, over the Stage.
        chord(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON)
        waitForText("Keyboard shortcuts")
        waitForText("Next tab")
        assertShortcutTable()
        capture("shortcut-sheet")
    }

    /**
     * C22's table (design review, item 4): a row per chord with the action as its title and the keys
     * trailing in mono, one line each on a 40 dp row, the reader hearing the action first; and the
     * whole sheet under two screens, where the two-line rows ran to four.
     */
    private fun assertShortcutTable() {
        val row = compose.onNode(hasContentDescription("Next tab, Ctrl+Tab")).fetchSemanticsNode()
        val density = compose.density.density
        assertTrue("a one-line row, ${row.size.height / density} dp", row.size.height / density <= 44f)
        // The row merges its texts for the reader; the two texts are found under it in the unmerged tree.
        val title = compose.onNode(hasText("Next tab") and hasAnyAncestor(hasContentDescription("Next tab, Ctrl+Tab")), useUnmergedTree = true).fetchSemanticsNode()
        val keys = compose.onNode(hasText("Ctrl+Tab") and hasAnyAncestor(hasContentDescription("Next tab, Ctrl+Tab")), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the action leads and the keys trail on the one line", title.boundsInRoot.left < keys.boundsInRoot.left && keys.boundsInRoot.right <= row.boundsInRoot.right)
        assertTrue("the two share the line", title.boundsInRoot.top < keys.boundsInRoot.bottom && keys.boundsInRoot.top < title.boundsInRoot.bottom)
        // The sheet's scroll: what is on screen plus what is left to scroll is the whole table.
        val scroll = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText("Keyboard shortcuts"))).fetchSemanticsNode()
        val range = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
        val whole = (scroll.size.height + range.maxValue()) / density
        // Two roots stand, the Stage's and the sheet's window, both the screen's size.
        val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height } / density
        assertTrue("the sheet is under two screens: $whole dp of $screen", whole < 2 * screen)
    }

    @Test
    fun `Compact Deck off, the strip still stands under a keyboard and opens to the whole Deck`() {
        // The tab's record first: the view model's first use restores the tabs, and the strip shows what it found.
        val session = liveHomelab()
        graph.viewModel.updateHardwareKeyboard { it.copy(compactDeck = false) }
        mount(keyboardMode = false) { Stage(session) }
        // The fold is the keyboard's, not the setting's (C4); the strip's tap is the touch way to open it.
        awaitDeckStrip()
        compose.onNode(deckStrip()).performClick()
        awaitDeck()
        assertTrue("Esc stays", compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Esc", substring = true)).fetchSemanticsNodes().isNotEmpty())
        capture("stage-hardware-keyboard-full-deck")
    }

    // ---- Settings and the host editor ---------------------------------------------------------------

    @Test
    fun `Settings, the Hardware keyboard panel and its Alt key menu`() {
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        // The panel's last row at the bottom edge puts the whole panel on screen.
        compose.onNodeWithText("Keyboard shortcuts").performScrollTo()
        compose.waitForIdle()
        capture("settings-hardware-keyboard")
        compose.onNodeWithText("Alt key").performClick()
        waitForText("Eighth bit")
        capture("settings-hardware-keyboard-alt-menu")
    }

    @Test
    fun `Settings, a row holding the keyboard's focus`() {
        // Out of touch mode, the row the focus is on shows it: one tonal step up, its label in accent.
        StageFixture.seed(graph)
        mount(keyboardMode = true) { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Keyboard shortcuts").performScrollTo()
        compose.waitForIdle()
        val row = compose.onNode(hasText("Alt key") and hasClickAction())
        row.requestFocus()
        compose.waitForIdle()
        row.assertIsFocused()
        capture("settings-hardware-keyboard-focus-row")
    }

    @Test
    fun `the host editor, this host's own Alt key`() {
        StageFixture.seed(graph)
        themed { HostEditorScreen(graph.viewModel, hostId = "homelab", onDone = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("homelab")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alt key").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText("Alt key").performClick()
        waitForText("Eighth bit")
        compose.onNodeWithText("Eighth bit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Eighth bit")).fetchSemanticsNodes().size == 1 }
        capture("host-editor-alt-key")
    }

    // ---- a tablet: two panes, one Deck, the sheet a dialog ---------------------------------------------

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-320dpi")
    fun `two panes under one compact Deck on a tablet, and the shortcut sheet as a dialog with the pane chords`() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        val box = Host(
            id = "box", name = "Berth test box", color = SwatchColor.TEAL, monogram = "BT", address = sshHost, port = sshPort, user = sshUser,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), createdAt = now - TimeUnit.DAYS.toMillis(3),
        )
        // The first login raises trust-on-first-use; answered the way the sheet's button would.
        bg.launch { graph.prompts.current.collect { prompt -> if (prompt is Prompt.TrustHostKey) prompt.trust() } }
        runBlocking {
            graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
            graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.hosts.upsert(homelab)
            graph.sessionRecords.upsert(homelabRecord())
            graph.sessionRecords.saveFrame(homelabRecord().id, frame(HOMELAB_LINES))
            graph.sessions.restore()
        }
        val live = runBlocking { graph.sessions.open(box) }
        mount(keyboardMode = false) { AppRoot(graph.viewModel) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Tabs, ", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(45_000) { live.state == SessionState.LIVE }
        awaitFocused(hasTestTag(TerminalTag), "the live terminal, on coming on stage")

        // Ctrl+Shift+D: a second shell on the box in the right pane, focused; the one compact Deck stands under both.
        chord(KEYCODE_D, META_CTRL_ON or META_SHIFT_ON)
        compose.waitUntil(20_000) { graph.sessions.panes.value?.focused == PaneSide.RIGHT }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().size == 2 }
        compose.waitUntil(20_000) { graph.sessions.panes.value?.right?.let { (it as? TerminalSession)?.state == SessionState.LIVE } == true }
        awaitFocused(hasTestTag(TerminalTag) and hasAnyAncestor(hasContentDescription(", right pane", substring = true)), "the new pane's terminal")
        // The one strip stands folded under both panes (C4); Ctrl+Shift+E opens it to the one compact Deck.
        awaitDeckStrip()
        compose.onAllNodes(deckStrip()).assertCountEquals(1)
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        awaitDeck()
        assertEquals(1, compose.onAllNodes(hasTestTag(DeckKeyTag) and hasContentDescription("Paste", substring = true)).fetchSemanticsNodes().size)
        compose.onAllNodes(hasContentDescription("Close pane")).assertCountEquals(1)
        capture("stage-panes-hardware-keyboard")

        // Ctrl+Shift+/ on a window that fits two panes: the sheet is a dialog, and it names the pane chords.
        chord(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON)
        waitForText("Keyboard shortcuts")
        waitForText("Ctrl+Shift+O")
        compose.onNodeWithText("Ctrl+Shift+O").performScrollTo()
        compose.waitForIdle()
        capture("shortcut-sheet-panes")
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private val homelab = Host(
        id = "homelab", name = "homelab", color = SwatchColor.VERDIGRIS, monogram = Host.monogramFor("homelab"), address = "192.168.1.20", port = 22, user = "ben",
        auth = AuthMethod.Password("host-password:homelab"), lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun homelabRecord(state: SessionState = SessionState.DETACHED, layer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME) = SessionRecord(
        id = "s-homelab", workspaceId = Workspace.DEFAULT_ID, hostId = homelab.id, hostSnapshot = homelab, state = state, layer = layer, title = homelab.name,
        cwd = "~/srv", lastCommand = "docker compose ps", sortOrder = 0, createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
    )

    /**
     * A live session on homelab with nowhere to send, its screen restored from a frame: the Stage
     * shows it with the Deck, since only a live tab has one, and no sshd is needed for the frame.
     */
    private fun liveHomelab(): TerminalSession {
        runBlocking {
            graph.hosts.upsert(homelab)
            graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
            graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
            graph.sessionRecords.upsert(homelabRecord())
            graph.sessionRecords.saveFrame(homelabRecord().id, frame(HOMELAB_LINES))
        }
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        val session = TerminalSession(homelabRecord(SessionState.LIVE, PersistenceLayer.IN_APP), CoroutineScope(SupervisorJob() + Dispatchers.Default), env) {}
        session.restoreFrame(frame(HOMELAB_LINES))
        return session
    }

    /** The Stage as the shell mounts it, minus navigation. */
    @Composable
    private fun Stage(session: TerminalSession) {
        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
        StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** [content] in the theme, with a hardware keyboard in the configuration, in touch mode or out of it. */
    private fun mount(keyboardMode: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                WithHardwareKeyboard {
                    Box(Modifier.fillMaxSize()) {
                        if (keyboardMode) InKeyboardMode(content) else content()
                    }
                }
            }
        }
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

    /** The window out of touch mode, as a typed key leaves it: every control shows the focus it holds. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun InKeyboardMode(content: @Composable () -> Unit) {
        val modes = LocalInputModeManager.current
        LaunchedEffect(modes) { modes.requestInputMode(InputMode.Keyboard) }
        content()
    }

    private fun awaitDeck() = compose.waitUntil(10_000) { compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty() }

    /** The 20 dp strip a folded Deck leaves (C4): the one button that says so, naming the layer it opens to. */
    private fun deckStrip() = hasContentDescription("Deck collapsed", substring = true) and role(Role.Button)

    private fun awaitDeckStrip() {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(deckStrip()).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the Deck's strip never stood", e)
        }
    }

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun waitForText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("'$text' never showed", e)
        }
    }

    /** Waits for the one focused control to be [what], failing with [where] the focus was expected and where it is. */
    private fun awaitFocused(what: SemanticsMatcher, where: String) {
        try {
            compose.waitUntil("the focus on $where", 10_000) { compose.onAllNodes(isFocused() and what).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            val holding = compose.onAllNodes(isFocused(), useUnmergedTree = true).printToString(maxDepth = 0)
            throw AssertionError("Expected the focus on $where; it is on:\n$holding", e)
        }
    }

    /** A chord's down, the way a hardware keyboard's arrives: through the focused control's ancestors, the Stage's preview first. */
    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }

    /** A key pressed and released: a focus move happens on the press. */
    private fun press(code: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, 0)))
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_UP, code, 0, 0)))
        compose.waitForIdle()
    }

    /** A text-only frame, version 1. */
    private fun frame(lines: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(1)
            d.writeInt(lines.size)
            lines.forEach(d::writeUTF)
        }
        return out.toByteArray()
    }

    private companion object {
        val HOMELAB_LINES: List<String> = listOf(
            "ben@homelab:~/srv$ docker compose ps",
            "NAME        IMAGE               STATUS        PORTS",
            "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
            "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
            "postgres    postgres:16         Up 3 days     5432/tcp",
            "ben@homelab:~/srv$ journalctl -u caddy -n 3 --no-pager",
            "Sep 19 19:58:01 homelab caddy[812]: serving https://git.homelab.lan on :443",
            "Sep 19 19:58:02 homelab caddy[812]: reverse_proxy upstream gitea:3000 healthy",
            "Sep 19 19:58:03 homelab caddy[812]: certificate renewed for git.homelab.lan",
            "ben@homelab:~/srv$ ",
        )
    }
}

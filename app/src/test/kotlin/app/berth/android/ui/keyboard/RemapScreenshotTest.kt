package app.berth.android.ui.keyboard

import android.app.Application
import android.content.res.Configuration
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_ALT_RIGHT
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_P
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_V
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.KeyEvent.KEYCODE_W
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.assertNoTextCut
import app.berth.android.screenshots.captureAudited
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.DeckKeyTag
import app.berth.android.ui.stage.StageHintTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.ChordAction
import app.berth.domain.model.ChordKey
import app.berth.domain.model.ChordPrefix
import app.berth.domain.model.HardwareKeyboardSettings
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.LeaderKey
import app.berth.domain.model.VolumeButtons
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The remap table, the chord prefix, pass-through and the first Ctrl+W's hint (spec C22, A44,
 * A46), and the volume buttons (A43), written to `build/outputs/roborazzi` with the accessibility
 * audit on each frame: the shortcut sheet with a row listening for its new chord, the line naming
 * what Ctrl+F takes from the shell, the row rebound, the line refusing Ctrl+C; the Stage with
 * pass-through on and its pill in the header; the hint bar a first plain Ctrl+W leaves in place of
 * the close, and the notice after its action; Settings › Hardware keyboard with the prefix, its
 * menu, the Leader key under it, and the Volume buttons panel with its menu; the sheet under the
 * Leader prefix, opened by a tapped Right Alt then `/`; and the Settings panel and the sheet at the
 * interface's 1.3× font cap, no text cut. Every key travels the way a hardware keyboard's does,
 * into the window whose control holds the focus, the Stage's preview first. The Stage rides a live
 * session with nowhere to send (`StageFixture.liveHomelab`), so no sshd is needed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class RemapScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")
    private lateinit var graph: TestGraph

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
        graph.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(name: String) = compose.captureAudited(File(outDir, "$name.png"))

    private val settings get() = graph.viewModel.hardwareKeyboard.value

    // ---- the sheet as the remap table ----------------------------------------------------------------------

    @Test
    fun `a row listens for its chord, names what Ctrl+F takes from the shell, binds it on Enter, and refuses Ctrl+C`() {
        stageWithKeyboard()
        chord(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON)
        waitForText("Keyboard shortcuts")
        // The sheet says what a tap on a row does, since it has the store to write to.
        waitForText("Tap one to rebind it")
        expandSheet()

        // Tapped, Find in scrollback steps up, takes the focus and asks for the chord; its keys give way to the ellipsis.
        row("Find in scrollback").performScrollTo().performClick()
        awaitFocused(hasTestTag(ChordCaptureTag), "the listening row, Find in scrollback")
        row("Find in scrollback, listening for the new chord").assertExists()
        text("Press the new chord on the keyboard. Esc keeps Ctrl+Shift+F.").assertExists()
        compose.onNodeWithText("Use").assertIsNotEnabled()
        capture("shortcut-sheet-remap-listening")

        // Ctrl+F is readline's forward-char: named as the cost, and still offered (C22's conflict detection warns for the shell's keys).
        sheetChord(KEYCODE_F, META_CTRL_ON)
        text("Takes readline\u2019s forward-char from the shell. Enter binds it.").assertExists()
        text("Ctrl+F").assertExists()
        compose.onNodeWithText("Use").assertIsEnabled()
        capture("shortcut-sheet-remap-shell-warning")

        // Enter binds it: the remap lands in the settings store, and the row reads its new keys in accent with what it was and what the shell lost under it.
        sheetChord(KEYCODE_ENTER)
        compose.waitUntil(5_000) { settings.remaps[ChordAction.FIND] == ChordKey("F", ctrl = true) }
        row("Find in scrollback, Ctrl+F, rebound from Ctrl+Shift+F, takes readline\u2019s forward-char from the shell. Rebind").assertExists()
        text("Default Ctrl+Shift+F \u00B7 takes readline\u2019s forward-char from the shell").assertExists()
        assertTrue("no row listens once bound", compose.onAllNodesWithTag(ChordCaptureTag).fetchSemanticsNodes().isEmpty())
        // The focus stays on the row it bound, so a keyboard's user is where they were.
        awaitFocused(hasContentDescription("Find in scrollback", substring = true), "the rebound row")
        capture("shortcut-sheet-remapped-row")

        // Ctrl+C is the shell's interrupt: refused, and Use stays off; Ctrl+Shift+V is Paste's already.
        row("Copy the selection").performScrollTo().performClick()
        awaitFocused(hasTestTag(ChordCaptureTag), "the listening row, Copy the selection")
        sheetChord(KEYCODE_C, META_CTRL_ON)
        text("Ctrl+C is the shell\u2019s interrupt; the shell keeps it.").assertExists()
        compose.onNodeWithText("Use").assertIsNotEnabled()
        capture("shortcut-sheet-remap-refused")
        sheetChord(KEYCODE_V, META_CTRL_ON or META_SHIFT_ON)
        text("Ctrl+Shift+V is taken: Paste.").assertExists()
        compose.onNodeWithText("Use").assertIsNotEnabled()
        sheetChord(KEYCODE_ENTER)
        assertTrue("Enter binds nothing that is refused", ChordAction.COPY !in settings.remaps)
        // Esc leaves the row as it was.
        sheetChord(KEYCODE_ESCAPE)
        row("Copy the selection, Ctrl+Shift+C. Rebind").assertExists()
        assertTrue("no row listens after Esc", compose.onAllNodesWithTag(ChordCaptureTag).fetchSemanticsNodes().isEmpty())

        // Default gives a rebound row its prefix chord back.
        row("Find in scrollback").performScrollTo().performClick()
        awaitFocused(hasTestTag(ChordCaptureTag), "the listening row, Find in scrollback, again")
        compose.onNodeWithText("Default").performClick()
        compose.waitUntil(5_000) { ChordAction.FIND !in settings.remaps }
        row("Find in scrollback, Ctrl+Shift+F. Rebind").assertExists()
    }

    // ---- pass-through -----------------------------------------------------------------------------------------

    @Test
    fun `pass-through puts its pill in the header, sends the chords to the shell, and ends on the pill's tap or its own chord`() {
        stageWithKeyboard()
        awaitDeckStrip()
        chord(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON)
        awaitPill(shown = true)
        // The Deck's chord and the sheet's are the shell's now: the strip stays folded and no sheet opens.
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        chord(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON)
        assertTrue("no Deck key under pass-through", compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isEmpty())
        assertTrue("no sheet under pass-through", compose.onAllNodes(hasText("Keyboard shortcuts")).fetchSemanticsNodes().isEmpty())
        awaitFocused(hasTestTag(TerminalTag), "the terminal, under pass-through")
        capture("stage-pass-through")

        // The pill's tap is the touch way out; the Deck's chord works again.
        compose.onNodeWithTag(PassThroughPillTag).performClick()
        awaitPill(shown = false)
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        awaitDeck()

        // The chord that turned it on turns it off.
        chord(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON)
        awaitPill(shown = true)
        chord(KEYCODE_P, META_CTRL_ON or META_SHIFT_ON)
        awaitPill(shown = false)
    }

    // ---- the first Ctrl+W -----------------------------------------------------------------------------------------

    @Test
    fun `the first plain Ctrl+W on a live shell is held for its hint, and Shell keeps it hands the keys over`() {
        stageWithKeyboard()
        assertFalse(settings.ctrlWHintSeen)
        chord(KEYCODE_W, META_CTRL_ON)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(StageHintTag).fetchSemanticsNodes().isNotEmpty() }
        waitForText("Ctrl+W closes the tab here")
        // The close was held back this once, and the hint is marked seen in the settings store.
        assertTrue("homelab's tab still stands", compose.onAllNodes(role(Role.Tab) and hasContentDescription("homelab", substring = true)).fetchSemanticsNodes().isNotEmpty())
        compose.waitUntil(5_000) { settings.ctrlWHintSeen }
        capture("stage-ctrl-w-hint")

        // Its action: Ctrl+T and Ctrl+W go to the shell, said in the notice's slot; a Ctrl+W after is the shell's and closes nothing.
        compose.onNodeWithText("Shell keeps it").performClick()
        compose.waitUntil(5_000) { graph.viewModel.ctrlTabKeysReachTerminal.value }
        waitForText("Ctrl+T and Ctrl+W go to the shell")
        capture("stage-ctrl-w-hint-shell-keeps")
        chord(KEYCODE_W, META_CTRL_ON)
        compose.waitForIdle()
        assertTrue("homelab's tab still stands, Ctrl+W being the shell's", compose.onAllNodes(role(Role.Tab) and hasContentDescription("homelab", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    // ---- Settings -----------------------------------------------------------------------------------------------------

    @Test
    fun `Settings, the chord prefix and its menu, the Leader key row under it, and the Volume buttons panel with its menu`() {
        StageFixture.seed(graph)
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Keyboard shortcuts").performScrollTo()
        compose.waitForIdle()
        text("Ctrl+Shift+P sends every key to the shell, the chords included, until it is pressed again or its pill is tapped.").assertExists()
        capture("settings-chord-prefix")

        compose.onNodeWithText("Chord prefix").performClick()
        waitForText("Leader key")
        capture("settings-chord-prefix-menu")

        // The Leader key row stands only under the Leader prefix, and the note under it says how a tap and a hold differ.
        compose.onNodeWithText("Leader key").performClick()
        compose.waitUntil(5_000) { settings.chordPrefix == ChordPrefix.LEADER }
        waitForText("Right Alt")
        compose.onNodeWithText("Keyboard shortcuts").performScrollTo()
        compose.waitForIdle()
        text("Right Alt is the app\u2019s and never reaches the shell: hold it with a key, or tap it and the next key is the chord; a second tap or Esc lets a tap go. Leader P sends every key to the shell until it is pressed again.").assertExists()
        text("Leader / opens this from a session").assertExists()
        compose.assertNoTextCut("the Settings screen at 1\u00D7 under the Leader prefix")
        capture("settings-chord-prefix-leader")

        compose.onNodeWithText("On a shell tab").performScrollTo()
        compose.waitForIdle()
        capture("settings-volume-buttons")
        compose.onNodeWithText("On a shell tab").performClick()
        waitForText("Ctrl+C and Enter")
        capture("settings-volume-buttons-menu")
        compose.onNodeWithText("Page Up and Down").performClick()
        compose.waitUntil(5_000) { settings.volumeButtons == VolumeButtons.PAGES }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Page Up and Down")).fetchSemanticsNodes().size == 1 }
    }

    // ---- the Leader prefix on the Stage ----------------------------------------------------------------------------------

    @Test
    fun `under the Leader prefix a tapped Right Alt then slash opens the sheet, which names the Leader on every row`() {
        stageWithKeyboard { it.withChordPrefix(ChordPrefix.LEADER) }
        assertEquals(LeaderKey.RIGHT_ALT, settings.leaderKey)
        // Ctrl+Shift+/ is nobody's now and reaches the shell: no sheet.
        chord(KEYCODE_SLASH, META_CTRL_ON or META_SHIFT_ON)
        assertTrue("Ctrl+Shift+/ opens nothing under the Leader", compose.onAllNodes(hasText("Keyboard shortcuts")).fetchSemanticsNodes().isEmpty())
        // Right Alt tapped, then /: the Leader chord.
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, KEYCODE_ALT_RIGHT, 0, META_ALT_ON or META_ALT_RIGHT_ON)))
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_UP, KEYCODE_ALT_RIGHT, 0, 0)))
        compose.waitForIdle()
        chord(KEYCODE_SLASH, 0)
        waitForText("Keyboard shortcuts")
        waitForText("the Leader, Right Alt, held with the key or tapped before it")
        row("Find in scrollback, Leader F. Rebind").assertExists()
        row("This sheet, Leader /. Rebind").assertExists()
        // The Terminal group's note says the Leader is the one key that never reaches the shell.
        compose.onNode(hasText("The Leader, Right Alt, never does: it is the app\u2019s.", substring = true), useUnmergedTree = true).assertExists()
        expandSheet()
        capture("shortcut-sheet-leader")
    }

    // ---- the volume buttons -----------------------------------------------------------------------------------------------

    @Test
    fun `the volume buttons under Font size step the terminal's font while a shell tab is on stage, and are the volume's again under Off`() {
        StageFixture.seed(graph)
        graph.viewModel.updateHardwareKeyboard { it.copy(volumeButtons = VolumeButtons.FONT_SIZE) }
        compose.waitUntil(5_000) { settings.volumeButtons == VolumeButtons.FONT_SIZE }
        val keys = VolumeKeys()
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab()
        mount(keyboardMode = false) {
            CompositionLocalProvider(LocalVolumeKeys provides keys) { Stage(live) }
        }
        compose.waitUntil(5_000) { keys.handler != null }
        val before = graph.viewModel.terminalFont.value.sizeSp
        // The press acts, and the release is swallowed with it, or the system would show its volume panel.
        assertTrue(keys.dispatch(android.view.KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP)))
        assertTrue(keys.dispatch(android.view.KeyEvent(ACTION_UP, KEYCODE_VOLUME_UP)))
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.sizeSp == before + 1 }
        assertTrue(keys.dispatch(android.view.KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_DOWN)))
        compose.waitUntil(5_000) { graph.viewModel.terminalFont.value.sizeSp == before }
        // Off: the binding is dropped, and the buttons are the volume's.
        graph.viewModel.updateHardwareKeyboard { it.copy(volumeButtons = VolumeButtons.OFF) }
        compose.waitUntil(5_000) { keys.handler == null }
        assertFalse(keys.dispatch(android.view.KeyEvent(ACTION_DOWN, KEYCODE_VOLUME_UP)))
        assertEquals(before, graph.viewModel.terminalFont.value.sizeSp)
    }

    // ---- the interface's font cap -----------------------------------------------------------------------------------------

    @Test
    fun `Settings under the Leader prefix and the sheet with a rebound row at the font cap, no text cut`() {
        // The system's largest font size, set before the first composition reads the configuration; interface text stops at 1.3×.
        RuntimeEnvironment.setFontScale(2f)
        StageFixture.seed(graph)
        graph.viewModel.updateHardwareKeyboard { it.withChordPrefix(ChordPrefix.LEADER).withRemap(ChordAction.FIND, ChordKey("F", ctrl = true)) }
        compose.waitUntil(5_000) { settings.chordPrefix == ChordPrefix.LEADER && ChordAction.FIND in settings.remaps }
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Keyboard shortcuts").performScrollTo()
        compose.waitForIdle()
        text("Leader / opens this from a session \u00B7 1 rebound").assertExists()
        compose.assertNoTextCut("the Settings screen at the interface's font cap, under the Leader prefix")
        capture("settings-chord-prefix-leader-font-scale-2x")

        // The sheet from the Settings row: the rebound row's two-line caption, and every row whole.
        compose.onNodeWithText("Keyboard shortcuts").performClick()
        waitForText("Tap one to rebind it")
        expandSheet()
        row("Find in scrollback").performScrollTo()
        compose.waitForIdle()
        text("Default Leader F \u00B7 takes readline\u2019s forward-char from the shell").assertExists()
        capture("shortcut-sheet-remapped-row-font-scale-2x")
        // The sheet's column is composed whole, so the check reads every row of it, scrolled to or not.
        compose.assertNoTextCut("the shortcut sheet at the interface's font cap, a row rebound")
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------------------

    /**
     * Homelab live on stage beside the fixture's strip, a keyboard attached and the window out of
     * touch mode, the terminal focused; [settings] first changes the hardware keyboard's settings,
     * after the seed, since the graph's view model restores the tabs the moment it is first touched.
     */
    private fun stageWithKeyboard(settings: ((HardwareKeyboardSettings) -> HardwareKeyboardSettings)? = null): TerminalSession {
        StageFixture.seed(graph)
        if (settings != null) {
            val wanted = settings(this.settings)
            graph.viewModel.updateHardwareKeyboard(settings)
            compose.waitUntil(5_000) { this.settings == wanted }
        }
        graph.sessions.setActive("s-homelab")
        val live = StageFixture.liveHomelab()
        mount(keyboardMode = true) { Stage(live) }
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Tabs, 3 open")).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the strip never stood with its three tabs; on stage:\n${compose.onAllNodes(isRoot()).printToString(maxDepth = 4)}", e)
        }
        awaitFocused(hasTestTag(TerminalTag), "the terminal, with a keyboard attached and a shell tab on stage")
        return live
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

    /**
     * The half-open sheet to its full height, through the handle's own Expand action (what a
     * reader would do), so a row the test scrolls to is on the screen and not in the sheet's
     * lower half below it.
     */
    private fun expandSheet() {
        compose.onNode(hasContentDescription("Drag handle")).performSemanticsAction(SemanticsActions.Expand)
        compose.waitForIdle()
    }

    /** A row of the sheet by what a reader hears of it, whole or its opening words. */
    private fun row(spoken: String): SemanticsNodeInteraction =
        compose.onNode(hasContentDescription(spoken, substring = true) and hasClickAction())

    /** A text on screen, found under the row that merges it. */
    private fun text(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text), useUnmergedTree = true)

    private fun awaitDeck() = compose.waitUntil(10_000) { compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty() }

    private fun awaitDeckStrip() {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Deck collapsed", substring = true) and role(Role.Button)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the Deck's strip never stood", e)
        }
    }

    private fun awaitPill(shown: Boolean) {
        try {
            compose.waitUntil(5_000) { compose.onAllNodesWithTag(PassThroughPillTag).fetchSemanticsNodes().isNotEmpty() == shown }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(if (shown) "the pass-through pill never showed" else "the pass-through pill never went", e)
        }
    }

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun waitForText(text: String) {
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
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

    /** A chord's down, the way a hardware keyboard's arrives on the Stage: through the focused control's ancestors, the Stage's preview first. */
    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }

    /** A chord's down into the sheet's window, where the listening row holds the focus. */
    private fun sheetChord(code: Int, meta: Int = 0) {
        compose.onNodeWithTag(ChordCaptureTag).performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
        compose.waitForIdle()
    }
}

package app.berth.android.screenshots

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.isDialog
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.TerminalSession
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.io.MAX_TEXT_BYTES
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.GroupEditorRequest
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabSheets
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.android.ui.themes.TerminalThemeEditorScreen
import app.berth.android.ui.themes.ThemeScope
import app.berth.android.ui.themes.ThemesScreen
import app.berth.domain.model.AccentPreset
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckModifier
import app.berth.domain.model.DeckReach
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.Snippet
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.ThemeSlot
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The customisation screens, in Berth Dark on a Pixel-class phone, on in-memory storage: the
 * theme gallery with a pasted base16 import, Settings' About licences, the terminal theme editor
 * with its colour sheet, apply panel and accent offer, the interface
 * editor, the Deck editor with the action catalogue, the presets sheet, the layout panel, a two-row
 * left-reach layout and a Termux import, plus the Stage picking up a workspace theme. Written to
 * `build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class EditorScreenshotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val outDir = File(System.getProperty("user.dir"), "build/outputs/roborazzi")

    /** The Libraries row whose one text is each of its five libraries' own. */
    private val SHARED_APACHE = "Dagger and Hilt, JSpecify, listenablefuture, javax.inject and JSR 305"
    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        if (System.getProperty("roborazzi.test.record") == null && System.getProperty("roborazzi.test.verify") == null) {
            System.setProperty("roborazzi.test.record", "true")
        }
        SshSecurity.ensureProviders()
        outDir.mkdirs()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        seed()
    }

    @After
    fun tearDown() {
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

    /** Under the theme the shell draws with, the current group's accent over the app's, as AppRoot mounts it. */
    private fun shellThemed(content: @Composable () -> Unit) {
        compose.setContent {
            val theme by graph.viewModel.shownInterfaceTheme.collectAsState()
            BerthTheme(theme) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /** The Stage as the shell mounts it, with tab actions that reach the manager but no navigation. */
    @Composable
    private fun Stage(session: TerminalSession) {
        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
        StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
    }

    /**
     * Runs the main looper with the compose clock until [condition] holds, then once more so what
     * follows from it on the main thread has landed: a tap in a sheet or menu, its own window, is
     * delivered through the looper, and so is every view-model flow (the view model's scope), none
     * of which a bare waitUntil runs.
     */
    private fun awaitOnMain(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            if (condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                return
            }
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Swipes the screen's scrolling column up to its end, so a capture shows the page's foot whole. */
    private fun scrollToEnd() {
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
    }

    /** Taps the modal sheet's scrim near the top of the screen, where the sheet itself is not. */
    private fun dismissSheet() {
        compose.onNodeWithContentDescription("Close sheet").performTouchInput { click(Offset(width / 2f, 60f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }

    // ---- themes -----------------------------------------------------------------------------------

    @Test
    fun `theme gallery`() {
        themed { ThemesScreen(graph.viewModel, onBack = {}, onOpen = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Theme Berth Dark", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("themes")
    }

    /**
     * The four editors' headers at the interface's font cap: the back action leading, the title,
     * the actions trailing (the Deck editor's is in its presets frame at the cap). Their trailing
     * lambdas are their actions again, which they stopped being when ScreenHeader's navigation
     * slot arrived after the actions parameter.
     */
    @Test
    fun `theme gallery at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        themed { ThemesScreen(graph.viewModel, onBack = {}, onOpen = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Theme Berth Dark", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        capture("themes-font-scale-2x")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `theme gallery's last row, the GitHub pair`() = githubPair("")

    @Test
    fun `theme gallery's last row, the GitHub pair at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        githubPair("-font-scale-2x")
    }

    /** The gallery's last row: each GitHub name breaks before "High Contrast", its full name kept. */
    private fun githubPair(suffix: String) {
        themed { ThemesScreen(graph.viewModel, onBack = {}, onOpen = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Theme Berth Dark", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Theme GitHub Light High Contrast", substring = true))
        compose.waitForIdle()
        capture("themes-github-pair$suffix")
        for (name in listOf("GitHub Dark High Contrast", "GitHub Light High Contrast")) {
            val layout = compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().textLayout()!!
            val lines = (0 until layout.lineCount).map { name.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
            assertEquals("$name's lines", listOf(name.removeSuffix(" High Contrast"), "High Contrast"), lines)
        }
    }

    @Test
    fun `a pasted base16 scheme lands at the gallery's foot under its own name`() = pastedImport("")

    @Test
    fun `a pasted base16 scheme lands at the gallery's foot at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        pastedImport("-font-scale-2x")
    }

    /**
     * The paste sheet names the formats it reads; a base16 scheme carries its name, which the new
     * last tile takes. Started from the header menu with the gallery at its top, the answer is on
     * the notice bar there, not a screen below at the grid's foot.
     */
    private fun pastedImport(suffix: String) {
        themed { ThemesScreen(graph.viewModel, onBack = {}, onOpen = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Theme Berth Dark", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Paste theme text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Paste theme text").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Berth JSON, iTerm2, Ghostty, Windows Terminal, base16 or Termux colours").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput(TomorrowNightBase16)
        compose.waitForIdle()
        capture("themes-paste-sheet$suffix")

        compose.onNodeWithText("Import").performClick()
        awaitOnMain("the import to reach the view model") { graph.viewModel.terminalThemes.value.lastOrNull()?.name == "Tomorrow Night" }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Imported Tomorrow Night.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Imported Tomorrow Night.").assertIsDisplayed()
        compose.onNode(hasContentDescription("Theme Berth Dark", substring = true)).assertIsDisplayed()
        capture("themes-imported$suffix")
        scrollToEnd()
        compose.onNode(hasContentDescription("Theme Tomorrow Night", substring = true)).assertIsDisplayed()
        val imported = graph.viewModel.terminalThemes.value.last()
        assertEquals(0x1D1F21, imported.background)
        assertFalse(imported.builtIn)
    }

    @Test
    fun `Settings About opens the shipped licences`() = aboutPanel("")

    @Test
    fun `Settings About opens the shipped licences at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        aboutPanel("-font-scale-2x")
    }

    /**
     * About keeps one sentence and a Licences row; the row's sheet lists each font, library and
     * palette with its terms, a row with a shipped text opens it in place, and Back returns to the
     * list. Gruvbox, whose upstream has no licence file, is a credit with nothing to open. The
     * symbols font's text ends in its icon sets' table, a paragraph a row, its cells set apart.
     */
    private fun aboutPanel(suffix: String) {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Licences").performScrollTo()
        scrollToEnd()
        capture("settings-about$suffix")
        val app = RuntimeEnvironment.getApplication()
        val version = "Berth ${app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""}".trim()
        val rowInset = compose.onNodeWithText("Licences", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        for (line in listOf(version, "No account. No telemetry. Everything stays on this device.")) {
            val left = compose.onNodeWithText(line, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
            assertEquals("\"$line\" starts where the Licences row's title does", rowInset, left, 0.5f)
        }

        compose.onNodeWithText("Licences").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }
        capture("settings-licences$suffix")
        compose.onNodeWithText("under the MIT/X11 licence its README states", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gruvbox Dark and Light").assertHasNoClickAction()
        compose.onNodeWithText("Tokyo Night, by folke").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Catppuccin Mocha and Latte").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copyright (c) 2021 Catppuccin").fetchSemanticsNodes().isNotEmpty() }
        capture("settings-licence-text$suffix")
        compose.onNodeWithText("Permission is hereby granted, free of charge", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Back to Licences").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Symbols Nerd Font Mono, from Nerd Fonts").performScrollTo().performClick()
        val codicons = "Codicons \u00B7 https://github.com/microsoft/vscode-codicons \u00B7 0.0.45 \u00B7 CC BY 4.0"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(codicons).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(codicons).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Weather Icons \u00B7 https://github.com/erikflowers/weather-icons \u00B7 2.0.10 (1.100) \u00B7 OFL 1.1").performScrollTo()
        capture("settings-licence-table$suffix")
        compose.onNodeWithContentDescription("Back to Licences").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Public Suffix List, for link captions").performScrollTo()
        capture("settings-licences-libraries$suffix")
        for (library in listOf("sshj, the SSH transport", "asn-one, sshj's ASN.1 coding", "Bouncy Castle, the cryptography", "SLF4J, the transport's logging", "Public Suffix List, for link captions")) {
            compose.onNode(hasText(library) and hasClickAction()).assertExists()
        }

        compose.onNodeWithText("sshj, the SSH transport").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("sshj - SSHv2 library for Java", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("From github.com/hierynomus/sshj at v0.40.0: LICENSE and NOTICE").assertIsDisplayed()
        capture("settings-licence-sshj$suffix")
        compose.onNodeWithText("sshj - SSHv2 library for Java", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Licences").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }

        val platform = listOf(
            "AndroidX, the interface and the database", "Kotlin, the language's standard library", "kotlinx.coroutines, the concurrency",
            "kotlinx.serialization, the stored formats", "JetBrains annotations", "Jakarta Dependency Injection, Hilt's annotations", SHARED_APACHE,
        )
        compose.onNodeWithText(SHARED_APACHE).performScrollTo()
        capture("settings-licences-platform$suffix")
        for (library in platform) compose.onNode(hasText(library) and hasClickAction()).assertExists()
        compose.assertNoTextCut("the Licences list$suffix")
        compose.assertNoBrokenWords("the Licences list$suffix")

        compose.onNodeWithText(SHARED_APACHE).performClick()
        val origins = "From github.com/google/dagger at dagger-2.60.1: LICENSE.txt; github.com/jspecify/jspecify at v1.0.0: LICENSE;"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(origins, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(origins, substring = true).assertIsDisplayed()
        capture("settings-licence-apache-shared$suffix")
        compose.assertNoTextCut("the shared Apache text$suffix")
        compose.assertNoBrokenWords("the shared Apache text$suffix")
        compose.onNodeWithContentDescription("Back to Licences").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }

        // Kotlin's NOTICE follows its licence in the one text, as sshj's does.
        compose.onNodeWithText("Kotlin, the language's standard library").performScrollTo().performClick()
        val notice = "Kotlin Compiler\nCopyright 2010-2024 JetBrains s.r.o and respective authors and developers"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(notice).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        capture("settings-licence-kotlin-notice$suffix")
        compose.assertNoTextCut("Kotlin's licence and NOTICE$suffix")
        compose.assertNoBrokenWords("Kotlin's licence and NOTICE$suffix")
        compose.onNodeWithContentDescription("Back to Licences").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Public Suffix List, for link captions").performScrollTo().performClick()
        val disclaimer = "6. Disclaimer of Warranty\n"
        compose.waitUntil(5_000) { compose.onAllNodesWithText(disclaimer, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(disclaimer, substring = true).performScrollTo()
        val warranty = compose.onNodeWithText("Covered Software is provided under this License on an \"as is\" basis, without warranty", substring = true)
        warranty.performScrollTo()
        capture("settings-licence-disclaimer$suffix")
        warranty.assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Licences").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("What Berth ships that came under terms of its own").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `terminal theme editor at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.BERTH_DARK_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = { _, _ -> }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth Dark")).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal-theme-editor-font-scale-2x")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `interface editor at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        capture("appearance-font-scale-2x")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `terminal theme editor and its colour sheet`() {
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.BERTH_DARK_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = { _, _ -> }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth Dark")).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal-theme-editor")

        compose.onNode(hasContentDescription("Blue #", substring = true)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Pick from preview")).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal-theme-colour-sheet")
        dismissSheet()

        // The end of the page: which scope the theme applies to, the primary action and export.
        compose.onNodeWithText("Export").performScrollTo()
        capture("terminal-theme-editor-apply")
    }

    @Test
    fun `terminal theme export sheet`() = exportSheet("terminal-theme-export")

    @Test
    fun `terminal theme export sheet at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        exportSheet("terminal-theme-export-font-scale-2x")
    }

    /** Dracula's links are its purple, not its blue, and Ghostty has no link colour: that row is off and says so. */
    private fun exportSheet(name: String) {
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.DRACULA_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = { _, _ -> }) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Export Dracula").fetchSemanticsNodes().isNotEmpty() }
        capture(name)
        compose.onNode(hasText("Berth JSON")).assertIsEnabled()
        compose.onNode(hasText("iTerm2") and hasText("Dracula.itermcolors")).assertIsEnabled()
        compose.onNode(hasText("Ghostty") and hasText("Would lose the link colour")).assertIsNotEnabled()
    }

    @Test
    fun `interface editor`() {
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        capture("appearance")
    }

    @Test
    fun `an accent picked in Settings is the same chip in the Interface editor`() = accentAcrossScreens("")

    @Test
    fun `an accent picked in Settings is the same chip in the Interface editor at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        accentAcrossScreens("-font-scale-2x")
    }

    /**
     * Settings and the Interface editor list one set of accents (spec A2), so a preset picked in
     * Settings is that preset in the editor rather than Custom, and a hex typed in the editor is
     * Custom back in Settings. Drawn in the theme the shell draws, so each frame wears the pick.
     */
    private fun accentAcrossScreens(suffix: String) {
        var editor by mutableStateOf(false)
        shellThemed { if (editor) AppearanceScreen(graph.viewModel, onBack = {}) else SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Rose").performScrollTo().performClick()
        awaitOnMain("the shell to draw in Rose") { graph.viewModel.shownInterfaceTheme.value.accent == AccentPreset.ROSE.rgb }
        compose.onNodeWithText("Material You").assertIsNotSelected()
        capture("settings-accent$suffix")

        editor = true
        compose.onNodeWithText("Rose").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Custom").assertIsNotSelected()
        compose.onNodeWithText("Custom").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#4FA3D9")
        awaitOnMain("the shell to draw in #4FA3D9") { graph.viewModel.shownInterfaceTheme.value.accent == 0x4FA3D9 }
        compose.onNodeWithText("Rose").assertIsNotSelected()
        capture("appearance-accent-custom$suffix")

        editor = false
        compose.onNodeWithText("Custom").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Rose").assertIsNotSelected()
        compose.onNodeWithText("Verdigris").performClick()
        awaitOnMain("the write to reach the view model") { graph.viewModel.interfaceTheme.value.accent == AccentPreset.VERDIGRIS.rgb }
        compose.onNodeWithText("Custom").assertIsNotSelected()
    }

    @Test
    fun `a group's accent is the interface's while the group is current`() = groupAccent("")

    @Test
    fun `a group's accent is the interface's while the group is current at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        groupAccent("-font-scale-2x")
    }

    /**
     * The group editor's Accent (spec C8): Inherit, drawn in the app's accent, until one of the
     * app's list is picked; the pick is the interface's accent while the group is current (the
     * active tab's), the Stage behind the sheet included, another group's does not reach it, and
     * Inherit hands it back. The app's own accent, which the pickers show, is untouched throughout.
     */
    private fun groupAccent(suffix: String) {
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        val ui = TabUiState().apply { groupEditor = GroupEditorRequest.Edit(Workspace.DEFAULT_ID) }
        fun home() = graph.workspaces.items.value.first { it.id == Workspace.DEFAULT_ID }
        shellThemed {
            Stage(session)
            TabSheets(graph.viewModel, ui, remember { ShellTabActions(graph.viewModel, ui, onActivated = {}) }, onAddHost = {})
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit group").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Inherit").performScrollTo().assertIsSelected()
        capture("group-editor-accent$suffix")

        compose.onNodeWithText("Rose").performClick()
        awaitOnMain("Home's accent to be Rose") { home().accentRgb == AccentPreset.ROSE.rgb }
        assertEquals(Workspace.DEFAULT_ID, graph.viewModel.currentWorkspaceId.value)
        awaitOnMain("the write to reach the view model") { graph.viewModel.shownInterfaceTheme.value.accent == AccentPreset.ROSE.rgb }
        assertEquals("the app's own accent is untouched", AccentPreset.COPPER.rgb, graph.viewModel.interfaceTheme.value.accent)
        compose.onNodeWithText("Inherit").assertIsNotSelected()
        capture("group-editor-accent-rose$suffix")

        graph.viewModel.setWorkspaceAccent("ws-work", AccentPreset.MOSS.rgb)
        awaitOnMain("Work's accent to reach the view model") { graph.viewModel.workspaces.value.first { it.id == "ws-work" }.accentRgb == AccentPreset.MOSS.rgb }
        assertEquals("Work is not current", AccentPreset.ROSE.rgb, graph.viewModel.shownInterfaceTheme.value.accent)

        ui.groupEditor = null
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit group").fetchSemanticsNodes().isEmpty() }
        capture("stage-group-accent-rose$suffix")

        ui.groupEditor = GroupEditorRequest.Edit(Workspace.DEFAULT_ID)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Edit group").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Rose").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Inherit").performClick()
        awaitOnMain("Home to inherit the app's accent") { home().accentRgb == null }
        awaitOnMain("the write to reach the view model") { graph.viewModel.shownInterfaceTheme.value.accent == AccentPreset.COPPER.rgb }
    }

    @Test
    fun `while a group's own accent is in force the app's pickers say so`() = groupAccentNote("")

    @Test
    fun `while a group's own accent is in force the app's pickers say so at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        groupAccentNote("-font-scale-2x")
    }

    /**
     * Settings' and the Interface editor's accent pickers set the app's accent (spec A2): while Work
     * is current with Rose of its own, a line under each says whose accent the chrome is in and what
     * a pick there sets; once Work inherits again the line goes.
     */
    private fun groupAccentNote(suffix: String) {
        val note = "While Work is current the interface uses its accent, Rose. This sets the app's, for groups on Inherit."
        runBlocking { graph.sessions.restore() }
        graph.sessions.setCurrentWorkspace("ws-work", activate = false)
        // An accent set on a group the manager has not loaded yet is dropped, so Work has to be there first.
        awaitOnMain("Work to reach the view model") { graph.viewModel.workspaces.value.any { it.id == "ws-work" } }
        graph.viewModel.setWorkspaceAccent("ws-work", AccentPreset.ROSE.rgb)
        awaitOnMain("Rose to be the chrome's") { graph.viewModel.shownInterfaceTheme.value.accent == AccentPreset.ROSE.rgb }
        var appearance by mutableStateOf(false)
        shellThemed {
            if (appearance) AppearanceScreen(graph.viewModel, onBack = {}) else SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {})
        }
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Copper").assertIsSelected()
        capture("settings-group-accent-note$suffix")

        appearance = true
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(note).performScrollTo().assertIsDisplayed()

        graph.viewModel.setWorkspaceAccent("ws-work", null)
        awaitOnMain("Work to inherit the app's accent") { graph.viewModel.shownInterfaceTheme.value.accent == AccentPreset.COPPER.rgb }
        compose.onAllNodesWithText(note).assertCountEquals(0)
    }

    @Test
    fun `applying a theme offers its suggested accent`() = accentOffer("")

    @Test
    fun `applying a theme offers its suggested accent at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        accentOffer("-font-scale-2x")
    }

    /**
     * The suggested accent on apply (spec A10): Dracula applied to Work offers its purple for the
     * interface while Work is current, and taking it sets Work's accent and leaves the app's; applied
     * to the app default it offers the purple again, for the app now, and taken, the offer goes;
     * applied to a host, which has no accent, it offers nothing.
     */
    private fun accentOffer(suffix: String) {
        val purple = TerminalTheme.DRACULA.suggestedAccent!!
        shellThemed {
            TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.DRACULA_ID, scope = ThemeScope.ForWorkspace("ws-work"), onDone = {}, onOpenTheme = { _, _ -> })
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Apply to Work").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply to Work").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(AccentOffer).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Applied to Work.").assertExists()
        compose.onNodeWithText("#bd93f9, while Work is current").assertExists()
        scrollToEnd()
        capture("theme-editor-accent-offer-group$suffix")
        compose.onNodeWithText(AccentOffer).performClick()
        awaitOnMain("the write to reach the view model") { graph.workspaces.items.value.first { it.id == "ws-work" }.accentRgb == purple }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(AccentOffer).fetchSemanticsNodes().isEmpty() }
        assertEquals("the app's accent is left", AccentPreset.COPPER.rgb, graph.viewModel.interfaceTheme.value.accent)

        compose.onNodeWithText("Apply to").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("App default").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("App default").performClick()
        awaitOnMain("the app default picked") { compose.onAllNodesWithText("Apply to app default").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Apply to app default").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Applied to the app default.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("#bd93f9").assertExists()
        scrollToEnd()
        capture("theme-editor-accent-offer$suffix")
        compose.onNodeWithText(AccentOffer).performClick()
        awaitOnMain("the write to reach the view model") { graph.viewModel.interfaceTheme.value.accent == purple }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(AccentOffer).fetchSemanticsNodes().isEmpty() }
        scrollToEnd()
        capture("theme-editor-accent-taken$suffix")

        compose.onNodeWithText("Apply to").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Host \u00B7 homelab").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Host \u00B7 homelab").performClick()
        awaitOnMain("homelab picked") { compose.onAllNodesWithText("Apply to homelab").fetchSemanticsNodes().isNotEmpty() }
        // Back to copper, so the purple would be on offer if a host were offered anything.
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(accent = AccentPreset.COPPER.rgb))
        awaitOnMain("the write to reach the view model") { graph.viewModel.interfaceTheme.value.accent == AccentPreset.COPPER.rgb }
        compose.onNodeWithText("Apply to homelab").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Applied to homelab.").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(AccentOffer).assertCountEquals(0)
    }

    /**
     * Applying an edited stock theme stores a copy and the editor carries on in it, fresh, as the
     * shell's back stack swaps the entry; the apply's note and the copy's inherited suggestion come
     * with it.
     */
    @Test
    fun `an edited stock theme's copy opens with the apply's note and accent offer`() {
        var screen by mutableStateOf(Triple<String, ThemeScope, Boolean>(TerminalTheme.DRACULA_ID, ThemeScope.AppDefault, false))
        shellThemed {
            val (id, scope, applied) = screen
            key(id) {
                TerminalThemeEditorScreen(
                    graph.viewModel,
                    themeId = id,
                    scope = scope,
                    onDone = {},
                    onOpenTheme = { newId, appliedTo -> screen = Triple(newId, appliedTo ?: scope, appliedTo != null) },
                    applied = applied,
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dracula")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Background").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Pick from preview")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("#1B2A41")
        compose.onNodeWithText("Done").performClick()
        awaitOnMain("the colour sheet closed") { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Apply to app default").performScrollTo().performClick()
        awaitOnMain("the write to reach the view model") { graph.viewModel.defaultTerminalTheme.value.background == 0x1B2A41 }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Dracula copy")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(ThemeScope.AppDefault to true, screen.second to screen.third)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Applied to the app default.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(AccentOffer).assertExists()
    }

    @Test
    fun `setting a theme as the app default in the gallery offers its accent`() = galleryAccentOffer("")

    @Test
    fun `setting a theme as the app default in the gallery offers its accent at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        galleryAccentOffer("-font-scale-2x")
    }

    /** The gallery's Set as app default offers the theme's accent on the notice bar, and its action takes it. */
    private fun galleryAccentOffer(suffix: String) {
        val mauve = TerminalTheme.CATPPUCCIN_MOCHA.suggestedAccent!!
        shellThemed { ThemesScreen(graph.viewModel, onBack = {}, onOpen = {}) }
        val tile = hasContentDescription("Theme Catppuccin Mocha")
        compose.waitUntil(5_000) { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(tile).performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Set as app default").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Set as app default").performClick()
        awaitOnMain("the accent offered") { compose.onAllNodesWithText("Use its accent").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(TerminalTheme.CATPPUCCIN_MOCHA_ID, graph.viewModel.defaultTerminalTheme.value.id)
        compose.onNodeWithText("Catppuccin Mocha is the app default").assertExists()
        capture("themes-accent-offer$suffix")
        compose.onNodeWithText("Use its accent").performClick()
        awaitOnMain("the write to reach the view model") { graph.viewModel.interfaceTheme.value.accent == mauve }
        assertEquals(false, graph.viewModel.interfaceTheme.value.materialYou)
    }

    /** Settings opens on Input, so the frame scrolls to Appearance, where the two editor rows are. */
    @Test
    fun `settings with the editor rows`() {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        compose.onNodeWithText("Terminal themes").performScrollTo()
        compose.waitForIdle()
        capture("settings-editors")
        compose.onNodeWithText("Interface editor").assertIsDisplayed()
        compose.onNodeWithText("Terminal themes").assertIsDisplayed()
    }

    // ---- deck -------------------------------------------------------------------------------------

    @Test
    fun `deck editor, catalogue, presets and a two-row left-reach layout`() {
        themed { DeckEditorScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Slot 3").fetchSemanticsNodes().isNotEmpty() }

        // Selecting the Ctrl key fills the slot panel with its four gestures.
        compose.onNodeWithContentDescription("Slot 3").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Swipe up")).fetchSemanticsNodes().isNotEmpty() }
        capture("deck-editor")

        compose.onNodeWithText("Swipe up").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search keys and actions")).fetchSemanticsNodes().isNotEmpty() }
        capture("deck-editor-catalogue")

        // The Snippet chip lists the saved snippets by name, the unpinned one included.
        compose.onNodeWithText("Snippet").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("tail caddy")).fetchSemanticsNodes().isNotEmpty() }
        capture("deck-editor-catalogue-snippets")
        dismissSheet()

        // Picking a snippet binds its id to the gesture, and the row names it; the free swipe-down
        // takes it so the key's ^C hint stays in the strip.
        compose.onNodeWithText("Swipe down").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search keys and actions")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Snippet").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Disk")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Disk").performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.layers[0].keys[2].down == DeckAction.Snippet("snip-disk") }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Snippet \u00B7 Disk").assertExists()

        // Picking Page up for hold is written straight through to settings, which is what the Stage reads.
        compose.onNodeWithText("Hold").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search keys and actions")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PgUp").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.layers[0].keys[2].hold == DeckAction.Key(DeckKeyCode.PGUP) }

        // The Snippets layer previews as one key per pinned snippet, named after them. In the editor
        // a slot is one button to a reader and what it holds is its state, so the slot names them.
        // The layer chips and the Presets row stay in the frames that follow, so they are chosen through their
        // click action rather than pressed: a press leaves a ripple that Robolectric never finishes, its sparkle
        // drawn off the wall clock, so two frames of it never match.
        compose.onNodeWithText("Snippets").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasStateDescription("Snippets: compose ps, tail caddy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Slot 1").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Expands to one key per pinned snippet: compose ps, tail caddy.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Deck preview").performScrollTo()
        capture("deck-editor-snippets-layer")
        compose.onNodeWithText("Base").performSemanticsAction(SemanticsActions.OnClick)

        compose.onNodeWithText("Presets").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preset Vim", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(500)
        capture("deck-editor-presets")
        compose.assertSheetAtContentHeight("Preset Default", "Preset Vim", "Preset tmux", "Preset Minimal")
        dismissSheet()

        // The layout panel and the import, export and preset actions at the end of the page.
        compose.onNodeWithText("Import").performScrollTo()
        compose.settle(500)
        capture("deck-editor-layout")

        compose.onNodeWithText("Two").performScrollTo().performClick()
        compose.onNodeWithText("Left").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.rows == 2 && graph.viewModel.deckLayout.value.reach == DeckReach.LEFT }
        compose.onNodeWithContentDescription("Deck preview").performScrollTo()
        compose.settle(500)
        capture("deck-editor-two-rows-left")

        // Undo walks back through the changes that were applied live.
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.rows == 1 && graph.viewModel.deckLayout.value.reach == DeckReach.RIGHT }
        assertEquals(DeckAction.Key(DeckKeyCode.PGUP), graph.viewModel.deckLayout.value.layers[0].keys[2].hold)
        assertEquals(DeckAction.Snippet("snip-disk"), graph.viewModel.deckLayout.value.layers[0].keys[2].down)

        // Termux extra-keys pasted into the import sheet become the Deck's layers, one per row.
        compose.onNodeWithText("Import").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Import a Deck")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("extra-keys = [['ESC','/','-','HOME','UP','END'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT']]")
        compose.settle(500)
        capture("deck-editor-import")
        compose.onAllNodesWithText("Import").onLast().performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.layers.size == 2 && graph.viewModel.deckLayout.value.rows == 2 }
        val imported = graph.viewModel.deckLayout.value
        assertEquals(listOf("Row 1", "Row 2"), imported.layers.map { it.name })
        assertEquals(DeckAction.Key(DeckKeyCode.ESC), imported.layers[0].keys[0].tap)
        assertEquals(DeckAction.Modifier(DeckModifier.CTRL), imported.layers[1].keys[1].tap)
    }

    @Test
    fun `presets sheet at the 1,3 cap`() {
        // The sheet at the interface's font cap (A9): the four presets, each a Deck drawn whole, open at the content's height.
        RuntimeEnvironment.setFontScale(2f)
        themed { DeckEditorScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Slot 3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Presets").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preset Vim", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(500)
        capture("deck-editor-presets-font-scale-2x")
        compose.assertSheetAtContentHeight("Preset Default", "Preset Vim", "Preset tmux", "Preset Minimal")
    }

    @Test
    fun `a Deck file over the limit is refused in the import sheet, which names the limit`() = oversizedDeckFile("")

    @Test
    fun `a Deck file over the limit is refused in the import sheet at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        oversizedDeckFile("-font-scale-2x")
    }

    /** The import sheet's Open file answered with a file one byte over what a picked document may hold. */
    private fun oversizedDeckFile(suffix: String) {
        val file = File.createTempFile("deck", ".json").apply {
            deleteOnExit()
            writeBytes(ByteArray(MAX_TEXT_BYTES + 1) { ' '.code.toByte() })
        }
        val picker = PickedDocument(Uri.fromFile(file))
        themed { CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) { DeckEditorScreen(graph.viewModel, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Slot 3").fetchSemanticsNodes().isNotEmpty() }
        val before = graph.viewModel.deckLayout.value
        compose.onNodeWithText("Import").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Import a Deck")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open file").performClick()
        val refusal = "That file is over 2 MB, too big for a Deck."
        awaitOnMain("the refusal") { compose.onAllNodesWithText(refusal).fetchSemanticsNodes().isNotEmpty() }
        compose.settle(500)
        capture("deck-editor-import-too-big$suffix")
        compose.onNodeWithText(refusal).assertIsDisplayed()
        compose.onNode(hasText("Import a Deck")).assertIsDisplayed()
        assertEquals(1, picker.launches)
        assertEquals(before, graph.viewModel.deckLayout.value)
        compose.assertNoTextCut("the Deck import's refusal$suffix", within = isDialog())
        compose.assertNoBrokenWords("the Deck import's refusal$suffix", within = isDialog())
    }

    /** Answers every launch at once with [uri], as the system's picker does when a file is chosen. */
    private class PickedDocument(private val uri: Uri) : ActivityResultRegistryOwner {
        var launches = 0
            private set

        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launches++
                dispatchResult(requestCode, uri)
            }
        }
    }

    /**
     * The edit-to-Stage loop as a frame sequence, standing in for an emulator recording: the
     * background of Berth Dark is changed in the colour sheet, applied as the app default, and the
     * Stage behind the editor comes back painted with the copy. Frames land in `flow/`.
     */
    @Test
    fun `editing a theme repaints the stage`() {
        runBlocking { graph.sessions.restore() }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        var onStage by mutableStateOf(false)
        var themeId by mutableStateOf(TerminalTheme.BERTH_DARK_ID)
        themed {
            if (onStage) {
                Stage(session)
            } else {
                TerminalThemeEditorScreen(graph.viewModel, themeId = themeId, scope = ThemeScope.AppDefault, onDone = { onStage = true }, onOpenTheme = { id, _ -> themeId = id })
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth Dark")).fetchSemanticsNodes().isNotEmpty() }
        capture("flow/01-editor")

        compose.onNodeWithText("Background").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Pick from preview")).fetchSemanticsNodes().isNotEmpty() }
        capture("flow/02-background-sheet")

        compose.onNode(hasSetTextAction()).performTextReplacement("#1B2A41")
        compose.waitForIdle()
        capture("flow/03-navy-in-the-preview")

        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Apply to app default").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.defaultTerminalTheme.value.background == 0x1B2A41 }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth Dark copy")).fetchSemanticsNodes().isNotEmpty() }
        capture("flow/04-applied-as-a-copy")

        onStage = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Reconnect")).fetchSemanticsNodes().isNotEmpty() }
        capture("flow/05-stage-in-the-new-theme")
        assertEquals(0x1B2A41, graph.viewModel.themeFor(session.host, session.record.value.workspaceId).background)
    }

    @Test
    fun `stage follows the workspace theme`() {
        runBlocking {
            graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30), terminalThemeId = TerminalTheme.GRUVBOX_DARK_ID))
            graph.sessions.restore()
        }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed { Stage(session) }
        capture("stage-workspace-theme")
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun seed() = runBlocking {
        fun host(id: String, name: String, address: String, user: String, color: SwatchColor) = Host(
            id = id, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user,
            auth = AuthMethod.AskEachTime, lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
        )
        graph.hosts.upsert(host("homelab", "homelab", "192.168.1.20", "ben", SwatchColor.VERDIGRIS))
        graph.hosts.upsert(host("prod-api", "prod-api", "203.0.113.10", "deploy", SwatchColor.COPPER))
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = now - TimeUnit.DAYS.toMillis(20)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.settings.upsertTerminalTheme(TerminalTheme.GRUVBOX_DARK.duplicate("theme-warm", "Gruvbox warm").with(ThemeSlot.Background, 0x1E1A17))
        // Two snippets pinned to the Deck fill its Snippets slot; the third only appears in the catalogue.
        graph.snippets.upsert(Snippet(id = "snip-compose", name = "compose ps", body = "docker compose ps", pinnedToDeck = true))
        graph.snippets.upsert(Snippet(id = "snip-tail", name = "tail caddy", body = "docker compose logs -f --tail {{lines:100}} caddy", pinnedToDeck = true))
        graph.snippets.upsert(Snippet(id = "snip-disk", name = "Disk", body = "df -h /"))

        val homelab = graph.hosts.items.value.first { it.id == "homelab" }
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
                    "NAME        IMAGE               STATUS        PORTS",
                    "caddy       caddy:2             Up 3 days     80/tcp, 443/tcp",
                    "gitea       gitea/gitea:1.22    Up 3 days     3000/tcp",
                    "postgres    postgres:16         Up 3 days     5432/tcp",
                    "ben@homelab:~/srv$ ",
                ),
            ),
        )
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

private const val AccentOffer = "Use this theme's accent for the interface"

/** Chris Kempson's Tomorrow Night as the tinted-theming base16 repository ships it. */
private val TomorrowNightBase16 = """
    scheme: "Tomorrow Night"
    author: "Chris Kempson (http://chriskempson.com)"
    base00: "1d1f21"
    base01: "282a2e"
    base02: "373b41"
    base03: "969896"
    base04: "b4b7b4"
    base05: "c5c8c6"
    base06: "e0e0e0"
    base07: "ffffff"
    base08: "cc6666"
    base09: "de935f"
    base0A: "f0c674"
    base0B: "b5bd68"
    base0C: "8abeb7"
    base0D: "81a2be"
    base0E: "b294bb"
    base0F: "a3685a"
""".trimIndent()

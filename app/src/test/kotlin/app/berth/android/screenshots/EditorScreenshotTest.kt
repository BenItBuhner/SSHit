package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.session.TerminalSession
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.android.ui.themes.TerminalThemeEditorScreen
import app.berth.android.ui.themes.ThemeScope
import app.berth.android.ui.themes.ThemesScreen
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The customisation screens, in Berth Dark on a Pixel-class phone, on in-memory storage: the
 * theme gallery, the terminal theme editor with its colour sheet and apply panel, the interface
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

    /** The Stage as the shell mounts it, with tab actions that reach the manager but no navigation. */
    @Composable
    private fun Stage(session: TerminalSession) {
        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
        StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
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
    fun `terminal theme editor at the 1,3 cap`() {
        RuntimeEnvironment.setFontScale(2f)
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.BERTH_DARK_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = {}) }
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
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.BERTH_DARK_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = {}) }
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
    fun `interface editor`() {
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Interface preview").fetchSemanticsNodes().isNotEmpty() }
        capture("appearance")
    }

    @Test
    fun `settings with the editor rows`() {
        themed { SettingsScreen(graph.viewModel, onBack = {}, onKnownHosts = {}) }
        capture("settings-editors")
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
                TerminalThemeEditorScreen(graph.viewModel, themeId = themeId, scope = ThemeScope.AppDefault, onDone = { onStage = true }, onOpenTheme = { themeId = it })
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

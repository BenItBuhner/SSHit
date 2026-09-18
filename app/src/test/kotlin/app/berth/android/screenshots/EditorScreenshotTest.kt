package app.berth.android.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.settings.SettingsScreen
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.android.ui.themes.TerminalThemeEditorScreen
import app.berth.android.ui.themes.ThemeScope
import app.berth.android.ui.themes.ThemesScreen
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckReach
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalTheme
import app.berth.domain.model.ThemeSlot
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * The customisation screens, in Berth Dark on a Pixel-class phone, on in-memory storage: the
 * theme gallery, the terminal theme editor with its colour sheet, the interface editor, the Deck
 * editor with the action catalogue, the presets sheet and a two-row left-reach layout, plus the
 * Stage picking up a workspace theme. Written to `build/outputs/roborazzi`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class EditorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

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

    private fun capture(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage(File(outDir, "$name.png").path)
    }

    private fun themed(content: @Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
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

    @Test
    fun `terminal theme editor and its colour sheet`() {
        themed { TerminalThemeEditorScreen(graph.viewModel, themeId = TerminalTheme.BERTH_DARK_ID, scope = ThemeScope.AppDefault, onDone = {}, onOpenTheme = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Berth Dark")).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal-theme-editor")

        compose.onNode(hasContentDescription("Blue #", substring = true)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Pick from preview")).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal-theme-colour-sheet")
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

        dismissSheet()

        // Picking Page up for hold is written straight through to settings, which is what the Stage reads.
        compose.onNodeWithText("Hold").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Search keys and actions")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PgUp").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Close sheet").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.layers[0].keys[2].hold == DeckAction.Key(DeckKeyCode.PGUP) }

        compose.onNodeWithText("Presets").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Preset Vim").fetchSemanticsNodes().isNotEmpty() }
        capture("deck-editor-presets")
        dismissSheet()

        compose.onNodeWithText("Two").performScrollTo().performClick()
        compose.onNodeWithText("Left").performScrollTo().performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.rows == 2 && graph.viewModel.deckLayout.value.reach == DeckReach.LEFT }
        compose.onNodeWithContentDescription("Deck preview").performScrollTo()
        capture("deck-editor-two-rows-left")

        // Undo walks back through the changes that were applied live.
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value.rows == 1 && graph.viewModel.deckLayout.value.reach == DeckReach.RIGHT }
        assertEquals(DeckAction.Key(DeckKeyCode.PGUP), graph.viewModel.deckLayout.value.layers[0].keys[2].hold)
    }

    @Test
    fun `stage follows the workspace theme`() {
        runBlocking {
            graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30), terminalThemeId = TerminalTheme.GRUVBOX_DARK_ID))
            graph.sessions.restore()
        }
        val session = graph.sessions.get("s-homelab")!!
        graph.sessions.setActive(session.id)
        themed {
            StageScreen(graph.viewModel, session, onOpenRail = {}, onOpenSessionSheet = {}, onEditHost = {}, onNextSession = {}, onPreviousSession = {})
        }
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

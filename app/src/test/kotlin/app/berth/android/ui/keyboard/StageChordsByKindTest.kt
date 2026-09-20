package app.berth.android.ui.keyboard

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_V
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.TestGraph
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
import app.berth.domain.model.TabKind
import app.berth.domain.model.Tunnel
import app.berth.domain.model.TunnelType
import app.berth.domain.model.Workspace
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
import java.util.concurrent.TimeUnit

/**
 * The Stage's terminal chords are keyed off the tab's kind, not off the tab being a
 * [app.berth.android.session.TerminalSession]: a Tunnels tab (#13) is one, a login with forwards
 * and no shell, so Ctrl+Shift+F, Ctrl+Shift+E and Ctrl+Shift+V must reach the Stage and do nothing
 * at all there, while the same chords on the shell tab beside it open the search, flip the Deck and
 * try the paste. Through the real [StageScreen] over the in-memory graph, with the key events
 * travelling the way a hardware keyboard's do, up from the focused control through the Stage's
 * key preview.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class StageChordsByKindTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()
    /** Handed to the Stage for both tabs, so what a chord did (or did not do) is read here. */
    private val tools = StageTools()

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun `a Tunnels tab on stage takes the terminal chords and does nothing with them, the shell tab beside it acts on them`() {
        seed()
        runBlocking { graph.sessions.restore() }
        val tunnels = graph.sessions.get("s-tunnels")!!
        assertEquals(TabKind.Tunnels, tunnels.kind)
        graph.sessions.setActive(tunnels.id)

        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    // The clipboard the Stage's paste chord reads (Robolectric keeps one per context, so the same one).
                    val clipboard = LocalClipboardManager.current
                    LaunchedEffect(Unit) { clipboard.setText(AnnotatedString("echo hi")) }
                    val tab by graph.viewModel.activeTab.collectAsState()
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
                }
            }
        }
        val row = "127.0.0.1:5433 \u2192\u00A0localhost:5432"
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(row)).fetchSemanticsNodes().isNotEmpty() }
        assertFalse("a Tunnels tab has no Deck", compose.onAllNodesWithTag(DeckKeyTag).fetchSemanticsNodes().isNotEmpty())

        // From a forward's row, the chords climb to the Stage's preview and find no shell to act on.
        compose.onNodeWithText(row).requestFocus()
        chord(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON)
        chord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        chord(KEYCODE_V, META_CTRL_ON or META_SHIFT_ON)
        compose.waitForIdle()
        assertFalse("Ctrl+Shift+F opened the search on a Tunnels tab", tools.search.open)
        assertNull("Ctrl+Shift+V held a paste for preview on a Tunnels tab", tools.pendingPaste)
        // A paste into a detached shell says Not connected; a Tunnels tab, detached too, says nothing, since nothing was tried.
        assertNull("Ctrl+Shift+V raised a notice on a Tunnels tab", tools.notice)

        // The shell tab beside it, detached with its frame: the overflow still offers Hide Deck, so the
        // Ctrl+Shift+E the Tunnels tab took did not flip the Deck it does not have; then the same three chords act.
        graph.sessions.setActive("s-homelab")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Hide Deck", deckRow())
        // Each chord from the terminal itself, the way a keyboard's arrive with the shell focused.
        terminalChord(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON)
        assertTrue("Ctrl+Shift+F did not open the search on a shell tab", tools.search.open)
        tools.closeSearch()
        terminalChord(KEYCODE_V, META_CTRL_ON or META_SHIFT_ON)
        assertEquals(StageTools.NOT_CONNECTED, tools.notice)
        terminalChord(KEYCODE_E, META_CTRL_ON or META_SHIFT_ON)
        assertEquals("Show Deck", deckRow())
    }

    private fun chord(code: Int, meta: Int) {
        compose.onRoot().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)))
    }

    private fun terminalChord(code: Int, meta: Int) {
        compose.onNodeWithTag(TerminalTag).requestFocus()
        compose.waitForIdle()
        chord(code, meta)
        compose.waitForIdle()
    }

    /** The overflow's Deck row, `Hide Deck` or `Show Deck`, read and the menu closed again through its no-op Session row. */
    private fun deckRow(): String {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Session")).fetchSemanticsNodes().isNotEmpty() }
        val row = listOf("Hide Deck", "Show Deck").single { compose.onAllNodes(hasText(it)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Session").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Session")).fetchSemanticsNodes().isEmpty() }
        return row
    }

    /** A detached Tunnels tab on a tunnels-only host with three forwards, beside a detached shell tab with a frame. */
    private fun seed() = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        val prodDb = host("prod-db", "10.0.4.12", "deploy", SwatchColor.VERDIGRIS, tunnelsOnly = true)
        val homelab = host("homelab", "192.168.1.20", "ben", SwatchColor.MOSS)
        graph.hosts.upsert(prodDb)
        graph.hosts.upsert(homelab)
        graph.tunnels.upsert(Tunnel("t-pg", prodDb.id, TunnelType.LOCAL, "127.0.0.1", 5433, "localhost", 5432))
        graph.tunnels.upsert(Tunnel("t-web", prodDb.id, TunnelType.LOCAL, "127.0.0.1", 8080, "localhost", 80))
        graph.tunnels.upsert(Tunnel("t-socks", prodDb.id, TunnelType.DYNAMIC, "127.0.0.1", 1080, "", 0, enabled = false))
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "s-homelab", workspaceId = Workspace.DEFAULT_ID, hostId = homelab.id, hostSnapshot = homelab, state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME, title = homelab.name, cwd = "~/srv", lastCommand = "docker compose ps", sortOrder = 0,
                createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
            ),
        )
        graph.sessionRecords.upsert(
            SessionRecord(
                id = "s-tunnels", workspaceId = Workspace.DEFAULT_ID, hostId = prodDb.id, hostSnapshot = prodDb, state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME, title = "Tunnels \u00B7 ${prodDb.name}", sortOrder = 1,
                createdAt = now - TimeUnit.HOURS.toMillis(2), lastLiveAt = now - TimeUnit.MINUTES.toMillis(7), kind = TabKind.Tunnels,
            ),
        )
        graph.sessionRecords.saveFrame("s-homelab", frame(listOf("ben@homelab:~/srv$ docker compose ps", "ben@homelab:~/srv$ ")))
    }

    private fun host(name: String, address: String, user: String, color: SwatchColor, tunnelsOnly: Boolean = false) = Host(
        id = name, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = 22, user = user, auth = AuthMethod.AskEachTime,
        tunnelsOnly = tunnelsOnly, lastConnectedAt = now - TimeUnit.MINUTES.toMillis(40), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

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

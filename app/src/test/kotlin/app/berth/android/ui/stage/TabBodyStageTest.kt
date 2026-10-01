package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
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
 * A tab's body neither stages its tab nor marks it seen (spec C2 and C3: a bell on stage is
 * haptic only, off stage it lights the ring, and the ring clears when the tab becomes active).
 * The manager does both under its lock as the stage moves. A body's first frame can land after
 * the stage has moved on, two moves within one frame being the way it happens, and the Stage then
 * composes the tab it was handed a frame before. Here that is done on purpose: the real
 * [StageScreen] over the in-memory graph, composed for a tab the manager has moved off, and the
 * race itself, a second move made within the frame that composed the first move's tab. That tab
 * must stay off stage, keep a ring it already had, and light on a bell.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TabBodyStageTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    @Test
    fun `a shell tab's body composed after the stage left it leaves it off stage, so a bell lights it`() {
        val left = composeAfterTheStageLeft("s-b")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertFalse("the body put the tab the stage left back on stage", left.onStage)
        assertOnlyTheActiveTabOnStage()
        left.emulator.write("\u0007")
        assertTrue("a bell into the tab off stage lit it", left.record.value.needsAttention)
    }

    @Test
    fun `a shell tab's body composed after the stage left it keeps the ring the tab already had`() {
        val left = composeAfterTheStageLeft("s-b", lit = true)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertTrue("the body cleared a ring the user has not seen", left.record.value.needsAttention)
        assertEquals("Bell", left.record.value.attentionReason)
        assertOnlyTheActiveTabOnStage()
    }

    @Test
    fun `a Tunnels tab's body composed after the stage left it leaves it off stage with its ring`() {
        val left = composeAfterTheStageLeft("s-tunnels", lit = true)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("127.0.0.1:5433 \u2192\u00A0localhost:5432")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertFalse("the body put the tab the stage left back on stage", left.onStage)
        assertTrue("the body cleared a ring the user has not seen", left.record.value.needsAttention)
        assertOnlyTheActiveTabOnStage()
    }

    @Test
    fun `a second move of the stage within the frame that composed the first one's tab ends with that tab off stage, and a bell lights it`() {
        seed()
        runBlocking { graph.sessions.restore() }
        graph.process.start()
        graph.sessions.setActive("s-a")
        var movedBack = false
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val tab by graph.viewModel.activeTab.collectAsState()
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                    // The second move, once s-b's body has been composed and before that body's effects run.
                    SideEffect {
                        if (tab?.id == "s-b" && !movedBack) {
                            movedBack = true
                            graph.sessions.setActive("s-a")
                        }
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(TerminalTag).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val left = graph.sessions.get("s-b")!!
        graph.sessions.setActive("s-b")
        compose.waitUntil(5_000) { movedBack }
        compose.waitForIdle()
        assertOnlyTheActiveTabOnStage()
        left.emulator.write("\u0007")
        assertTrue("a bell into the tab the stage left lit it", left.record.value.needsAttention)
    }

    /**
     * The app on screen with s-a staged; [id], off stage, lit by a bell first when [lit]; then the
     * Stage composed for [id], the tab a frame-old strip would hand it.
     */
    private fun composeAfterTheStageLeft(id: String, lit: Boolean = false): TerminalSession {
        seed()
        runBlocking { graph.sessions.restore() }
        graph.process.start()
        graph.sessions.setActive("s-a")
        val tab = graph.sessions.get(id)!!
        assertFalse("$id starts off stage", tab.onStage)
        if (lit) {
            tab.emulator.write("\u0007")
            assertTrue("$id lit by its bell", tab.record.value.needsAttention)
        }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, tab, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        return tab
    }

    private fun assertOnlyTheActiveTabOnStage() {
        assertEquals("s-a", graph.sessions.activeTabId.value)
        assertEquals(listOf("s-a"), graph.sessions.sessions.value.filter { it.onStage }.map { it.id })
    }

    /** Two detached shell tabs with frames and a detached Tunnels tab on a tunnels-only host with one forward. */
    private fun seed() = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.settings.setLastActiveSessionId("s-a")
        val homelab = host("homelab", "192.168.1.20", "ben", SwatchColor.MOSS)
        val pihole = host("pi-hole", "192.168.1.2", "pi", SwatchColor.SLATE)
        val prodDb = host("prod-db", "10.0.4.12", "deploy", SwatchColor.VERDIGRIS, tunnelsOnly = true)
        listOf(homelab, pihole, prodDb).forEach { graph.hosts.upsert(it) }
        graph.tunnels.upsert(Tunnel("t-pg", prodDb.id, TunnelType.LOCAL, "127.0.0.1", 5433, "localhost", 5432))
        graph.sessionRecords.upsert(record("s-a", homelab, 0, lastCommand = "docker compose ps"))
        graph.sessionRecords.upsert(record("s-b", pihole, 1, lastCommand = "tail -f pihole.log"))
        graph.sessionRecords.upsert(record("s-tunnels", prodDb, 2, kind = TabKind.Tunnels))
        graph.sessionRecords.saveFrame("s-a", frame(listOf("ben@homelab:~/srv$ docker compose ps", "ben@homelab:~/srv$ ")))
        graph.sessionRecords.saveFrame("s-b", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app")))
    }

    private fun record(id: String, host: Host, order: Int, lastCommand: String? = null, kind: TabKind = TabKind.Ssh) = SessionRecord(
        id = id, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED,
        layer = PersistenceLayer.LOCAL_FRAME, title = if (kind == TabKind.Tunnels) "Tunnels \u00B7 ${host.name}" else host.name,
        lastCommand = lastCommand, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(2), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12), kind = kind,
    )

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

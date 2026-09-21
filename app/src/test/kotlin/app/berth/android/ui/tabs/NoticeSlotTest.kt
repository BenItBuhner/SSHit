package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.AuthResolver
import app.berth.android.ui.AppRoot
import app.berth.android.ui.AppViewModel
import app.berth.android.ui.HeldPaths
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * The shell's one notice slot (review #18 nit 17): Reopen, a link's line, Back's line and Landed
 * used to be four bars aligned to the same bottom centre, so a tab closing beside a terminal
 * holding a dropped path drew Reopen over Landed. In the slot the one raised last shows and the one
 * under it is back when it goes, whatever it says by then; the bar leaves with its last line on it.
 * And the shell holds Landed down while Reopen is up (nit 21), since in that very case Landed is
 * the later of the two and would sit over the six-second offer for as long as the paths are held.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class NoticeSlotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `of the notices up the one raised last shows, the one under it is back when it goes, and the line stays through the bar's exit`() {
        val reopen = mutableStateOf<Notice?>(null)
        val landed = mutableStateOf<Notice?>(null)
        val taps = ArrayList<String>()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    NoticeSlot(
                        notices = mapOf("reopen" to reopen.value, "landed" to landed.value),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Paste path").assertCountEquals(0)

        landed.value = Notice("Landed in /tmp", "Paste path") { taps += "paste" }
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()

        // A tab closes beside it: Reopen comes up over Landed, and the bar's action is Reopen's.
        reopen.value = Notice("Closed prod-web", "Reopen") { taps += "reopen" }
        compose.waitForIdle()
        compose.onNodeWithText("Closed prod-web").assertIsDisplayed()
        compose.onAllNodesWithText("Landed in /tmp").assertCountEquals(0)
        compose.onNodeWithText("Reopen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("reopen"), taps)

        // A second path lands while Reopen shows: Landed's line changes and keeps its place under it.
        landed.value = Notice("2 files landed in /tmp", "Paste paths") { taps += "paste" }
        compose.waitForIdle()
        compose.onNodeWithText("Closed prod-web").assertIsDisplayed()
        compose.onAllNodesWithText("2 files landed in /tmp").assertCountEquals(0)

        // Reopen's six seconds are done: Landed is back, saying what it says now, with its own action.
        reopen.value = null
        compose.waitForIdle()
        compose.onNodeWithText("2 files landed in /tmp").assertIsDisplayed()
        compose.onAllNodesWithText("Closed prod-web").assertCountEquals(0)
        compose.onNodeWithText("Paste paths").performClick()
        compose.waitForIdle()
        assertEquals(listOf("reopen", "paste"), taps)

        // The paths pasted, Landed goes: the bar leaves with its line still on it, then is gone.
        compose.mainClock.autoAdvance = false
        landed.value = null
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("2 files landed in /tmp").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodesWithText("2 files landed in /tmp").assertCountEquals(0)
        compose.onAllNodesWithText("Paste paths").assertCountEquals(0)
    }

    @Test
    fun `two notices raised in one frame show the later of the slot's order, and one going down while another stays changes nothing`() {
        val back = mutableStateOf<Notice?>(null)
        val landed = mutableStateOf<Notice?>(null)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    NoticeSlot(notices = mapOf("back" to back.value, "landed" to landed.value), modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        back.value = Notice("Sessions keep running. Detach all from the notification.", "OK", maxLines = 2) {}
        landed.value = Notice("Landed in /tmp", "Paste path") {}
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()
        // Back's six seconds end under Landed: Landed stays, nothing flickers to Back's line.
        back.value = null
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()
        compose.onAllNodesWithText("Sessions keep running. Detach all from the notification.").assertCountEquals(0)
    }

    /**
     * The slot's own example, wired as the shell wires it (review #18 nit 21): the tab on stage,
     * connecting, closes, and its neighbour, which the stage moves to, holds a dropped path. The
     * close raises Reopen (a tab that was connecting or live earns it, spec C3); the neighbour's
     * Landed rises with it, in the same frame or a dispatch after, and as the later of the two would
     * show over Reopen for as long as the path is held, so the six-second offer would never be
     * seen. The shell holds Landed down while Reopen is up: Reopen shows, and Landed is there, with
     * its Paste, once Reopen's six seconds are done. The host that closes is on a server that
     * accepts and never speaks, so the tab is Connecting for as long as the test needs.
     */
    @Test
    fun `closing the tab on stage beside a terminal holding a path shows Reopen for its six seconds, and Landed after`() {
        val silent = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val graph = TestGraph(ApplicationProvider.getApplicationContext())
        try {
            val now = System.currentTimeMillis()
            val homelab = host("homelab", "127.0.0.1", silent.localPort, SwatchColor.VERDIGRIS, now)
            val pihole = host("pi-hole", "192.168.1.2", 22, SwatchColor.MOSS, now)
            runBlocking {
                graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = now - TimeUnit.DAYS.toMillis(30)))
                graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
                graph.secrets.put(AuthResolver.passwordSecretId("homelab"), "not-reached".toByteArray())
                graph.hosts.upsert(homelab)
                graph.hosts.upsert(pihole)
                graph.sessionRecords.upsert(record("s-homelab", homelab, 0, now))
                graph.sessionRecords.upsert(record("s-pihole", pihole, 1, now))
                graph.settings.setLastActiveSessionId("s-homelab")
                graph.sessions.restore()
                graph.sessions.activeTab.first { it?.id == "s-homelab" }
            }
            compose.setContent { AppRoot(graph.viewModel) }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Tabs, 2 open")).fetchSemanticsNodes().isNotEmpty() }
            val session = graph.sessions.get("s-homelab")!!
            session.connect()
            compose.waitUntil(10_000) { session.state == SessionState.CONNECTING }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("homelab, connecting", substring = true)).fetchSemanticsNodes().isNotEmpty() }

            // A path lands for pi-hole while homelab is on stage: held for pi-hole's notice, and no bar yet, homelab being the tab on stage.
            val path = "'/tmp/berth-a1b2c3/report.txt'"
            graph.viewModel.landDroppedPath(graph.sessions.get("s-pihole")!!, path)
            assertEquals(HeldPaths(path, 1), graph.viewModel.heldPaths.value["s-pihole"])
            compose.waitForIdle()
            compose.onAllNodesWithText(AppViewModel.LANDED_IN_TMP).assertCountEquals(0)

            // homelab closes: the stage moves to pi-hole. Reopen shows, alone; Landed waits under it.
            compose.onNode(hasContentDescription("homelab, connecting", substring = true)).performCustomAccessibilityActionWithLabel("Close")
            compose.waitUntil(5_000) { graph.viewModel.activeTabId.value == "s-pihole" }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Closed homelab").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reopen").assertIsDisplayed()
            compose.onAllNodesWithText(AppViewModel.LANDED_IN_TMP).assertCountEquals(0)
            compose.onAllNodesWithText(AppViewModel.PASTE_PATH).assertCountEquals(0)
            assertEquals("the path is still held for pi-hole", HeldPaths(path, 1), graph.viewModel.heldPaths.value["s-pihole"])

            // Reopen's six seconds are done: Landed is up, with its Paste, and Reopen gone.
            compose.mainClock.advanceTimeBy(NOTICE_BAR_MS + 100)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(AppViewModel.LANDED_IN_TMP).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(AppViewModel.PASTE_PATH).assertExists()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Closed homelab").fetchSemanticsNodes().isEmpty() }
        } finally {
            graph.close()
            silent.close()
        }
    }

    private fun host(name: String, address: String, port: Int, color: SwatchColor, now: Long) = Host(
        id = name, name = name, color = color, monogram = Host.monogramFor(name), address = address, port = port, user = "ben",
        auth = AuthMethod.Password(AuthResolver.passwordSecretId(name)),
        lastConnectedAt = now - TimeUnit.MINUTES.toMillis(18), createdAt = now - TimeUnit.DAYS.toMillis(30),
    )

    private fun record(id: String, host: Host, order: Int, now: Long) = SessionRecord(
        id = id, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, layer = PersistenceLayer.LOCAL_FRAME,
        title = host.name, cwd = "~", lastCommand = null, sortOrder = order, createdAt = now - TimeUnit.HOURS.toMillis(5), lastLiveAt = now - TimeUnit.MINUTES.toMillis(12),
    )
}

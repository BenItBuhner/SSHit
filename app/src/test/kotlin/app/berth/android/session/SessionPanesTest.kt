package app.berth.android.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The split Stage (spec C23): two tabs side by side, the active one taking the keys, the companion
 * on stage with it while the panes show. The strip's tap, Ctrl+Tab and the notification tap all go
 * through [SessionManager.setActive], so the rules for staging a tab while the Stage is split live
 * in the manager: the companion's tab trades roles, any other tab replaces the focused pane, and a
 * pane's tab closing closes its pane and never the other one. Nothing here is persisted; a split
 * survives rotation with the manager and starts over with the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionPanesTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        seed()
        runBlocking { graph.sessions.restore() }
        graph.process.start()
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
    }

    @Test
    fun `a tab dropped on a pane takes that side and the keys, and the active tab becomes the companion`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "s-pihole", focused = PaneSide.RIGHT)
        assertEquals("the current group follows the keys", Workspace.DEFAULT_ID, graph.sessions.currentWorkspaceId.value)
    }

    @Test
    fun `open beside takes the pane opposite the active tab, and across groups the group follows`() {
        graph.sessions.openBeside("s-build")
        assertEquals("s-build", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        assertEquals("ws-work", graph.sessions.currentWorkspaceId.value)
        graph.sessions.setActive("s-homelab")
        assertEquals(Workspace.DEFAULT_ID, graph.sessions.currentWorkspaceId.value)
        // Beside the (now) left tab is the right pane again, whoever holds it.
        graph.sessions.openBeside("s-pihole")
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "s-pihole", focused = PaneSide.RIGHT)
    }

    @Test
    fun `staging the companion trades roles and both tabs keep their panes`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.setActive("s-homelab")
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        assertEquals(Split("s-pihole", PaneSide.LEFT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "s-pihole", focused = PaneSide.LEFT)
        // Ctrl+Tab from homelab in strip order is pi-hole, the companion: the keys cross over, nothing moves.
        graph.sessions.stepActive(1)
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "s-pihole", focused = PaneSide.RIGHT)
    }

    @Test
    fun `staging a tab in neither pane replaces the focused pane and leaves the other alone`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.setActive("s-build")
        assertEquals("s-build", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "s-build", focused = PaneSide.RIGHT)
        // A new tab opens in the focused pane the same way.
        val fresh = runBlocking { graph.sessions.duplicate("s-build")!! }
        assertEquals(fresh.id, graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = fresh.id, focused = PaneSide.RIGHT)
    }

    @Test
    fun `the active tab dropped on the other pane trades places with the companion and keeps the keys`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.placeInPane("s-pihole", PaneSide.LEFT)
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.LEFT), graph.sessions.split.value)
        awaitPanes(left = "s-pihole", right = "s-homelab", focused = PaneSide.LEFT)
        // Dropped on its own pane, nothing changes.
        graph.sessions.placeInPane("s-pihole", PaneSide.LEFT)
        assertEquals(Split("s-homelab", PaneSide.LEFT), graph.sessions.split.value)
    }

    @Test
    fun `a third tab dropped on the companion's pane replaces it while the active tab keeps its side`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.placeInPane("s-build", PaneSide.LEFT)
        assertEquals("s-build", graph.sessions.activeTabId.value)
        assertEquals(Split("s-pihole", PaneSide.LEFT), graph.sessions.split.value)
        awaitPanes(left = "s-build", right = "s-pihole", focused = PaneSide.LEFT)
    }

    @Test
    fun `a third tab dropped on the focused pane replaces it while the companion stays`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.placeInPane("f-build", PaneSide.RIGHT)
        assertEquals("f-build", graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = "f-build", focused = PaneSide.RIGHT)
    }

    @Test
    fun `the companion is on stage only while the panes show, and its news counts as seen when they do`() {
        val homelab = graph.sessions.get("s-homelab")!!
        val pihole = graph.sessions.get("s-pihole")!!
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        assertTrue(pihole.onStage)
        assertFalse("off stage until the Stage lays the panes out", homelab.onStage)
        homelab.emulator.write("\u0007")
        await("the companion's bell lit it while hidden") { homelab.record.value.needsAttention }

        graph.sessions.setPanesShown(true)
        assertTrue(homelab.onStage)
        assertTrue(pihole.onStage)
        assertFalse("in view now, so seen", homelab.record.value.needsAttention)
        homelab.emulator.write("\u0007")
        assertFalse("a bell in view never lights", homelab.record.value.needsAttention)
        assertFalse(graph.sessions.get("s-build")!!.onStage)

        // Folded to a compact width: the split waits, the companion is any other tab again.
        graph.sessions.setPanesShown(false)
        assertFalse(homelab.onStage)
        assertTrue(pihole.onStage)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)

        // Away from the screen nothing is on stage; back, both are.
        graph.sessions.setPanesShown(true)
        graph.process.stop()
        assertFalse(homelab.onStage)
        assertFalse(pihole.onStage)
        graph.process.start()
        assertTrue(homelab.onStage)
        assertTrue(pihole.onStage)
    }

    @Test
    fun `closing the focused pane's tab hands the keys to the companion and the Stage is one pane again`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.setPanesShown(true)
        assertNotNull(graph.sessions.close("s-pihole"))
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        assertTrue(graph.sessions.get("s-homelab")!!.onStage)
        awaitPanes(null)
    }

    @Test
    fun `closing the companion's tab leaves the active tab alone with the Stage`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        assertNotNull(graph.sessions.close("s-homelab"))
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        awaitPanes(null)
    }

    @Test
    fun `closing a pane keeps its tab in the strip, and the other pane's tab has the keys`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.setPanesShown(true)
        graph.sessions.closePane(PaneSide.RIGHT)
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        assertNotNull("the tab is still open", graph.sessions.get("s-pihole"))
        assertFalse(graph.sessions.get("s-pihole")!!.onStage)
        assertTrue(graph.sessions.get("s-homelab")!!.onStage)

        // Reopened: nothing was lost on either side.
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        awaitPanes(left = "s-homelab", right = "s-pihole", focused = PaneSide.RIGHT)
        graph.sessions.closePane(PaneSide.LEFT)
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        assertNotNull(graph.sessions.get("s-homelab"))
        assertFalse(graph.sessions.get("s-homelab")!!.onStage)
    }

    @Test
    fun `unsplit drops the companion's pane and staging nothing drops the split`() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        graph.sessions.unsplit()
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        graph.sessions.unsplit()
        assertEquals("s-pihole", graph.sessions.activeTabId.value)

        graph.sessions.placeInPane("s-homelab", PaneSide.LEFT)
        assertEquals(Split("s-pihole", PaneSide.LEFT), graph.sessions.split.value)
        graph.sessions.setActive(null)
        assertNull(graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
    }

    @Test
    fun `split opens a second tab on the active host in the other pane and hands it the keys`() {
        val fresh = runBlocking { graph.sessions.splitActive()!! }
        assertEquals("homelab", fresh.host.id)
        assertEquals(fresh.id, graph.sessions.activeTabId.value)
        assertEquals(Split("s-homelab", PaneSide.RIGHT), graph.sessions.split.value)
        awaitPanes(left = "s-homelab", right = fresh.id, focused = PaneSide.RIGHT)
        await("directly after its source in the strip") { graph.sessions.tabs.value.map { it.id }.take(2) == listOf("s-homelab", fresh.id) }

        // Split again from the right pane: the new tab takes the left, the previous companion leaves it.
        val second = runBlocking { graph.sessions.splitActive()!! }
        assertEquals(second.id, graph.sessions.activeTabId.value)
        assertEquals(Split(fresh.id, PaneSide.LEFT), graph.sessions.split.value)
        awaitPanes(left = second.id, right = fresh.id, focused = PaneSide.LEFT)
        assertNotNull("the tab that left its pane is still open", graph.sessions.get("s-homelab"))
    }

    @Test
    fun `an id no tab answers to is ignored`() {
        graph.sessions.placeInPane("s-closed-a-frame-ago", PaneSide.RIGHT)
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        assertNull(graph.sessions.split.value)
        graph.sessions.closePane(PaneSide.LEFT)
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
    }

    // ---- fixture ----------------------------------------------------------------------------------

    /** Home: homelab (active), pi-hole. Work: a Files tab for build box, then its terminal. */
    private fun seed() = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = 0L))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = 0L))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.settings.setLastActiveSessionId("s-homelab")
        val homelab = host("homelab", "homelab", SwatchColor.VERDIGRIS)
        val pihole = host("pi-hole", "pi-hole", SwatchColor.MOSS)
        val build = host("build-box", "build box", SwatchColor.SLATE)
        listOf(homelab, pihole, build).forEach { graph.hosts.upsert(it) }
        graph.sessionRecords.upsert(record("s-homelab", homelab, Workspace.DEFAULT_ID, 0))
        graph.sessionRecords.upsert(record("s-pihole", pihole, Workspace.DEFAULT_ID, 1))
        graph.sessionRecords.upsert(record("f-build", build, "ws-work", 0, kind = TabKind.Files, title = "Files \u00B7 berth"))
        graph.sessionRecords.upsert(record("s-build", build, "ws-work", 1))
    }

    private fun host(id: String, name: String, color: SwatchColor) = Host(
        id = id,
        name = name,
        color = color,
        monogram = Host.monogramFor(name),
        address = "$id.internal",
        port = 22,
        user = "ben",
        auth = AuthMethod.AskEachTime,
        createdAt = 0L,
    )

    private fun record(id: String, host: Host, workspaceId: String, order: Int, kind: TabKind = TabKind.Ssh, title: String = host.name) = SessionRecord(
        id = id,
        workspaceId = workspaceId,
        hostId = host.id,
        hostSnapshot = host,
        state = SessionState.DETACHED,
        title = title,
        sortOrder = order,
        createdAt = 0L,
        kind = kind,
    )

    /** [SessionManager.panes] is derived on the manager's scope, a step behind the split. */
    private fun awaitPanes(left: String?, right: String? = null, focused: PaneSide? = null) {
        await("panes $left | $right, keys on $focused") {
            val panes = graph.sessions.panes.value
            if (left == null) panes == null else panes != null && panes.left.id == left && panes.right.id == right && panes.focused == focused
        }
    }

    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(20)
        }
        assertTrue("timed out waiting for $what", condition())
    }
}

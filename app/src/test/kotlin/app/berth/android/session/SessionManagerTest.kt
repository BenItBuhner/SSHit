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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launch puts a tab on stage whenever the strip has one (spec C3, Launch: "if any group holds a
 * tab, open the last active tab on its Stage"; Persistence: "restore is instant"). The persisted
 * active id wins while it names an open tab; a missing or stale id, which every phone has once
 * (the first launch after the tabs migration, a tab closed from the notification while the app was
 * dead, a settings write that never landed), falls back to the current group's first tab, then the
 * strip's first, and the fallback is written back so the next launch agrees. The manager is built
 * the way Hilt builds it, over in-memory storage, with detached tabs of both kinds across two groups
 * and a third group that is empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionManagerTest {
    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `restore stages the persisted active tab and the current group follows it`() {
        seed(currentGroup = Workspace.DEFAULT_ID, lastActive = "s-build")
        restore()
        assertEquals("s-build", graph.sessions.activeTabId.value)
        assertEquals("ws-work", graph.sessions.currentWorkspaceId.value)
        await("strip order") { graph.sessions.tabs.value.map { it.id } == listOf("s-homelab", "s-pihole", "f-build", "s-build") }
        assertEquals("s-build", runBlocking { graph.settings.lastActiveSessionId.first() })
    }

    @Test
    fun `restore without an active id stages the current group's first tab and persists the choice`() {
        seed(currentGroup = Workspace.DEFAULT_ID, lastActive = null)
        restore()
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        assertEquals(Workspace.DEFAULT_ID, graph.sessions.currentWorkspaceId.value)
        awaitLastActive("s-homelab")
    }

    @Test
    fun `restore with a stale active id stages the current group's first tab whatever its kind`() {
        seed(currentGroup = "ws-work", lastActive = "s-closed-while-the-app-was-dead")
        restore()
        // Work's first tab is the Files tab; the fallback does not care what a tab runs.
        assertEquals("f-build", graph.sessions.activeTabId.value)
        assertEquals("ws-work", graph.sessions.currentWorkspaceId.value)
        awaitLastActive("f-build")
    }

    @Test
    fun `restore with an empty current group stages the strip's first tab and follows it`() {
        seed(currentGroup = "ws-lab", lastActive = null)
        restore()
        assertEquals("s-homelab", graph.sessions.activeTabId.value)
        // The current group is the active tab's group, so the empty group stops being current.
        assertEquals(Workspace.DEFAULT_ID, graph.sessions.currentWorkspaceId.value)
        awaitLastActive("s-homelab")
    }

    @Test
    fun `restore with no tabs stages nothing and still reports restored`() {
        seed(currentGroup = "ws-lab", lastActive = "s-anything", tabs = false)
        restore()
        assertNull(graph.sessions.activeTabId.value)
        assertEquals("ws-lab", graph.sessions.currentWorkspaceId.value)
        assertTrue(graph.sessions.restored.value)
        assertTrue(graph.sessions.tabs.value.isEmpty())
    }

    @Test
    fun `setActive ignores an id no tab answers to`() {
        seed(currentGroup = Workspace.DEFAULT_ID, lastActive = "s-pihole")
        restore()
        graph.sessions.setActive("s-closed-a-frame-ago")
        assertEquals("s-pihole", graph.sessions.activeTabId.value)
        assertEquals(Workspace.DEFAULT_ID, graph.sessions.currentWorkspaceId.value)
        graph.sessions.setActive("s-build")
        assertEquals("s-build", graph.sessions.activeTabId.value)
        assertEquals("ws-work", graph.sessions.currentWorkspaceId.value)
        graph.sessions.setActive(null)
        assertNull(graph.sessions.activeTabId.value)
    }

    // ---- fixture ----------------------------------------------------------------------------------

    /** Home: homelab, pi-hole. Work: a Files tab for build box, then its terminal. Homelab: nothing. */
    private fun seed(currentGroup: String, lastActive: String?, tabs: Boolean = true) = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = 0L))
        graph.workspaces.upsert(Workspace("ws-work", "Work", SwatchColor.SLATE, "W", sortOrder = 1, createdAt = 0L))
        graph.workspaces.upsert(Workspace("ws-lab", "Homelab", SwatchColor.MOSS, "HL", sortOrder = 2, createdAt = 0L))
        graph.settings.setCurrentWorkspaceId(currentGroup)
        graph.settings.setLastActiveSessionId(lastActive)
        if (!tabs) return@runBlocking
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

    /** The manager restores itself on construction; this returns once that has happened, whichever call did the work. */
    private fun restore() = runBlocking {
        graph.sessions.restore()
        assertTrue("restore reports itself done", graph.sessions.restored.value)
    }

    /** The fallback is written back on the manager's scope; give it a moment to land. */
    private fun awaitLastActive(expected: String?) {
        await("last active id written as $expected") { runBlocking { graph.settings.lastActiveSessionId.first() } == expected }
    }

    /** The derived flows run on the manager's scope a step behind its maps. */
    private fun await(what: String, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(20)
        }
        assertTrue("timed out waiting for $what", condition())
    }
}

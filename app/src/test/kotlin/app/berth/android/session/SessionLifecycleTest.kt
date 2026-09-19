package app.berth.android.session

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The manager against the process (vision §4.3 L0, spec C3 Persistence, C21): frames go to disk
 * when the app leaves the screen and when the OS trims memory, a tab that held a socket when the
 * process died comes back as a paused frame, attention reaches the shade only while the app is
 * away, a notification's tab is staged whenever it is asked for, and jump-to-unread goes to the
 * tab that needed the user most recently. The process lifecycle is the fake owner in [TestGraph].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionLifecycleTest {
    private lateinit var graph: TestGraph
    private val notifications by lazy { shadowOf(ApplicationProvider.getApplicationContext<Application>().getSystemService(NotificationManager::class.java)) }

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    // ---- frames ----------------------------------------------------------------------------------

    @Test
    fun `leaving the screen saves every open terminal's frame`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        graph.process.start()
        assertTrue(graph.sessionRecords.frames.isEmpty())
        graph.process.stop()
        await("both frames saved on stop") { graph.sessionRecords.frames.keys == setOf("s-a", "s-b") }
        assertTrue(lines(graph.sessionRecords.frames.getValue("s-a")).any { it.contains("docker compose ps") })
        assertTrue(lines(graph.sessionRecords.frames.getValue("s-b")).any { it.contains("tail -f pihole.log") })
    }

    @Test
    fun `a memory trim saves frames whatever the level`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        graph.sessions.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        await("frames saved on trim") { graph.sessionRecords.frames.keys == setOf("s-a", "s-b") }
    }

    @Test
    fun `the application's trim callback reaches the manager`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        ApplicationProvider.getApplicationContext<Application>().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        await("frames saved through the registered callback") { graph.sessionRecords.frames.keys == setOf("s-a", "s-b") }
    }

    @Test
    fun `foreground follows the process`() {
        seed()
        restore()
        assertFalse(graph.sessions.foreground.value)
        graph.process.start()
        assertTrue(graph.sessions.foreground.value)
        graph.process.stop()
        assertFalse(graph.sessions.foreground.value)
    }

    @Test
    fun `a tab that was live when the process died comes back detached, on its frame, marked paused`() {
        val killedAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(4)
        seed(aState = SessionState.LIVE, aLayer = PersistenceLayer.IN_APP, aLastLiveAt = killedAt)
        restore()
        val session = graph.sessions.get("s-a")!!
        assertEquals(SessionState.DETACHED, session.state)
        assertEquals(PersistenceLayer.LOCAL_FRAME, session.record.value.layer)
        assertEquals("the pill counts from the last save, not from the connect", killedAt, session.record.value.lastLiveAt)
        val screen = session.emulator.screenText()
        assertTrue(screen.any { it.contains("docker compose ps") })
        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(killedAt))
        assertTrue("paused marker in ${screen.filter { it.isNotBlank() }}", screen.any { it.contains("paused $stamp") })
        // The record on disk agrees, so the next launch reads the same.
        assertEquals(SessionState.DETACHED, graph.sessionRecords.items.value.first { it.id == "s-a" }.state)
    }

    @Test
    fun `a reconnecting tab at death is a paused frame too, a detached one is not`() {
        seed(aState = SessionState.RECONNECTING, aLayer = PersistenceLayer.IN_APP, aLastLiveAt = System.currentTimeMillis())
        restore()
        assertTrue(graph.sessions.get("s-a")!!.emulator.screenText().any { it.contains("paused") })
        assertFalse(graph.sessions.get("s-b")!!.emulator.screenText().any { it.contains("paused") })
    }

    @Test
    fun `detach all from the shade keeps every tab's frame`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        assertTrue(graph.notifier.dispatch(Intent(SessionNotifier.ACTION_DETACH_ALL), graph.sessions))
        await("frames saved by Detach all") { graph.sessionRecords.frames.keys == setOf("s-a", "s-b") }
        assertTrue(graph.sessions.sessions.value.all { it.state == SessionState.DETACHED })
    }

    // ---- attention -------------------------------------------------------------------------------

    @Test
    fun `jump to unread stages the tab that needed the user most recently, then the next, then nothing`() {
        seed(third = true)
        restore()
        graph.sessions.setActive("s-a")
        await("s-a on stage") { graph.sessions.get("s-a")!!.onStage }
        assertFalse(graph.sessions.jumpToUnread())
        bell("s-b")
        Thread.sleep(5)
        bell("s-c")
        await("both lit") { graph.sessions.records.value.count { it.needsAttention } == 2 }
        assertTrue(graph.sessions.jumpToUnread())
        assertEquals("s-c", graph.sessions.activeTabId.value)
        assertFalse("staging clears it", graph.sessions.get("s-c")!!.record.value.needsAttention)
        assertTrue(graph.sessions.jumpToUnread())
        assertEquals("s-b", graph.sessions.activeTabId.value)
        assertFalse(graph.sessions.jumpToUnread())
        assertEquals("s-b", graph.sessions.activeTabId.value)
    }

    @Test
    fun `the tab on stage never counts as unread`() {
        seed()
        restore()
        graph.sessions.setActive("s-a")
        await("s-a on stage") { graph.sessions.get("s-a")!!.onStage }
        graph.sessions.get("s-a")!!.emulator.write("\u0007")
        assertFalse(graph.sessions.get("s-a")!!.record.value.needsAttention)
        assertFalse(graph.sessions.jumpToUnread())
    }

    @Test
    fun `attention while the app is away goes to the shade and leaves it when the tab is seen`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        graph.process.stop()
        bell("s-b")
        await("attention notification for s-b") { notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2) != null }
        val posted = notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2)
        assertEquals("s-b", shadowOf(posted.contentIntent).savedIntent.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals("pi-hole needs you", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("bell in tail -f pihole.log", posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        graph.sessions.setActive("s-b")
        await("cancelled once seen") { notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2) == null }
    }

    @Test
    fun `attention while the app is on screen stays on the strip`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        bell("s-b")
        await("s-b lit") { graph.sessions.get("s-b")!!.record.value.needsAttention }
        Thread.sleep(100)
        assertNull(notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2))
    }

    @Test
    fun `a closed tab takes its notification with it`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        graph.process.stop()
        bell("s-b")
        await("posted") { notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2) != null }
        graph.sessions.close("s-b")
        assertNull(notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2))
    }

    // ---- deep links ------------------------------------------------------------------------------

    @Test
    fun `a notification's tab is staged and the shell is asked for the Stage`() {
        seed()
        restore()
        graph.sessions.setActive("s-a")
        val staged = ArrayList<String>()
        val collector = CoroutineScope(Dispatchers.Default)
        collector.launch { graph.sessions.stageRequests.collect { staged += it } }
        Thread.sleep(50)
        graph.sessions.activateFromNotification("s-b")
        assertEquals("s-b", graph.sessions.activeTabId.value)
        await("stage request") { staged == listOf("s-b") }
        graph.sessions.activateFromNotification("s-gone")
        assertEquals("an unknown tab stages nothing", "s-b", graph.sessions.activeTabId.value)
        collector.cancel()
    }

    @Test
    fun `a tap that starts the process is honoured once the strip is restored`() {
        seed()
        // The manager restores itself on its own scope; asking before that has landed parks the request.
        graph.sessions.activateFromNotification("s-b")
        await("s-b on stage after restore") { graph.sessions.restored.value && graph.sessions.activeTabId.value == "s-b" }
        assertEquals("s-b", runBlocking { graph.settings.lastActiveSessionId.first() })
    }

    // ---- fixture ---------------------------------------------------------------------------------

    private fun seed(
        aState: SessionState = SessionState.DETACHED,
        aLayer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME,
        aLastLiveAt: Long? = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(12),
        third: Boolean = false,
    ) = runBlocking {
        graph.workspaces.upsert(Workspace(Workspace.DEFAULT_ID, Workspace.DEFAULT_NAME, SwatchColor.COPPER, "H", sortOrder = 0, createdAt = 0L))
        graph.settings.setCurrentWorkspaceId(Workspace.DEFAULT_ID)
        graph.settings.setLastActiveSessionId("s-a")
        val homelab = host("homelab", "homelab", SwatchColor.VERDIGRIS)
        val pihole = host("pi-hole", "pi-hole", SwatchColor.MOSS)
        val build = host("build-box", "build box", SwatchColor.SLATE)
        listOf(homelab, pihole, build).forEach { graph.hosts.upsert(it) }
        graph.sessionRecords.upsert(record("s-a", homelab, 0, aState, aLayer, aLastLiveAt, lastCommand = "docker compose ps"))
        graph.sessionRecords.upsert(record("s-b", pihole, 1, lastCommand = "tail -f pihole.log"))
        if (third) graph.sessionRecords.upsert(record("s-c", build, 2, lastCommand = "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame("s-a", frame(listOf("ben@homelab:~/srv$ docker compose ps", "caddy   Up 3 days", "ben@homelab:~/srv$ ")))
        graph.sessionRecords.saveFrame("s-b", frame(listOf("pi@pi-hole:/etc/pihole$ tail -f pihole.log", "Sep 18 20:41:02 dnsmasq[712]: query[A] api.berth.app")))
        if (third) graph.sessionRecords.saveFrame("s-c", frame(listOf("ci@build:~$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s")))
    }

    private fun host(id: String, name: String, color: SwatchColor) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = "$id.internal", port = 22, user = "ben", auth = AuthMethod.AskEachTime, createdAt = 0L,
    )

    private fun record(
        id: String,
        host: Host,
        order: Int,
        state: SessionState = SessionState.DETACHED,
        layer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME,
        lastLiveAt: Long? = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30),
        lastCommand: String? = null,
    ) = SessionRecord(
        id = id, workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = state, layer = layer, title = host.name,
        lastCommand = lastCommand, sortOrder = order, createdAt = 0L, lastLiveAt = lastLiveAt,
    )

    private fun restore() = runBlocking {
        graph.sessions.restore()
        assertTrue(graph.sessions.restored.value)
    }

    /** A BEL in a tab that is not on stage: the smallest thing that lights the ring. */
    private fun bell(id: String) {
        val session = graph.sessions.get(id)!!
        await("$id off stage") { !session.onStage }
        session.emulator.write("\u0007")
    }

    private fun grantNotifications() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.notifier.refresh()
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

    private fun lines(frame: ByteArray): List<String> = DataInputStream(frame.inputStream()).use { d ->
        d.readInt()
        List(d.readInt()) { d.readUTF() }
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

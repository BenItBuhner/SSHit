package app.berth.android.session

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.berth.android.files.TransferManager
import app.berth.android.screenshots.FakeSftpFileSystem
import app.berth.android.screenshots.TestGraph
import app.berth.android.security.FakeAuthenticator
import app.berth.android.security.LockState
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.LockTimeout
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.Workspace
import app.berth.sftp.ConflictChoice
import app.berth.sftp.SftpFileSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.After
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * The manager against the process (vision §4.3 L0, spec C3 Persistence, C21): frames go to disk
 * when the app leaves the screen and when the OS trims memory, a tab that held a socket when the
 * process died comes back detached on its frame, attention reaches the shade only while the app is
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

    @After
    fun tearDown() {
        graph.close()
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
    fun `a trim from UI_HIDDEN up saves frames`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        graph.sessions.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        await("frames saved on trim") { graph.sessionRecords.frames.keys == setOf("s-a", "s-b") }
        // Nothing moved since: the next trim writes nothing, whatever its level.
        graph.sessionRecords.frames.clear()
        graph.sessions.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        Thread.sleep(150)
        assertTrue("unchanged screens are not rewritten", graph.sessionRecords.frames.isEmpty())
        graph.sessions.get("s-a")!!.emulator.write("ben@homelab:~/srv$ uptime\r\n")
        graph.sessions.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        await("only the tab whose screen moved") { graph.sessionRecords.frames.keys == setOf("s-a") }
    }

    @Test
    fun `a RUNNING trim level, with the app on screen, saves nothing`() {
        seed()
        restore()
        graph.sessionRecords.frames.clear()
        graph.process.start()
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)) {
            graph.sessions.onTrimMemory(level)
        }
        Thread.sleep(150)
        assertTrue("the app is on screen and not about to die; no scrollback walk under the user's finger", graph.sessionRecords.frames.isEmpty())
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
    fun `a tab that was live when the process died comes back detached, on its frame, marked detached`() {
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
        assertTrue("detached marker in ${screen.filter { it.isNotBlank() }}", screen.any { it.contains("detached $stamp") })
        // The record on disk agrees, so the next launch reads the same.
        assertEquals(SessionState.DETACHED, graph.sessionRecords.items.value.first { it.id == "s-a" }.state)
    }

    @Test
    fun `a reconnecting tab at death gets the detached marker too, a tab already detached does not`() {
        seed(aState = SessionState.RECONNECTING, aLayer = PersistenceLayer.IN_APP, aLastLiveAt = System.currentTimeMillis())
        restore()
        assertTrue(graph.sessions.get("s-a")!!.emulator.screenText().any { it.contains("detached") })
        assertFalse(graph.sessions.get("s-b")!!.emulator.screenText().any { it.contains("detached") })
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
    fun `with the app away the active tab counts too, and is seen again on return`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        await("s-a on stage") { graph.sessions.get("s-a")!!.onStage }
        graph.process.stop()
        assertFalse("nothing is on stage while the app is away", graph.sessions.get("s-a")!!.onStage)
        bell("s-a")
        await("the active tab's bell reaches the shade") { notifications.getNotification(SessionNotifier.attentionTag("s-a"), 2) != null }
        assertTrue(graph.sessions.get("s-a")!!.record.value.needsAttention)
        graph.process.start()
        assertTrue(graph.sessions.get("s-a")!!.onStage)
        assertFalse("in front of the user again, so seen", graph.sessions.get("s-a")!!.record.value.needsAttention)
        await("its notification goes with it") { notifications.getNotification(SessionNotifier.attentionTag("s-a"), 2) == null }
        // Back on screen, the same bell is the stage's own again.
        graph.sessions.get("s-a")!!.emulator.write("\u0007")
        assertFalse(graph.sessions.get("s-a")!!.record.value.needsAttention)
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

    // ---- problems (spec C21, Problems channel) ---------------------------------------------------

    @Test
    fun `a connection that fails while the app is on screen lights the tab off stage and the shade stays quiet`() {
        grantNotifications()
        seed()
        seedBrokenKeyTab()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        await("s-a on stage") { graph.sessions.get("s-a")!!.onStage }
        graph.sessions.reconnect("s-c")
        val broken = graph.sessions.get("s-c")!!
        await("the sign-in fails") { broken.state == SessionState.FAILED }
        await("and lights the tab") { broken.record.value.needsAttention }
        assertEquals("The key for build box no longer exists", broken.record.value.attentionReason)
        Thread.sleep(150)
        assertNull("no heads-up over Berth's own header", notifications.getNotification(SessionNotifier.problemTag("s-c"), 3))
        assertNull(notifications.getNotification(SessionNotifier.attentionTag("s-c"), 2))
        // The ring is the signal, so jump-to-unread finds the tab, and arriving clears it.
        assertTrue(graph.sessions.jumpToUnread())
        assertEquals("s-c", graph.sessions.activeTabId.value)
        await("seen") { !broken.record.value.needsAttention }
    }

    @Test
    fun `away, the same failure reaches the shade once, as a Problem with Retry and Detach`() {
        grantNotifications()
        seed()
        seedBrokenKeyTab()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        graph.process.stop()
        graph.sessions.reconnect("s-c")
        await("problem posted") { notifications.getNotification(SessionNotifier.problemTag("s-c"), 3) != null }
        val posted = notifications.getNotification(SessionNotifier.problemTag("s-c"), 3)
        assertEquals(SessionNotifier.CHANNEL_PROBLEMS, posted.channelId)
        assertEquals("Couldn't connect to build box", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("The key for build box no longer exists", posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Retry", "Detach"), posted.actions.map { it.title.toString() })
        assertEquals("s-c", shadowOf(posted.contentIntent).savedIntent.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertTrue("lit for the return, like any tab that needed the user while the app was away", graph.sessions.get("s-c")!!.record.value.needsAttention)
        Thread.sleep(150)
        assertNull("one event, one notification: Problems carries it, Attention does not repeat it", notifications.getNotification(SessionNotifier.attentionTag("s-c"), 2))
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

    // ---- the app lock (spec C20 with C21) --------------------------------------------------------

    @Test
    fun `a return under the app lock leaves the active tab's news for the unlock, and the shade speaks meanwhile`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        await("s-a on stage") { graph.sessions.get("s-a")!!.onStage }
        graph.process.stop()
        bell("s-a")
        await("the active tab's bell reaches the shade") { notifications.getNotification(SessionNotifier.attentionTag("s-a"), 2) != null }

        // The user comes back to find the lock screen, not the tab: the return is not a seeing.
        lockTheReturn()
        graph.process.start()
        assertEquals(LockState.LOCKED, graph.appLock.state.value)
        assertFalse("nothing is on stage behind the lock screen", graph.sessions.get("s-a")!!.onStage)
        assertTrue("unseen still", graph.sessions.get("s-a")!!.record.value.needsAttention)
        Thread.sleep(100)
        assertNotNull("its notification stays", notifications.getNotification(SessionNotifier.attentionTag("s-a"), 2))
        // Another tab's bell under the lock goes to the shade as it would with the app away.
        bell("s-b")
        await("s-b's bell reaches the shade under the lock") { notifications.getNotification(SessionNotifier.attentionTag("s-b"), 2) != null }

        // The unlock is the moment the active tab is in front of the user.
        unlock()
        await("seen at the unlock") { !graph.sessions.get("s-a")!!.record.value.needsAttention }
        assertTrue(graph.sessions.get("s-a")!!.onStage)
        await("its notification goes with it") { notifications.getNotification(SessionNotifier.attentionTag("s-a"), 2) == null }
        assertTrue("the other tab is still lit for the strip", graph.sessions.get("s-b")!!.record.value.needsAttention)
    }

    @Test
    fun `a notification's tap under the lock waits for the unlock, the latest tap standing`() {
        seed(third = true)
        restore()
        graph.sessions.setActive("s-a")
        lockTheReturn()
        graph.process.start()
        val staged = ArrayList<String>()
        val collector = CoroutineScope(Dispatchers.Default)
        collector.launch { graph.sessions.stageRequests.collect { staged += it } }
        Thread.sleep(50)

        graph.sessions.activateFromNotification("s-b")
        graph.sessions.activateFromNotification("s-c")
        Thread.sleep(100)
        assertEquals("nothing is staged behind the lock screen", "s-a", graph.sessions.activeTabId.value)
        assertEquals("and the shell is not asked for the Stage", emptyList<String>(), staged)

        unlock()
        await("the latest tap is honoured at the unlock") { graph.sessions.activeTabId.value == "s-c" }
        await("and the shell asked for the Stage once") { staged == listOf("s-c") }
        collector.cancel()
    }

    /** The app lock on with Immediately, the controller's first foreground being the cold start that locks. */
    private fun lockTheReturn() {
        graph.settings.security.value = SecuritySettings(appLock = true, lockTimeout = LockTimeout.IMMEDIATELY)
        graph.appLock.onForeground()
        assertEquals(LockState.LOCKED, graph.appLock.state.value)
    }

    private fun unlock() {
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        assertTrue(runBlocking { graph.appLock.unlock() })
    }

    // ---- a copy waiting on the user (the transfers notification, spec C21) -----------------------

    @Test
    fun `a copy stopped on a question after its Files tab was closed is reachable from the notification's tap`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        graph.process.start()
        graph.process.stop()
        assertNull("the tab that would ask is gone", graph.sessions.filesTabFor("homelab"))
        val staged = ArrayList<String>()
        val collector = CoroutineScope(Dispatchers.Default)
        collector.launch { graph.sessions.stageRequests.collect { staged += it } }

        // A folder coming down into a tree that already holds one of its files stops on the question.
        val (queue, id) = startWaitingDownload("s-a")
        await("the copy waiting on a.txt") { queue.transfers.value.first { it.id == id }.waiting }
        await("the summary counts it apart and names the terminal") {
            graph.notifier.summary.value.let { it.transfers == 1 && it.waiting == 1 && it.waitingSession == "s-a" }
        }
        val posted = graph.notifier.sessionsNotification(graph.notifier.summary.value)
        assertTrue(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().endsWith("1 transfer \u00B7 waiting on you"))
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals(SessionNotifier.ACTION_OPEN_FILES, tap.action)
        assertEquals("s-a", tap.getStringExtra(SessionNotifier.EXTRA_TAB_ID))

        // The tap, handed on as MainActivity does: a Files tab for the host is created riding that
        // terminal, comes on stage, and the shell is asked for the Stage, where the pane asks on arrival.
        graph.sessions.activateFilesFromNotification(tap.getStringExtra(SessionNotifier.EXTRA_TAB_ID)!!)
        graph.process.start()
        await("a Files tab for homelab") { graph.sessions.filesTabFor("homelab") != null }
        val files = graph.sessions.filesTabFor("homelab")!!
        await("on stage") { graph.sessions.activeTabId.value == files.id }
        await("riding the terminal whose copy waits") { files.ride.value?.id == "s-a" }
        await("the shell asked for the Stage") { staged == listOf(files.id) }
        assertFalse("in front of the user, so not lit", files.record.value.needsAttention)

        // Tapped again with the tab open: the same tab, not a second one.
        graph.sessions.setActive("s-a")
        graph.sessions.activateFilesFromNotification("s-a")
        await("staged again") { staged == listOf(files.id, files.id) }
        assertEquals(1, graph.sessions.records.value.count { it.kind == TabKind.Files })

        // The answer settles the count and takes the link with it; the tap is the plain one again.
        queue.resolveConflict(id, ConflictChoice.SKIP, applyToAll = true)
        await("settled") { graph.notifier.summary.value.let { it.waiting == 0 && it.waitingSession == null } }
        assertNotEquals(SessionNotifier.ACTION_OPEN_FILES, shadowOf(graph.notifier.sessionsNotification(graph.notifier.summary.value).contentIntent).savedIntent.action)
        collector.cancel()
    }

    @Test
    fun `a tap that starts the process waits for the strip, then opens the Files tab`() {
        seed()
        graph.sessions.activateFilesFromNotification("s-a")
        await("a Files tab for homelab, on stage, after restore") {
            graph.sessions.restored.value && graph.sessions.filesTabFor("homelab")?.let { it.id == graph.sessions.activeTabId.value && it.ride.value?.id == "s-a" } == true
        }
    }

    @Test
    fun `away, a Files tab whose copy stopped on a question reaches the shade like any tab that needs the user`() {
        grantNotifications()
        seed()
        restore()
        graph.sessions.setActive("s-a")
        val files = runBlocking { graph.sessions.openFiles("s-a")!! }
        graph.sessions.setActive("s-a")
        graph.process.start()
        graph.process.stop()
        val (queue, id) = startWaitingDownload("s-a")
        await("the copy waiting") { queue.transfers.value.first { it.id == id }.waiting }
        await("the Files tab lit") { files.record.value.needsAttention }
        assertEquals(FilesTab.WAITING_ON_YOU, files.record.value.attentionReason)
        await("and in the shade") { notifications.getNotification(SessionNotifier.attentionTag(files.id), 2) != null }
        val posted = notifications.getNotification(SessionNotifier.attentionTag(files.id), 2)
        assertEquals("Files \u00B7 homelab needs you", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        assertEquals("waiting on you", posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals("its own notification opens the tab itself", SessionNotifier.ACTION_OPEN_TAB, tap.action)
        assertEquals(files.id, tap.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        // Arriving on it is seeing it: the pane asks, the ring and the notification go.
        graph.sessions.activateFromNotification(files.id)
        graph.process.start()
        await("seen") { !files.record.value.needsAttention }
        await("its notification goes with it") { notifications.getNotification(SessionNotifier.attentionTag(files.id), 2) == null }
        assertTrue("the copy itself still waits for the pane's answer", queue.transfers.value.first { it.id == id }.waiting)
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

    private fun host(id: String, name: String, color: SwatchColor, auth: AuthMethod = AuthMethod.AskEachTime) = Host(
        id = id, name = name, color = color, monogram = Host.monogramFor(name), address = "$id.internal", port = 22, user = "ben", auth = auth, createdAt = 0L,
    )

    /**
     * A third tab whose host signs in with a key that no longer exists: its connect fails before
     * any network is touched, with a reason a retry cannot fix, the way a refused sign-in does.
     */
    private fun seedBrokenKeyTab() = runBlocking {
        val build = host("build-box", "build box", SwatchColor.SLATE, auth = AuthMethod.Key("key-gone"))
        graph.hosts.upsert(build)
        graph.sessionRecords.upsert(record("s-c", build, 2, lastCommand = "./gradlew assembleDebug"))
        graph.sessionRecords.saveFrame("s-c", frame(listOf("ci@build:~$ ./gradlew assembleDebug", "BUILD SUCCESSFUL in 1m 12s")))
    }

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

    /**
     * A folder download on [sessionId] through the real transfer queue (the one `FilesCenter` runs,
     * so a Files tab of the session hears of it) over an in-memory server, into a directory that
     * already holds one of the folder's files: the copy stops on that question at once. Returns the
     * queue and the transfer's id.
     */
    private fun startWaitingDownload(sessionId: String): Pair<TransferManager, String> {
        val server = FakeSftpFileSystem().dir("/srv/app", 0).file("/srv/app/a.txt", "alpha\n", 0).file("/srv/app/b.txt", "bravo\n", 0)
        val tree = createTempDirectory("berth-waiting").toFile().also { File(it, "app").mkdirs(); File(it, "app/a.txt").writeText("mine\n") }
        val queue = graph.files.transfers
        queue.channelFor = { Channel(server) }
        val id = queue.downloadFolder(graph.sessions.get(sessionId)!!, runBlocking { server.stat("/srv/app") }, Uri.fromFile(tree))
        return queue to id
    }

    /** One channel over the shared fake server: closing it closes this channel only. */
    private class Channel(inner: FakeSftpFileSystem) : SftpFileSystem by inner {
        override var isOpen: Boolean = true
            private set

        override fun close() {
            isOpen = false
        }
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

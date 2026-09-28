package app.berth.android.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.Host
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshSecurity
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.internal.builders.ServiceComponentBuilder
import dagger.hilt.android.internal.managers.ServiceComponentManager
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The foreground service itself (spec A20; C21's Sessions notification, `ux-spec.md:1039`), run by
 * Robolectric's [ServiceController] with the manager and notifier a phone's would have: the first
 * tab to hold a socket starts it, and it goes to the foreground as a special-use service with the
 * Sessions notification; the last close or detach stops it, and its notification goes with it.
 * Sessions keep running in the service (`ux-spec.md:235`), with or without the notification
 * permission (`:265`). A tab holds a socket from its first connect: each dials a server that
 * accepts and never greets, so it stays Connecting and no sshd is needed. The system shade that
 * shows the notification is a phone's to prove.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SessionServiceTest.GraphApplication::class)
class SessionServiceTest {
    private val app: GraphApplication get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph
    private val silent = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val held = CopyOnWriteArrayList<Socket>()
    private val services = ArrayList<ServiceController<SessionService>>()

    /** Each start and stop the manager asks of [SessionService], in the order it asks, as "start" and "stop". */
    private val asked = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        val recording = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent): ComponentName? {
                if (service.component?.className == SessionService::class.java.name) asked += "start"
                return super.startForegroundService(service)
            }

            override fun stopService(name: Intent): Boolean {
                if (name.component?.className == SessionService::class.java.name) asked += "stop"
                return super.stopService(name)
            }
        }
        graph = TestGraph(recording)
        app.graph = graph
        thread(isDaemon = true, name = "silent-accept") {
            while (!silent.isClosed) held += try { silent.accept() } catch (_: IOException) { break }
        }
    }

    @After
    fun tearDown() {
        graph.close()
        services.forEach { runCatching { it.destroy() } }
        runCatching { silent.close() }
        held.forEach { runCatching { it.close() } }
    }

    private fun quietHost(name: String) = Host(
        id = name, name = name, color = SwatchColor.TEAL, monogram = Host.monogramFor(name),
        address = "127.0.0.1", port = silent.localPort, user = "berth", createdAt = 0,
    )

    /** The manager asks from its own thread, and the service's collector runs on the main looper, which this steps. */
    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; the service was asked $asked")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    /** The service as the system runs it for [intent]: created, then handed the command as [startId]. */
    private fun run(intent: Intent, startId: Int): ServiceController<SessionService> =
        Robolectric.buildService(SessionService::class.java, intent).create().startCommand(0, startId).also { services += it }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private val posted get() = shadowOf(app.getSystemService(NotificationManager::class.java))

    @Test
    fun `the first tab holding a socket starts the service in the foreground as special use, and the last close stops it`() {
        val first = runBlocking { graph.sessions.connect(quietHost("quiet-one")) }
        await("the first tab's start") { asked.isNotEmpty() }
        assertEquals(listOf("start"), asked)
        assertEquals(SessionState.CONNECTING, first.state)

        val service = run(Intent(app, SessionService::class.java), startId = 1)
        val shadow = shadowOf(service.get())
        assertEquals(SessionNotifier.ID_SESSIONS, shadow.lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.get().foregroundServiceType)
        val shown = shadow.lastForegroundNotification
        assertEquals(SessionNotifier.CHANNEL_SESSIONS, shown.channelId)
        assertEquals("1 session live", shown.text())
        assertFalse("a service asked to hold a session does not stop itself", shadow.isStoppedBySelf)

        // A second tab is counted on the notification the running service keeps up.
        val second = runBlocking { graph.sessions.connect(quietHost("quiet-two")) }
        await("the notification counting both") { posted.getNotification(SessionNotifier.ID_SESSIONS)?.text() == "2 sessions live" }

        // Closing one leaves one holding a socket: the service stays, and says so.
        graph.sessions.close(first.id)
        await("the notification counting one") { posted.getNotification(SessionNotifier.ID_SESSIONS)?.text() == "1 session live" }
        assertFalse("the service stays while a tab holds a socket; it was asked $asked", "stop" in asked)

        // The last close stops it; the system then destroys it, and its notification goes with the foreground.
        graph.sessions.close(second.id)
        await("the last close's stop") { "stop" in asked }
        assertEquals("one stop, the last thing asked: $asked", listOf("stop"), asked.dropWhile { it == "start" })
        assertEquals(0, graph.notifier.summary.value.active)
        service.destroy()
        assertTrue("the foreground ends with the service", shadow.isForegroundStopped)
        assertTrue("and takes its notification with it", shadow.notificationShouldRemoved)
    }

    @Test
    fun `the last detach stops it too, and Detach all from its notification detaches every tab`() {
        val tabs = listOf("quiet-one", "quiet-two").map { runBlocking { graph.sessions.connect(quietHost(it)) } }
        await("both tabs connecting") { graph.notifier.summary.value.active == 2 }
        val service = run(Intent(app, SessionService::class.java), startId = 1)
        val shadow = shadowOf(service.get())
        assertEquals("2 sessions live", shadow.lastForegroundNotification.text())

        // Detach all is the notification's own action, a service intent, so the running service takes it.
        val detachAll = shadowOf(shadow.lastForegroundNotification.actions.single { it.title.toString() == "Detach all" }.actionIntent).savedIntent
        service.withIntent(detachAll).startCommand(0, 2)
        await("every tab detached") { tabs.all { it.state == SessionState.DETACHED } }
        await("the last detach's stop") { "stop" in asked }
        assertEquals("one stop, the last thing asked: $asked", listOf("stop"), asked.dropWhile { it == "start" })
        assertEquals(0, graph.notifier.summary.value.active)
        assertFalse("the manager stops a foreground service; it does not stop itself on the action", shadow.isStoppedBySelf)
        service.destroy()
        assertTrue(shadow.isForegroundStopped)
        assertTrue(shadow.notificationShouldRemoved)
    }

    @Test
    fun `with notifications refused the first tab still starts the service in the foreground`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { graph.sessions.connect(quietHost("quiet-one")) }
        await("the first tab's start") { asked.isNotEmpty() }
        assertEquals(listOf("start"), asked)
        val service = run(Intent(app, SessionService::class.java), startId = 1)
        assertEquals(SessionNotifier.ID_SESSIONS, shadowOf(service.get()).lastForegroundNotificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.get().foregroundServiceType)
    }

    @Test
    fun `a restart by the system with no intent stops at once and never goes foreground`() {
        val service = Robolectric.buildService(SessionService::class.java).create().also { services += it }
        val sticky = service.get().onStartCommand(null, 0, 7)
        val shadow = shadowOf(service.get())
        assertEquals(Service.START_NOT_STICKY, sticky)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(7, shadow.stopSelfId)
        assertNull("nothing connected, so no notification", shadow.lastForegroundNotification)
    }

    @Test
    fun `a notification action that finds nothing connected stops the service once it is dispatched`() {
        val detach = Intent(app, SessionService::class.java).setAction(SessionNotifier.ACTION_DETACH).putExtra(SessionNotifier.EXTRA_TAB_ID, "gone")
        val service = run(detach, startId = 3)
        val shadow = shadowOf(service.get())
        await("the action dispatched and the service stopped") { shadow.isStoppedBySelf }
        assertEquals(3, shadow.stopSelfId)
        assertNull("an action alone never takes the foreground", shadow.lastForegroundNotification)
        assertEquals(emptyList<String>(), asked)
    }

    /**
     * An application Hilt's generated injector can reach, in the one respect [SessionService] asks
     * of one: its component builds a service component that injects this test's manager and
     * notifier, the service's two members. So the service's own code runs as it does on a phone.
     */
    class GraphApplication : Application(), GeneratedComponentManager<Any> {
        lateinit var graph: TestGraph

        private val component = object : GeneratedComponent, ServiceComponentManager.ServiceComponentBuilderEntryPoint {
            override fun serviceComponentBuilder(): ServiceComponentBuilder = object : ServiceComponentBuilder {
                override fun service(service: Service): ServiceComponentBuilder = this
                override fun build(): ServiceComponent = Injector()
            }
        }

        private inner class Injector : ServiceComponent, GeneratedComponent, SessionService_GeneratedInjector {
            override fun injectSessionService(sessionService: SessionService) {
                sessionService.sessions = graph.sessions
                sessionService.notifier = graph.notifier
            }
        }

        override fun generatedComponent(): Any = component
    }
}

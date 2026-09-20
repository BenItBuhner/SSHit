package app.berth.android.session

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.berth.android.MainActivity
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The notifications of spec C21 on a real (shadowed) NotificationManager at API 35, where
 * POST_NOTIFICATIONS is a runtime permission: the three channels at their importances, the one-time
 * ask after the first connect and the one-time notice after a refusal, and every notification's
 * intents, which is where a tap or an action actually goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionNotifierTest {
    private lateinit var context: Context
    private lateinit var notifier: SessionNotifier
    private lateinit var manager: NotificationManager

    private val host = Host(id = "h", name = "homelab", color = SwatchColor.MOSS, monogram = "HL", address = "192.168.1.20", user = "ben", createdAt = 0)

    private fun record(id: String = "s-a", reason: String? = "Bell", command: String? = "htop", title: String = host.name) = SessionRecord(
        id = id, workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.LIVE, title = title,
        lastCommand = command, needsAttention = reason != null, attentionReason = reason, sortOrder = 0, createdAt = 0,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifier = SessionNotifier(context)
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.refresh()
    }

    // ---- channels ------------------------------------------------------------------------------

    @Test
    fun `three channels at the importances the spec gives, created once`() {
        notifier.ensureChannels()
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(SessionNotifier.CHANNEL_SESSIONS).importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(SessionNotifier.CHANNEL_ATTENTION).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(SessionNotifier.CHANNEL_PROBLEMS).importance)
        assertFalse("the ongoing count never badges the icon", manager.getNotificationChannel(SessionNotifier.CHANNEL_SESSIONS).canShowBadge())
        notifier.ensureChannels()
        assertEquals(3, manager.notificationChannels.size)
    }

    @Test
    fun `every builder makes sure its channel exists`() {
        assertEquals(0, manager.notificationChannels.size)
        notifier.attentionNotification(record(), at = 0L)
        assertEquals(3, manager.notificationChannels.size)
    }

    // ---- the permission ------------------------------------------------------------------------

    @Test
    fun `the permission is asked once, after the first live, with its rationale first, and Not now is the answer`() {
        assertTrue(notifier.needsPermission)
        assertFalse(notifier.asked)
        assertFalse(notifier.enabled.value)
        assertNull("nothing is asked on a cold launch", notifier.prompt.value)
        notifier.onFirstLive()
        assertEquals(NotificationPrompt.Rationale, notifier.prompt.value)
        notifier.onRationaleDeclined()
        assertNull(notifier.prompt.value)
        assertTrue(notifier.asked)
        // The next process's first connect does not ask again; Settings is the way back.
        val next = SessionNotifier(context)
        assertTrue(next.asked)
        next.onFirstLive()
        assertNull(next.prompt.value)
    }

    @Test
    fun `a swipe or scrim dismissal puts the sheet away without answering, so the next process asks again`() {
        notifier.onFirstLive()
        assertEquals(NotificationPrompt.Rationale, notifier.prompt.value)
        notifier.onRationaleDismissed()
        assertNull("gone for this process", notifier.prompt.value)
        assertFalse("but nothing was decided", notifier.asked)
        val next = SessionNotifier(context)
        assertFalse(next.asked)
        next.onFirstLive()
        assertEquals("the next process's first Live raises it again", NotificationPrompt.Rationale, next.prompt.value)
        // Dismissing something other than the rationale changes nothing.
        next.onPermissionResult(granted = false)
        assertEquals(NotificationPrompt.Denied, next.prompt.value)
        next.onRationaleDismissed()
        assertEquals(NotificationPrompt.Denied, next.prompt.value)
    }

    @Test
    fun `a refusal earns one notice and no second ask, across processes`() {
        notifier.onFirstLive()
        notifier.onPermissionResult(granted = false)
        assertEquals(NotificationPrompt.Denied, notifier.prompt.value)
        assertFalse(notifier.enabled.value)
        notifier.onDeniedNoticeDismissed()
        assertNull(notifier.prompt.value)
        val next = SessionNotifier(context)
        next.onFirstLive()
        assertNull(next.prompt.value)
        next.onPermissionResult(granted = false)
        assertNull("the notice is one-time", next.prompt.value)
    }

    @Test
    fun `a grant closes the flow and turns the shade on`() {
        notifier.onFirstLive()
        assertEquals(NotificationPrompt.Rationale, notifier.prompt.value)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.onPermissionResult(granted = true)
        assertNull(notifier.prompt.value)
        assertTrue(notifier.enabled.value)
        assertTrue(notifier.canPost())
    }

    @Test
    fun `with the permission already held nothing is ever asked`() {
        grant()
        assertFalse(notifier.needsPermission)
        notifier.onFirstLive()
        assertNull(notifier.prompt.value)
        assertTrue(notifier.enabled.value)
    }

    @Test
    fun `the settings row opens this app's notification page`() {
        val intent = notifier.systemSettingsIntent()
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    // ---- sessions ------------------------------------------------------------------------------

    @Test
    fun `the sessions notification counts what holds a socket, lists every tab and carries Detach all`() {
        val summary = SessionsSummary(
            lines = listOf(SessionLine("prod-web", SessionState.LIVE), SessionLine("db", SessionState.RECONNECTING), SessionLine("pi-hole", SessionState.DETACHED)),
            tunnels = 1,
            transfers = 2,
        )
        assertEquals(2, summary.active)
        val n = notifier.sessionsNotification(summary)
        assertEquals(SessionNotifier.CHANNEL_SESSIONS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("2 sessions live \u00B7 1 tunnel \u00B7 2 transfers", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            "2 sessions live \u00B7 1 tunnel \u00B7 2 transfers\nprod-web live \u00B7 db reconnecting \u00B7 pi-hole detached",
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString(),
        )
        val action = n.actions.single()
        assertEquals("Detach all", action.title.toString())
        val shadow = shadowOf(action.actionIntent)
        assertTrue("Detach all is a service intent, so it works with the app dead", shadow.isService)
        assertEquals(SessionNotifier.ACTION_DETACH_ALL, shadow.savedIntent.action)
        assertEquals(SessionService::class.java.name, shadow.savedIntent.component?.className)
        assertTrue(shadowOf(n.contentIntent).isActivity)
    }

    @Test
    fun `one session reads in the singular`() {
        val n = notifier.sessionsNotification(SessionsSummary(listOf(SessionLine("prod-web", SessionState.CONNECTING)), 0, 0))
        assertEquals("1 session live", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("1 session live\nprod-web connecting", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test
    fun `dispatch routes each action to the tabs and says when there was none`() {
        val commands = RecordingCommands()
        assertTrue(notifier.dispatch(shadowOf(notifier.sessionsNotification(SessionsSummary.EMPTY).actions[0].actionIntent).savedIntent, commands))
        val problem = notifier.problemNotification(record("s-db"), SessionProblem.GaveUp(60_000))
        assertTrue(notifier.dispatch(shadowOf(problem.actions[0].actionIntent).savedIntent, commands))
        assertTrue(notifier.dispatch(shadowOf(problem.actions[1].actionIntent).savedIntent, commands))
        assertFalse(notifier.dispatch(Intent(), commands))
        assertFalse(notifier.dispatch(null, commands))
        assertEquals(listOf("detachAll", "reconnect s-db", "detach s-db"), commands.calls)
    }

    // ---- attention -----------------------------------------------------------------------------

    @Test
    fun `an attention notification names the tab, says why and opens that tab`() {
        val n = notifier.attentionNotification(record(id = "s-a", reason = "Bell", command = "htop"), at = 1_700_000_000_000L)
        assertEquals(SessionNotifier.CHANNEL_ATTENTION, n.channelId)
        assertEquals("homelab needs you", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("bell in htop", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1_700_000_000_000L, n.`when`)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        val shadow = shadowOf(n.contentIntent)
        assertTrue(shadow.isActivity)
        val intent = shadow.savedIntent
        assertEquals(SessionNotifier.ACTION_OPEN_TAB, intent.action)
        assertEquals("s-a", intent.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `two tabs' notifications open two different tabs`() {
        val a = shadowOf(notifier.attentionNotification(record(id = "s-a"), 0L).contentIntent).savedIntent
        val b = shadowOf(notifier.attentionNotification(record(id = "s-b"), 0L).contentIntent).savedIntent
        assertEquals("s-a", a.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals("s-b", b.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertFalse("distinct data URIs keep the system from handing one tab the other's intent", a.filterEquals(b))
    }

    @Test
    fun `the reason reads in the shade's register`() {
        assertEquals("bell in htop", notifier.attentionText(record(reason = "Bell", command = "htop")))
        assertEquals("bell", notifier.attentionText(record(reason = "Bell", command = null)))
        assertEquals("command finished", notifier.attentionText(record(reason = "Command finished", command = "make")))
        assertEquals("command failed (1)", notifier.attentionText(record(reason = "Command failed (1)")))
        assertEquals("build finished", notifier.attentionText(record(reason = "Build finished")))
        assertEquals("needs you", notifier.attentionText(record(reason = null)))
    }

    @Test
    fun `attention is posted only with the permission, per tab, and cancelled by tab`() {
        notifier.postAttention(record("s-a"), 0L)
        assertEquals("nothing reaches the shade without the permission", 0, shadowOf(manager).size())
        grant()
        notifier.postAttention(record("s-a"), 0L)
        notifier.postAttention(record("s-b"), 0L)
        assertNotNull(shadowOf(manager).getNotification(SessionNotifier.attentionTag("s-a"), 2))
        assertNotNull(shadowOf(manager).getNotification(SessionNotifier.attentionTag("s-b"), 2))
        notifier.cancelAttention("s-a")
        assertNull(shadowOf(manager).getNotification(SessionNotifier.attentionTag("s-a"), 2))
        assertEquals(1, shadowOf(manager).size())
    }

    // ---- problems ------------------------------------------------------------------------------

    @Test
    fun `a gave-up notification says how long it tried and offers Retry and Detach for that tab`() {
        val n = notifier.problemNotification(record("s-db", title = "db-primary"), SessionProblem.GaveUp(15 * 60_000))
        assertEquals(SessionNotifier.CHANNEL_PROBLEMS, n.channelId)
        assertEquals("Couldn't reconnect to db-primary", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Gave up after 15 min. Tap to retry.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("s-db", shadowOf(n.contentIntent).savedIntent.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals(listOf("Retry", "Detach"), n.actions.map { it.title.toString() })
        val retry = shadowOf(n.actions[0].actionIntent).savedIntent
        val detach = shadowOf(n.actions[1].actionIntent).savedIntent
        assertEquals(SessionNotifier.ACTION_RETRY, retry.action)
        assertEquals(SessionNotifier.ACTION_DETACH, detach.action)
        assertEquals("s-db", retry.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertEquals("s-db", detach.getStringExtra(SessionNotifier.EXTRA_TAB_ID))
        assertFalse(retry.filterEquals(detach))
    }

    @Test
    fun `a refused sign-in is a problem in the server's words`() {
        val n = notifier.problemNotification(record("s-a"), SessionProblem.Failed("The server did not accept the credentials for ben.", authentication = true))
        assertEquals("Couldn't connect to homelab", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("The server did not accept the credentials for ben.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `a closed tab leaves nothing in the shade`() {
        grant()
        notifier.postAttention(record("s-a"), 0L)
        notifier.postProblem(record("s-a"), SessionProblem.GaveUp(1_000))
        assertEquals(2, shadowOf(manager).size())
        notifier.cancelFor("s-a")
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun `durations read the way the spec writes them`() {
        assertEquals("40 s", SessionNotifier.durationText(40_000))
        assertEquals("15 min", SessionNotifier.durationText(15 * 60_000))
        assertEquals("2 h", SessionNotifier.durationText(2 * 3_600_000))
        assertEquals("2 h 10 min", SessionNotifier.durationText(2 * 3_600_000 + 10 * 60_000))
    }

    private class RecordingCommands : SessionCommands {
        val calls = ArrayList<String>()
        override fun detachAll() { calls += "detachAll" }
        override fun reconnect(id: String) { calls += "reconnect $id" }
        override fun detach(id: String) { calls += "detach $id" }
    }
}

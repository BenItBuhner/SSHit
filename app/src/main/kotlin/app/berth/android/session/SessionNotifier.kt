package app.berth.android.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.berth.android.MainActivity
import app.berth.android.R
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What a notification action asks of the tabs; [SessionManager] answers, tests record. */
interface SessionCommands {
    fun detachAll()
    fun reconnect(id: String)
    fun detach(id: String)
}

/** What the app should be showing about the notification permission, if anything (spec C1 note, C21). */
sealed interface NotificationPrompt {
    /** Raised once, after the first successful connect: why Berth wants to notify, then the system dialog. */
    data object Rationale : NotificationPrompt

    /** The permission was refused: a one-time notice of what that costs and where to turn it on. */
    data object Denied : NotificationPrompt
}

/** One terminal tab's line in the Sessions notification: `prod-web live`. */
data class SessionLine(val title: String, val state: SessionState)

/** Everything the Sessions notification says; the manager computes it, the service posts it. */
data class SessionsSummary(val lines: List<SessionLine>, val tunnels: Int, val transfers: Int) {
    /** Tabs holding a socket: connecting, live or reconnecting. */
    val active: Int get() = lines.count { it.state.keepsService }

    companion object {
        val EMPTY = SessionsSummary(emptyList(), 0, 0)
    }
}

/**
 * The system notifications of spec C21 and the permission behind them. Three channels: Sessions
 * (low, the ongoing count with Detach all), Attention (default, a tab needs the user while the
 * app is away) and Problems (high, a reconnect that gave up or a sign-in that failed). Every
 * notification about one tab opens that tab. The runtime permission is asked for once, after the
 * first successful connect and with its reason, never on a cold first launch; a refusal earns one
 * in-app notice, and Settings keeps a row to the system page.
 */
@Singleton
class SessionNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _prompt = MutableStateFlow<NotificationPrompt?>(null)

    /** The permission sheet or notice to show, if any; the UI clears it through the `on…` calls. */
    val prompt: StateFlow<NotificationPrompt?> = _prompt.asStateFlow()

    private val _enabled = MutableStateFlow(canPost())

    /** Whether notifications reach the shade right now; refreshed on foreground and after the dialog. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _summary = MutableStateFlow(SessionsSummary.EMPTY)

    /** What the Sessions notification currently says; the service posts every change. */
    val summary: StateFlow<SessionsSummary> = _summary.asStateFlow()

    /** Whether the runtime permission exists on this OS and is still missing. */
    val needsPermission: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Whether Berth has already asked (or been told "Not now"); it never asks twice on its own. */
    val asked: Boolean get() = prefs.getBoolean(KEY_ASKED, false)

    fun canPost(): Boolean = !needsPermission && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The user may have flipped the switch in system settings while the app was away. */
    fun refresh() {
        _enabled.value = canPost()
    }

    // ---- permission flow ---------------------------------------------------------------------

    /** The first tab of this process came up Live: the moment the spec asks for the permission, once. */
    fun onFirstLive() {
        if (!needsPermission || asked) return
        _prompt.value = NotificationPrompt.Rationale
    }

    /** The rationale's "Not now": no second ask; Settings › Notifications is the way back. */
    fun onRationaleDeclined() {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        if (_prompt.value == NotificationPrompt.Rationale) _prompt.value = null
    }

    /** The system dialog answered. A refusal gets the one-time notice; nothing more is ever asked. */
    fun onPermissionResult(granted: Boolean) {
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        refresh()
        _prompt.value = if (!granted && !prefs.getBoolean(KEY_DENIED_SHOWN, false)) NotificationPrompt.Denied else null
    }

    fun onDeniedNoticeDismissed() {
        prefs.edit().putBoolean(KEY_DENIED_SHOWN, true).apply()
        if (_prompt.value == NotificationPrompt.Denied) _prompt.value = null
    }

    /** The system page for this app's notifications: the Settings row and the denied notice both go there. */
    fun systemSettingsIntent(): Intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- channels ----------------------------------------------------------------------------

    fun ensureChannels() {
        if (CHANNELS.all { manager.getNotificationChannel(it) != null }) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SESSIONS, context.getString(R.string.notification_channel_sessions), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notification_channel_sessions_description)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.notification_channel_attention), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.notification_channel_attention_description)
                },
                NotificationChannel(CHANNEL_PROBLEMS, context.getString(R.string.notification_channel_problems), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notification_channel_problems_description)
                },
            ),
        )
    }

    // ---- sessions ----------------------------------------------------------------------------

    fun updateSessions(summary: SessionsSummary) {
        _summary.value = summary
    }

    /** The ongoing notification: the count line, every tab with its state, Detach all (spec C21). */
    fun sessionsNotification(summary: SessionsSummary): Notification {
        ensureChannels()
        val open = PendingIntent.getActivity(
            context, RC_OPEN, SessionManager.openAppIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS,
        )
        val detachAll = PendingIntent.getService(context, RC_DETACH_ALL, serviceIntent(ACTION_DETACH_ALL, null), FLAGS)
        val count = summary.active
        val text = buildString {
            append(if (count == 1) context.getString(R.string.notification_one_session) else context.getString(R.string.notification_sessions, count))
            when (summary.tunnels) {
                0 -> Unit
                1 -> append(SEP).append(context.getString(R.string.notification_one_tunnel))
                else -> append(SEP).append(context.getString(R.string.notification_tunnels, summary.tunnels))
            }
            when (summary.transfers) {
                0 -> Unit
                1 -> append(SEP).append(context.getString(R.string.notification_one_transfer))
                else -> append(SEP).append(context.getString(R.string.notification_transfers, summary.transfers))
            }
        }
        val lines = summary.lines.joinToString(SEP) { "${it.title} ${stateWord(it.state)}" }
        return Notification.Builder(context, CHANNEL_SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(if (lines.isEmpty()) text else "$text\n$lines"))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notification_detach_all), detachAll).build())
            .build()
    }

    // ---- attention ---------------------------------------------------------------------------

    /** `api-gateway needs you` / `bell in htop` stamped with when it happened; tapping opens that tab. */
    fun attentionNotification(tab: SessionRecord, at: Long): Notification {
        ensureChannels()
        return Notification.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_attention_title, tab.displayTitle))
            .setContentText(attentionText(tab))
            .setContentIntent(openTab(tab.id))
            .setWhen(at)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    /** The reason in the shade's register: `bell in htop` when the running command is known, else the reason itself, lowercased. */
    fun attentionText(tab: SessionRecord): String {
        val reason = tab.attentionReason ?: context.getString(R.string.notification_attention_default)
        val command = tab.lastCommand?.trim()?.takeIf { it.isNotEmpty() }
        return if (reason.equals("Bell", ignoreCase = true) && command != null) "bell in $command" else reason.replaceFirstChar { it.lowercase() }
    }

    /** Posts the tab's attention notice; the manager calls this only while the app is in the background. */
    fun postAttention(tab: SessionRecord, at: Long) {
        if (!canPost()) return
        manager.notify(attentionTag(tab.id), ID_ATTENTION, attentionNotification(tab, at))
    }

    fun cancelAttention(tabId: String) = manager.cancel(attentionTag(tabId), ID_ATTENTION)

    // ---- problems ----------------------------------------------------------------------------

    /** `Couldn't reconnect to db-primary` / `Gave up after 15 min. Tap to retry.` with Retry and Detach. */
    fun problemNotification(tab: SessionRecord, problem: SessionProblem): Notification {
        ensureChannels()
        val title = tab.displayTitle
        val (heading, text) = when (problem) {
            is SessionProblem.GaveUp ->
                context.getString(R.string.notification_problem_gave_up_title, title) to
                    context.getString(R.string.notification_problem_gave_up_text, durationText(problem.afterMillis))
            is SessionProblem.Failed -> context.getString(R.string.notification_problem_failed_title, title) to problem.reason
        }
        val retry = PendingIntent.getService(context, RC_TAB, serviceIntent(ACTION_RETRY, tab.id), FLAGS)
        val detach = PendingIntent.getService(context, RC_TAB, serviceIntent(ACTION_DETACH, tab.id), FLAGS)
        return Notification.Builder(context, CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openTab(tab.id))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notification_retry), retry).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.notification_detach), detach).build())
            .build()
    }

    fun postProblem(tab: SessionRecord, problem: SessionProblem) {
        if (!canPost()) return
        manager.notify(problemTag(tab.id), ID_PROBLEM, problemNotification(tab, problem))
    }

    fun cancelProblem(tabId: String) = manager.cancel(problemTag(tabId), ID_PROBLEM)

    /** A tab closed: nothing about it should stay in the shade. */
    fun cancelFor(tabId: String) {
        cancelAttention(tabId)
        cancelProblem(tabId)
    }

    // ---- intents -----------------------------------------------------------------------------

    /**
     * Opens the app on one tab. The data URI makes each tab's intent distinct to the system, so
     * one shared request code never hands a tab another tab's PendingIntent.
     */
    fun openTabIntent(tabId: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_OPEN_TAB)
        .setData(Uri.parse("berth://tab/$tabId"))
        .putExtra(EXTRA_TAB_ID, tabId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openTab(tabId: String): PendingIntent = PendingIntent.getActivity(context, RC_TAB, openTabIntent(tabId), FLAGS)

    private fun serviceIntent(action: String, tabId: String?): Intent = Intent(context, SessionService::class.java)
        .setAction(action)
        .apply { if (tabId != null) setData(Uri.parse("berth://${action.substringAfterLast('.').lowercase()}/$tabId")).putExtra(EXTRA_TAB_ID, tabId) }

    /** Handles a notification action; true when [intent] carried one. */
    fun dispatch(intent: Intent?, sessions: SessionCommands): Boolean {
        val id = intent?.getStringExtra(EXTRA_TAB_ID)
        when (intent?.action) {
            ACTION_DETACH_ALL -> sessions.detachAll()
            ACTION_RETRY -> id?.let { sessions.reconnect(it); cancelProblem(it) }
            ACTION_DETACH -> id?.let { sessions.detach(it); cancelProblem(it) }
            else -> return false
        }
        return true
    }

    private fun stateWord(state: SessionState): String = context.getString(
        when (state) {
            SessionState.LIVE -> R.string.notification_state_live
            SessionState.IDLE, SessionState.CONNECTING -> R.string.notification_state_connecting
            SessionState.RECONNECTING -> R.string.notification_state_reconnecting
            SessionState.DETACHED, SessionState.CLOSED -> R.string.notification_state_detached
            SessionState.FAILED -> R.string.notification_state_failed
        },
    )

    companion object {
        const val CHANNEL_SESSIONS = "sessions"
        const val CHANNEL_ATTENTION = "attention"
        const val CHANNEL_PROBLEMS = "problems"
        private val CHANNELS = listOf(CHANNEL_SESSIONS, CHANNEL_ATTENTION, CHANNEL_PROBLEMS)

        const val ACTION_OPEN_TAB = "app.berth.android.action.OPEN_TAB"
        const val ACTION_DETACH_ALL = "app.berth.android.action.DETACH_ALL"
        const val ACTION_RETRY = "app.berth.android.action.RETRY"
        const val ACTION_DETACH = "app.berth.android.action.DETACH"
        const val EXTRA_TAB_ID = "tabId"

        /** The ongoing Sessions notification's id; the service owns it. */
        const val ID_SESSIONS = 1
        private const val ID_ATTENTION = 2
        private const val ID_PROBLEM = 3
        private const val RC_OPEN = 0
        private const val RC_DETACH_ALL = 1
        private const val RC_TAB = 2
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        private const val SEP = " \u00B7 "
        private const val PREFS = "notifications"
        private const val KEY_ASKED = "asked"
        private const val KEY_DENIED_SHOWN = "deniedNoticeShown"

        fun attentionTag(tabId: String) = "attention:$tabId"
        fun problemTag(tabId: String) = "problem:$tabId"

        /** `40 s`, `15 min`, `2 h 10 min`: how long the reconnect loop kept trying. */
        fun durationText(millis: Long): String {
            val s = millis / 1000
            val h = s / 3600
            val m = (s % 3600) / 60
            return when {
                h > 0 && m > 0 -> "$h h $m min"
                h > 0 -> "$h h"
                m > 0 -> "$m min"
                else -> "$s s"
            }
        }
    }
}

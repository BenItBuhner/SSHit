package app.berth.android.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.berth.android.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Holds the process in the foreground while sessions are connecting, live or reconnecting. The
 * notification is the "Sessions" surface from the UX spec: a count, and Detach all.
 */
@AndroidEntryPoint
class SessionService : Service() {
    @Inject lateinit var sessions: SessionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DETACH_ALL -> {
                sessions.detachAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val tunnels = intent?.getIntExtra(EXTRA_TUNNELS, 0) ?: 0
        val notification = buildNotification(count, tunnels)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(count: Int, tunnels: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, SessionManager.openAppIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detachAll = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DETACH_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionsText = if (count == 1) getString(R.string.notification_one_session) else getString(R.string.notification_sessions, count)
        val text = when (tunnels) {
            0 -> sessionsText
            1 -> "$sessionsText \u00B7 " + getString(R.string.notification_one_tunnel)
            else -> "$sessionsText \u00B7 " + getString(R.string.notification_tunnels, tunnels)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_detach_all), detachAll).build())
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_sessions), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.notification_channel_sessions_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DETACH_ALL = "app.berth.android.action.DETACH_ALL"
        private const val ACTION_STOP = "app.berth.android.action.STOP"
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_TUNNELS = "tunnels"

        fun start(context: Context, activeCount: Int, tunnelCount: Int = 0) {
            val intent = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_COUNT, activeCount)
                .putExtra(EXTRA_TUNNELS, tunnelCount)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
        }
    }
}

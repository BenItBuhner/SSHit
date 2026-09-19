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
        val transfers = intent?.getIntExtra(EXTRA_TRANSFERS, 0) ?: 0
        val waiting = intent?.getIntExtra(EXTRA_WAITING, 0) ?: 0
        val notification = buildNotification(count, tunnels, transfers, waiting)
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

    /**
     * `1 session live · 1 tunnel · 2 transfers · 1 waiting on you`: [waiting] is how many of the
     * transfers have stopped for an answer only the Files tab can give, so a copy that stalled
     * while the app was in the background does not read as one that is running.
     */
    private fun buildNotification(count: Int, tunnels: Int, transfers: Int, waiting: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, SessionManager.openAppIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val detachAll = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_DETACH_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sessionsText = if (count == 1) getString(R.string.notification_one_session) else getString(R.string.notification_sessions, count)
        val text = buildString {
            append(sessionsText)
            when (tunnels) {
                0 -> Unit
                1 -> append(" \u00B7 ").append(getString(R.string.notification_one_tunnel))
                else -> append(" \u00B7 ").append(getString(R.string.notification_tunnels, tunnels))
            }
            when (transfers) {
                0 -> Unit
                1 -> append(" \u00B7 ").append(getString(if (waiting > 0) R.string.notification_one_transfer_waiting else R.string.notification_one_transfer))
                else -> {
                    append(" \u00B7 ").append(getString(R.string.notification_transfers, transfers))
                    when (waiting) {
                        0 -> Unit
                        1 -> append(" \u00B7 ").append(getString(R.string.notification_one_waiting))
                        else -> append(" \u00B7 ").append(getString(R.string.notification_waiting, waiting))
                    }
                }
            }
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
        private const val EXTRA_TRANSFERS = "transfers"
        private const val EXTRA_WAITING = "waiting"

        fun start(context: Context, activeCount: Int, tunnelCount: Int = 0, transferCount: Int = 0, waitingCount: Int = 0) {
            val intent = Intent(context, SessionService::class.java)
                .putExtra(EXTRA_COUNT, activeCount)
                .putExtra(EXTRA_TUNNELS, tunnelCount)
                .putExtra(EXTRA_TRANSFERS, transferCount)
                .putExtra(EXTRA_WAITING, waitingCount)
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
        }
    }
}

package app.berth.android.session

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the process in the foreground while sessions are connecting, live or reconnecting, and
 * answers the notification actions. What the ongoing notification says comes from
 * [SessionNotifier.summary]; the manager starts and stops the service with the count of tabs
 * holding a socket, and every change to the summary re-posts the notification while it is up.
 */
@AndroidEntryPoint
class SessionService : LifecycleService() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var notifier: SessionNotifier

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannels()
        lifecycleScope.launch {
            notifier.summary.collect { summary ->
                // The empty summary is the moment before the manager stops the service; posting it would flash "0 sessions".
                if (foreground && summary.active > 0) {
                    getSystemService(NotificationManager::class.java).notify(SessionNotifier.ID_SESSIONS, notifier.sessionsNotification(summary))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != null) {
            // A notification action. When the tap is what started the process, the tabs are still
            // loading, so the command waits for them; a service left with nothing connected stops.
            lifecycleScope.launch {
                sessions.restore()
                notifier.dispatch(intent, sessions)
                if (!foreground && notifier.summary.value.active == 0) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent == null) {
            // Restarted by the system after a kill: nothing is connected until the manager says so, and it will start the service again.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(SessionNotifier.ID_SESSIONS, notifier.sessionsNotification(notifier.summary.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        foreground = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, SessionService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SessionService::class.java)) }
        }
    }
}

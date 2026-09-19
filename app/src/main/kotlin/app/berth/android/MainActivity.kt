package app.berth.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.berth.android.security.LockState
import app.berth.android.security.SecurityCenter
import app.berth.android.security.WindowSecurity
import app.berth.android.session.SessionManager
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.AppRoot
import app.berth.domain.model.SecuritySettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var security: SecurityCenter

    /** The settings the window flags were last set from; the splash waits a frame for the relayout after a change. */
    private var windowSettings: SecuritySettings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hands the launch theme (graphite plus the monogram) over to Theme.App once content is up.
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The lock decision and FLAG_SECURE come from one settings read. The splash stays until it
        // lands and the flags have reached the window, so no content frame is ever drawn unprotected.
        splash.setKeepOnScreenCondition {
            val settings = security.settings.value ?: return@setKeepOnScreenCondition true
            if (windowSettings != settings) {
                applyWindowSecurity(settings)
                return@setKeepOnScreenCondition true
            }
            security.lock.state.value == LockState.UNKNOWN
        }
        lifecycleScope.launch { security.settings.filterNotNull().collect { applyWindowSecurity(it) } }
        setContent {
            AppRoot()
        }
        // A recreation (rotation, a restore after a kill) keeps the launch intent; only a fresh launch acts on it.
        if (savedInstanceState == null) openTabFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabFrom(intent)
    }

    /**
     * A notification's tab (spec C21): the manager puts it on stage now, or the moment the strip is
     * restored. The transfers notification names a terminal whose copy waits on the user and asks
     * for its Files tab, the host's or a new one riding it.
     */
    private fun openTabFrom(intent: Intent?) {
        val id = intent?.getStringExtra(SessionNotifier.EXTRA_TAB_ID) ?: return
        when (intent.action) {
            SessionNotifier.ACTION_OPEN_TAB -> sessions.activateFromNotification(id)
            SessionNotifier.ACTION_OPEN_FILES -> sessions.activateFilesFromNotification(id)
        }
    }

    private fun applyWindowSecurity(settings: SecuritySettings) {
        if (windowSettings == settings) return
        windowSettings = settings
        WindowSecurity.apply(this, settings)
    }

    override fun onStart() {
        super.onStart()
        security.onActivityStarted(this)
    }

    override fun onResume() {
        super.onResume()
        security.onActivityResumed()
    }

    override fun onPause() {
        security.onActivityPaused()
        super.onPause()
    }

    override fun onStop() {
        security.onActivityStopped(this, isChangingConfigurations)
        super.onStop()
    }
}

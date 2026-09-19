package app.berth.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.berth.android.session.SessionManager
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hands the launch theme (graphite plus the monogram) over to Theme.App once content is up.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

    /** A notification's tab (spec C21): the manager puts it on stage now, or the moment the strip is restored. */
    private fun openTabFrom(intent: Intent?) {
        if (intent?.action != SessionNotifier.ACTION_OPEN_TAB) return
        intent.getStringExtra(SessionNotifier.EXTRA_TAB_ID)?.let(sessions::activateFromNotification)
    }
}

package app.berth.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.berth.android.links.LinkInbox
import app.berth.android.security.LockState
import app.berth.android.security.SecurityCenter
import app.berth.android.security.WindowSecurity
import app.berth.android.session.SessionManager
import app.berth.android.session.SessionNotifier
import app.berth.android.ui.AppRoot
import app.berth.android.ui.keyboard.LocalVolumeKeys
import app.berth.android.ui.keyboard.VolumeKeys
import app.berth.domain.model.SecuritySettings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var sessions: SessionManager
    @Inject lateinit var security: SecurityCenter
    @Inject lateinit var links: LinkInbox

    /** The settings the window flags were last set from; the splash waits a frame for the relayout after a change. */
    private var windowSettings: SecuritySettings? = null

    /** An intent not yet read for a notification's tab or a link: the launch's, or one onNewIntent brought; read in onResume. */
    private var intentPending = false

    /** The volume buttons' router (spec A43): the Stage binds it while a shell tab has a use for them, and every other screen leaves them to the system. */
    private val volumeKeys = VolumeKeys()

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
        lifecycleScope.launch {
            // Whenever this activity is on top while the app is locked, the lock window goes over
            // it: at a cold start with the lock on, on a return past the timeout, and after anything
            // took the lock window away (a singleTask launch clears whatever lies over this one).
            // Under the lock window this activity is stopped, so the collector rests until it is back.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                security.lock.state.collect { if (it == LockState.LOCKED) startActivity(Intent(this@MainActivity, LockActivity::class.java)) }
            }
        }
        setContent {
            CompositionLocalProvider(LocalVolumeKeys provides volumeKeys) {
                AppRoot()
            }
        }
        // A recreation (rotation, a restore after a kill) keeps the launch intent; only a fresh launch acts on it.
        if (savedInstanceState == null) intentPending = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentPending = true
    }

    /**
     * A notification's tab (spec C21): the manager puts it on stage now, or the moment the strip is
     * restored. The transfers notification names a terminal whose copy waits on the user and asks
     * for its Files tab, the host's or a new one riding it. Read in onResume rather than where the
     * intent arrives: onNewIntent comes before onStart, where the lock decides on this return, and the
     * manager holds a tap under the lock (or before its decision) until the unlock, so the tab's news
     * is not marked seen behind the lock screen; read earlier, it would be judged against the state
     * left over from before the app went away.
     */
    private fun openTabFrom(intent: Intent?) {
        if (intent == null) return
        // An ssh:// or sftp:// link from another app (the VIEW filter in the manifest): the view model
        // reads it once the lock allows, so a link never opens a login behind the lock screen.
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(links::offer)
            return
        }
        val id = intent.getStringExtra(SessionNotifier.EXTRA_TAB_ID) ?: return
        when (intent.action) {
            SessionNotifier.ACTION_OPEN_TAB -> sessions.activateFromNotification(id)
            SessionNotifier.ACTION_OPEN_FILES -> sessions.activateFilesFromNotification(id)
        }
    }

    /** A volume press the Stage has a use for is taken before the window sees it, or the system would set the volume for it. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = volumeKeys.dispatch(event) || super.dispatchKeyEvent(event)

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
        if (intentPending) {
            intentPending = false
            openTabFrom(intent)
        }
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

package app.berth.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import app.berth.android.security.LockState
import app.berth.android.security.SecurityCenter
import app.berth.android.security.WindowSecurity
import app.berth.android.ui.security.LockWindow
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The lock screen's own window, over [MainActivity] in the same task (spec C20, App lock). A
 * window of its own is what covers the main window's sheets, menus and dialogs, which are windows
 * themselves and would float over anything drawn inside the shell; and the shell beneath stays
 * composed, so an edit in progress, an open sheet or a running Stage is exactly where it was when
 * the lock lifts. [MainActivity] starts this whenever it is on top while the app is locked; this
 * finishes itself the moment the app unlocks. Its window carries the same flags as the main one
 * and its theme has no transition either way, so no frame beneath shows through its arrival or
 * its departure. Back does not uncover the shell: it puts Berth in the background, as leaving does.
 */
@AndroidEntryPoint
class LockActivity : ComponentActivity() {
    @Inject lateinit var security: SecurityCenter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        lifecycleScope.launch { security.settings.filterNotNull().collect { WindowSecurity.apply(this@LockActivity, it) } }
        lifecycleScope.launch {
            security.lock.state.collect { state ->
                if (state == LockState.UNLOCKED) {
                    // Prompts the unlock released attach to the window beneath, not to this one.
                    security.onLockWindowClosing(this@LockActivity)
                    finish()
                }
            }
        }
        setContent {
            val theme by security.interfaceTheme.collectAsState()
            LockWindow(security, theme)
        }
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

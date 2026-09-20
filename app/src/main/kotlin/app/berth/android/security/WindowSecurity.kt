package app.berth.android.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import app.berth.domain.model.SecuritySettings

object WindowSecurity {
    val lockForcesSecure: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    /**
     * Whether a window of Berth's blocks screenshots, recording and casting under [settings]: when
     * the setting says so, or when the app lock has no narrower way to keep a locked task out of
     * Recents. Every window reads this, the Activities through [apply] and a dialog's window through
     * its own policy, so a second window over the Activity is never the one that shows.
     */
    fun secure(settings: SecuritySettings): Boolean {
        val recentsSwitch = !lockForcesSecure
        return settings.blockScreenshots || (settings.appLock && !recentsSwitch)
    }

    fun apply(activity: Activity, settings: SecuritySettings) {
        val recentsSwitch = !lockForcesSecure
        val secure = secure(settings)
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (recentsSwitch) activity.setRecentsScreenshotEnabled(!secure && !settings.appLock)
    }
}

package app.berth.android.security

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import app.berth.domain.model.SecuritySettings

/**
 * Window flags from the Security settings. `FLAG_SECURE` on the activity window blocks screenshots
 * and the Recents preview for every window the app opens: Compose dialogs, sheets and menus inherit
 * it (`SecureFlagPolicy.Inherit`). The app lock also keeps its content out of Recents, through the
 * dedicated switch on Android 13 and later and through `FLAG_SECURE` before that.
 */
object WindowSecurity {
    /**
     * Before Android 13 there is no switch for the Recents preview alone, so the app lock keeps its
     * content out of Recents with `FLAG_SECURE`, and screenshots go with it; the Block screenshots
     * row says so on those devices.
     */
    val lockForcesSecure: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    fun apply(activity: Activity, settings: SecuritySettings) {
        val recentsSwitch = !lockForcesSecure
        val secure = settings.blockScreenshots || (settings.appLock && !recentsSwitch)
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (recentsSwitch) activity.setRecentsScreenshotEnabled(!secure && !settings.appLock)
    }
}

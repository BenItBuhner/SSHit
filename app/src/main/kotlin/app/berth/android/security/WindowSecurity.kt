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
    fun apply(activity: Activity, settings: SecuritySettings) {
        val recentsSwitch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val secure = settings.blockScreenshots || (settings.appLock && !recentsSwitch)
        if (secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (recentsSwitch) activity.setRecentsScreenshotEnabled(!secure && !settings.appLock)
    }
}

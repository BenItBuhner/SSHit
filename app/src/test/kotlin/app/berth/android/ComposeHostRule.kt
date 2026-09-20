package app.berth.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.Shadows.shadowOf

/**
 * Declares the activity `createComposeRule` launches to Robolectric's package manager when the tested variant's
 * manifest does not, so the Compose tests run over the release variant as well as debug.
 *
 * Robolectric starts only activities the tested variant declares, and it reads them from the resource APK the
 * Android Gradle plugin packages for the unit tests, which carries that variant's merged manifest (not the unit
 * test source set's, whose merge Robolectric uses for nothing here). The debug manifest gets
 * `androidx.activity.ComponentActivity` from `androidx.compose.ui:ui-test-manifest`, a `debugImplementation`
 * dependency; the release manifest has no such activity, and none belongs in the manifest the phone gets. This
 * rule declares it as that library's manifest does (exported, `Theme.Material.Light.NoActionBar`), so both variants
 * render the same screen; where the manifest already declares it, nothing changes.
 *
 * Order it before the compose rule (`order = 0` to its `1`): the compose rule launches the activity as it starts.
 */
class ComposeHostRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            declareHost(ApplicationProvider.getApplicationContext())
            base.evaluate()
        }
    }

    private fun declareHost(context: Context) {
        val packageManager = context.packageManager
        val host = ComponentName(context, ComponentActivity::class.java)
        val declared = try {
            packageManager.getActivityInfo(host, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (declared) return
        shadowOf(packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = host.className
                packageName = host.packageName
                exported = true
                theme = android.R.style.Theme_Material_Light_NoActionBar
            },
        )
    }
}

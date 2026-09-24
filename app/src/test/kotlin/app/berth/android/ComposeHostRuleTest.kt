package app.berth.android

import android.app.Application
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The host every Compose test launches draws in a hardware-accelerated window in both variants, as the phone's
 * does: debug's from the manifest `ui-test-manifest` merges in, release's from [ComposeHostRule]'s declaration.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class ComposeHostRuleTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `the host draws in a hardware-accelerated window, as the phone's does`() {
        var view: View? = null
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(10.dp))
        }
        compose.waitForIdle()
        val activity = compose.activity
        val info = activity.packageManager.getActivityInfo(activity.componentName, 0)
        assertEquals("the flags a parsed manifest gives the activity", ActivityInfo.FLAG_HARDWARE_ACCELERATED, info.flags)
        assertTrue("the window is hardware-accelerated", activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
        assertTrue("Compose draws through hardware layers", view!!.isHardwareAccelerated)
    }
}

package app.berth.android.security

import android.app.Application
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import app.berth.domain.model.SecuritySettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Block screenshots on an Activity's own window (spec A77): [WindowSecurity.apply] on a real
 * Activity's window, as MainActivity and LockActivity call it with each settings value. On sets
 * `FLAG_SECURE` and off clears it. From Android 13 the app lock alone leaves the window capturable
 * and takes the task's Recents screenshot away instead, and Block screenshots takes both. Before
 * 13 there is no such switch, so the lock alone sets `FLAG_SECURE`; that branch runs here with
 * `Build.VERSION.SDK_INT` set to 12L's on this runtime, since [WindowSecurity] reads it at each call.
 *
 * Recents' black preview is a phone's to show. MainActivity's own collector is not run: the activity
 * composes AppRoot through Hilt's view models, so it cannot start without the app's whole graph,
 * and its `applyWindowSecurity` hands each settings value to [WindowSecurity.apply] as it comes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WindowSecurityTest {
    /** An Activity that writes down each Recents screenshot switch, which the platform gives no way to read back. */
    class RecentsActivity : ComponentActivity() {
        val recents = ArrayList<Boolean>()

        override fun setRecentsScreenshotEnabled(enabled: Boolean) {
            recents += enabled
            super.setRecentsScreenshotEnabled(enabled)
        }
    }

    private lateinit var controller: ActivityController<RecentsActivity>
    private val activity: RecentsActivity get() = controller.get()

    private fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(RecentsActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun `Block screenshots sets FLAG_SECURE on the window, and turning it off clears it`() {
        assertFalse("a window starts capturable", secure())
        WindowSecurity.apply(activity, SecuritySettings(blockScreenshots = true))
        assertTrue(secure())
        assertEquals("and no Recents screenshot", listOf(false), activity.recents)
        WindowSecurity.apply(activity, SecuritySettings())
        assertFalse(secure())
        assertEquals("the Recents screenshot is back", listOf(false, true), activity.recents)
    }

    @Test
    fun `from Android 13 the lock alone leaves the window capturable and takes its Recents screenshot instead`() {
        assertFalse(WindowSecurity.lockForcesSecure)
        WindowSecurity.apply(activity, SecuritySettings(appLock = true))
        assertFalse("the lock alone does not block screenshots", secure())
        assertEquals(listOf(false), activity.recents)
        WindowSecurity.apply(activity, SecuritySettings(appLock = true, blockScreenshots = true))
        assertTrue(secure())
        assertEquals(listOf(false, false), activity.recents)
        WindowSecurity.apply(activity, SecuritySettings())
        assertFalse(secure())
        assertEquals(listOf(false, false, true), activity.recents)
    }

    @Test
    fun `before Android 13 the lock alone sets FLAG_SECURE, with no Recents switch to take`() {
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
        try {
            assertTrue(WindowSecurity.lockForcesSecure)
            WindowSecurity.apply(activity, SecuritySettings(appLock = true))
            assertTrue("the lock forces the flag", secure())
            WindowSecurity.apply(activity, SecuritySettings())
            assertFalse(secure())
            assertEquals("no Recents switch is called below 13", emptyList<Boolean>(), activity.recents)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }
}

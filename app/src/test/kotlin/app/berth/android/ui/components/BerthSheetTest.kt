package app.berth.android.ui.components

import android.app.Application
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.window.DialogWindowProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.security.WindowSecurity
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SecuritySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sheet as a dialog is a window of its own (spec C23), so the Activity's `FLAG_SECURE` does
 * not reach it: it must carry the flag itself from the same state the Activity's comes from, or a
 * trust sheet's fingerprint would cast from a tablet when it would not from a phone. Runs on the
 * release variant as well (testReleaseUnitTest), the manifest and resources the phone gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-320dpi")
class BerthSheetTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var dialogWindow: Window? = null
    private var sheetPresentation: SheetPresentation? = null

    private fun sheet(secure: Boolean) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                CompositionLocalProvider(LocalWindowSecure provides secure) {
                    Box(Modifier.fillMaxSize()) {
                        BerthSheet(onDismiss = {}) {
                            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
                            val presentation = LocalSheetPresentation.current
                            SideEffect {
                                dialogWindow = window
                                sheetPresentation = presentation
                            }
                            Text("Fingerprint SHA256:abc")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Fingerprint SHA256:abc").assertExists()
    }

    private fun Window.isSecure() = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `on a tablet the sheet is a dialog, in a window of its own`() {
        sheet(secure = false)
        assertEquals(SheetPresentation.DIALOG, sheetPresentation)
        assertNotNull("the sheet's content is in a dialog window", dialogWindow)
    }

    @Test
    fun `the dialog window carries FLAG_SECURE when Berth's windows are secure`() {
        sheet(secure = true)
        assertTrue(dialogWindow!!.isSecure())
    }

    @Test
    fun `the dialog window is not made secure on its own account`() {
        sheet(secure = false)
        assertFalse(dialogWindow!!.isSecure())
    }

    /**
     * With nothing said the dialog inherits the flag from the window under it, so a window made
     * secure by another route stays covered; this pins the platform behaviour the fallback rests on.
     */
    @Test
    fun `the dialog inherits the Activity window's flag when Berth's state says nothing`() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        sheet(secure = false)
        assertTrue(dialogWindow!!.isSecure())
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-420dpi")
    fun `on a phone the sheet rises from the bottom as a sheet`() {
        sheet(secure = true)
        assertEquals(SheetPresentation.SHEET, sheetPresentation)
    }

    /** The one predicate both windows read: the setting, or the lock where Recents has no switch of its own (before T). */
    @Test
    fun `the dialog reads the same state the Activity does`() {
        assertFalse(WindowSecurity.secure(SecuritySettings()))
        assertTrue(WindowSecurity.secure(SecuritySettings(blockScreenshots = true)))
        assertEquals(WindowSecurity.lockForcesSecure, WindowSecurity.secure(SecuritySettings(appLock = true)))
    }
}

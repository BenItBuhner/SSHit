package app.berth.android.ui.stage

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The collapsed Deck's strip against its target (spec C4's 20 dp, A11's 44; #15 review, nit 9): the
 * strip takes 20 dp of the layout whatever it reaches, and the box that takes its tap reaches down
 * into the navigation bar's inset under it, as far as that inset goes and 24 dp at most, and not at
 * all under a soft keyboard. Mounted the way the Stage's bottom chrome mounts it, over the inset
 * that chrome pays, with the insets dispatched to the view as the window would send them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DeckStripTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var view: View? = null
    private var expanded = 0

    @Test
    fun `with no bar under it the strip is its 20 dp, and so is its target`() {
        mount()
        assertEquals(20f, chromeHeightDp(), 1f)
        assertEquals(20f, stripTargetDp(), 1f)
    }

    @Test
    fun `over a navigation bar the strip keeps its 20 dp of the layout and its target reaches 24 dp down into the bar`() {
        mount()
        insets(navigationBar = 48.dp.px())
        // The chrome grew by the bar alone: the strip's reach moved nothing.
        assertEquals(20f + 48f, chromeHeightDp(), 1f)
        assertEquals(44f, stripTargetDp(), 1f)
        // The target hangs from the strip's top, so its first 20 dp are the strip and the rest the bar's.
        val chrome = compose.onNodeWithTag(ChromeTag).fetchSemanticsNode().boundsInRoot
        val strip = strip().fetchSemanticsNode().boundsInRoot
        assertEquals(chrome.top, strip.top, 1f)
        assertEquals("the strip ends where the bar's inset begins", chrome.bottom - 48.dp.px(), strip.top + 20.dp.px(), 1f)
    }

    @Test
    fun `a bar shorter than the reach bounds it`() {
        mount()
        insets(navigationBar = 16.dp.px())
        assertEquals(20f + 16f, stripTargetDp(), 1f)
    }

    @Test
    fun `under a soft keyboard there is nothing to reach into`() {
        mount()
        insets(navigationBar = 48.dp.px(), keyboard = 300.dp.px())
        assertEquals("the chrome pays the keyboard, which covers the bar", 20f + 300f, chromeHeightDp(), 1f)
        assertEquals(20f, stripTargetDp(), 1f)
    }

    @Test
    fun `a tap in the reach, under the drawn strip, opens the Deck`() {
        mount()
        insets(navigationBar = 48.dp.px())
        val strip = strip()
        val bounds = strip.fetchSemanticsNode().boundsInRoot
        // 10 dp below the strip's painted foot: the bar's inset, where the reach is.
        strip.performTouchInput { click(Offset(bounds.width / 2, 30.dp.px().toFloat())) }
        compose.waitForIdle()
        assertEquals(1, expanded)
        // And the strip itself, of course.
        strip.performTouchInput { click(Offset(bounds.width / 2, 10.dp.px().toFloat())) }
        compose.waitForIdle()
        assertEquals(2, expanded)
    }

    /** The strip at the foot of the screen, in the Stage's bottom chrome, which pays the keyboard's or the bar's inset under it. */
    private fun mount() {
        compose.setContent {
            view = LocalView.current
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .testTag(ChromeTag)
                            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
                    ) {
                        DeckStrip(layerName = "Base", latch = ModifierLatch(), onExpand = { expanded++ })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The window's insets as it would send them: a navigation bar and a soft keyboard [keyboard] px tall along the bottom. */
    private fun insets(navigationBar: Int = 0, keyboard: Int = 0) {
        val target = checkNotNull(view) { "mount first" }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
            .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(target, insets) }
        compose.waitForIdle()
    }

    private fun strip(): SemanticsNodeInteraction = compose.onNode(hasContentDescription("Deck collapsed", substring = true) and role(Role.Button))

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun chromeHeightDp(): Float = compose.onNodeWithTag(ChromeTag).fetchSemanticsNode().size.height / compose.density.density

    private fun stripTargetDp(): Float = strip().fetchSemanticsNode().size.height / compose.density.density

    private fun androidx.compose.ui.unit.Dp.px(): Int = with(compose.density) { roundToPx() }

    private companion object {
        const val ChromeTag = "bottom-chrome"
    }
}

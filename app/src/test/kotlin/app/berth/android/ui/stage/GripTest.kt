package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The grip's two marks (spec C4, C2): the pill `accent` while predictive text is on, and a 4 dp
 * `text.2` dot over it while the shell is not echoing, drawn over the Deck's surface only then; a
 * reader hears each as the grip's state. The dot is read from the pixels, 16 dp over the pill's
 * centre in the grip's column: half the pill, a 2 dp gap, half the dot.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class GripTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var predictive by mutableStateOf(false)
    private var echoOff by mutableStateOf(false)
    private var text2 = Color.Unspecified
    private var surface = Color.Unspecified
    private var density = 1f

    private fun mount() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                text2 = Berth.colors.text2
                surface = Berth.colors.surface1
                density = LocalDensity.current.density
                val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
                Deck(layout = DeckLayout.default(), layerIndex = 0, onLayerIndexChange = {}, input = input, predictiveText = predictive, echoOff = echoOff)
            }
        }
        compose.waitForIdle()
    }

    private val grip get() = compose.onNode(hasContentDescription("Grip", substring = true))

    private fun state(): String? = grip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** The colour where the dot stands: the grip column's centre, 16 dp over its middle. */
    private fun dotPixel(): Int {
        compose.waitForIdle()
        val image = grip.captureToImage().toPixelMap()
        return image[image.width / 2, (image.height / 2 - 16 * density).toInt()].toArgb()
    }

    @Test
    fun `the dot stands over the pill while echo is off, and only then`() {
        mount()
        assertEquals("the Deck's surface where the dot would be", surface.toArgb(), dotPixel())
        echoOff = true
        assertEquals("the dot, in text.2", text2.toArgb(), dotPixel())
        echoOff = false
        assertEquals(surface.toArgb(), dotPixel())
    }

    @Test
    fun `a reader hears the grip's state, predictive text, echo off, both, or nothing`() {
        mount()
        assertNull("nothing to say with neither mark up", state())
        echoOff = true
        compose.waitForIdle()
        assertEquals("Echo off, history paused", state())
        predictive = true
        compose.waitForIdle()
        assertEquals("Predictive text on, echo off, history paused", state())
        echoOff = false
        compose.waitForIdle()
        assertEquals("Predictive text on", state())
    }
}

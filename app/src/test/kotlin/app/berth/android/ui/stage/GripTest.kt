package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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
 * centre in the grip's column: half the pill, a 2 dp gap, half the dot. And its target (spec l.102):
 * 44 across where the row has room, 40 on a 360 dp phone, read off where a finger's tap lands.
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
    private var gripTaps = 0
    private var keyTaps = 0

    private fun mount() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                text2 = Berth.colors.text2
                surface = Berth.colors.surface1
                density = LocalDensity.current.density
                // A key asks for the session before it sends; the Grip never does.
                val input = remember { StageInput(session = { keyTaps++; null }, latch = ModifierLatch(), onAppAction = {}) }
                Deck(
                    layout = DeckLayout.default(),
                    layerIndex = 0,
                    onLayerIndexChange = {},
                    input = input,
                    predictiveText = predictive,
                    echoOff = echoOff,
                    onGripTap = { gripTaps++ },
                )
            }
        }
        compose.waitForIdle()
    }

    private val grip get() = compose.onNode(hasContentDescription("Grip", substring = true))

    /** The row's targets left to right, in dp from the row's leading edge: the Grip, the seven keys, the layer key. */
    private fun targets(): List<ClosedFloatingPointRange<Float>> =
        compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .sortedBy { it.left }
            .map { it.left / density..it.right / density }

    /** A finger down and up [x] dp in from the row's leading edge, at the Grip's height; whose tap it was, the Grip's or a key's. */
    private fun tapAt(x: Float): String {
        val y = grip.fetchSemanticsNode().boundsInRoot.center.y
        val (g, k) = gripTaps to keyTaps
        compose.onRoot().performTouchInput { down(Offset(x * density, y)); up() }
        compose.waitForIdle()
        return when {
            gripTaps > g && keyTaps == k -> "Grip"
            keyTaps > k && gripTaps == g -> "key"
            else -> "nothing"
        }
    }

    private fun esc() = compose.onNode(hasTestTag(DeckKeyTag) and hasContentDescription("Esc")).fetchSemanticsNode().boundsInRoot

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

    /**
     * Spec l.102 at the width most phones are: nine targets for the Base layer's row, 40 each, the
     * Grip's included. Its target is the leading edge, its column and half the gap to Esc, so 1 and
     * 39 dp in open the sheet, and 41 is Esc's half of the gap.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp-420dpi")
    fun `at 360 dp all nine targets are 40, the first 40 the Grip's and the next Esc's`() {
        mount()
        val row = targets()
        assertEquals("the Grip, seven keys and the layer key", 9, row.size)
        row.forEachIndexed { i, t -> assertEquals("target ${i + 1} across", 40f, t.endInclusive - t.start, 0.5f) }
        assertEquals("the Grip's target from the leading edge", 0f, row.first().start, 0.5f)
        assertEquals("Esc's target starts where the Grip's ends", 40f, esc().left / density, 0.5f)
        assertEquals("1 dp in", "Grip", tapAt(1f))
        assertEquals("39 dp in", "Grip", tapAt(39f))
        assertEquals("41 dp in, Esc's half of the gap", "key", tapAt(41f))
        assertEquals("the Grip opened the sheet twice", 2, gripTaps)
    }

    /** On a 411 dp phone the row has room past 364 dp, and the Grip's target holds l.102's 44: 43 dp in opens the sheet, 45 is Esc. */
    @Test
    fun `at 411 dp the Grip's target is 44`() {
        mount()
        val row = targets()
        assertEquals(44f, row.first().endInclusive - row.first().start, 0.5f)
        assertEquals("Esc's target starts at 44", 44f, esc().left / density, 0.5f)
        assertEquals("1 dp in", "Grip", tapAt(1f))
        assertEquals("43 dp in", "Grip", tapAt(43f))
        assertEquals("45 dp in", "key", tapAt(45f))
    }
}

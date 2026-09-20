package app.berth.android.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A button's fill takes the width its caller sets (spec A9): `fillMaxWidth` on a sheet's stacked
 * answers paints the fill edge to edge, as it did when the fill and the tap were one Box, while a
 * button in a row of them wraps its label. Read from the pixels, since the fill has no semantics
 * of its own: the 48 dp target box is the node, the 44 dp fill is drawn inside it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class BerthButtonTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var accent = Color.Unspecified
    private var surface = Color.Unspecified
    private var density = 1f

    private fun button(modifier: Modifier) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                accent = Berth.colors.accent
                surface = Berth.colors.surface1
                density = LocalDensity.current.density
                // Centred, so a button that wraps its label leaves the box's own edges to the surface.
                Box(Modifier.requiredWidth(300.dp).background(surface), contentAlignment = Alignment.Center) {
                    BerthButton("Share report", onClick = {}, kind = ButtonKind.PRIMARY, modifier = modifier)
                }
            }
        }
        compose.waitForIdle()
    }

    /** The pixel [insetDp] in from the button's left or right edge, on its centre line, where the fill's corner has straightened. */
    private fun ImageBitmap.atEdge(bounds: Rect, insetDp: Int, right: Boolean): Int {
        val x = if (right) bounds.right - insetDp * density else bounds.left + insetDp * density
        return toPixelMap()[x.toInt(), bounds.center.y.toInt()].toArgb()
    }

    @Test
    fun `asked to fill the width, the button's fill runs edge to edge`() {
        button(Modifier.fillMaxWidth())
        val bounds = compose.onNodeWithText("Share report").fetchSemanticsNode().boundsInRoot
        assertEquals("the target box is the 300 dp the caller gave it", 300 * density, bounds.width, 1f)
        val image = compose.onRoot().captureToImage()
        assertEquals("the fill reaches the left edge", accent.toArgb(), image.atEdge(bounds, 6, right = false))
        assertEquals("the fill reaches the right edge", accent.toArgb(), image.atEdge(bounds, 6, right = true))
    }

    @Test
    fun `left to itself, the button wraps its label`() {
        button(Modifier)
        val bounds = compose.onNodeWithText("Share report").fetchSemanticsNode().boundsInRoot
        assertTrue("the button is narrower than the 300 dp it could take: ${bounds.width / density} dp", bounds.width < 200 * density)
        val image = compose.onRoot().captureToImage()
        // Inside the button the fill; 6 dp in from the 300 dp box's own edge, the surface.
        assertEquals(accent.toArgb(), image.atEdge(bounds, 6, right = false))
        assertNotEquals(accent.toArgb(), image.toPixelMap()[(6 * density).toInt(), bounds.center.y.toInt()].toArgb())
    }
}

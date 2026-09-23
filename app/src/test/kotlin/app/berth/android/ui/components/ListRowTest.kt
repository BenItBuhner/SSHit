package app.berth.android.ui.components

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SwatchColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A selected row's 4 dp accent dot sits beside the title (A6; #16 review, nit 11): its centre is the
 * centre of the title's first line, which on a one-line row is the row's own centre and on a row
 * whose caption runs to a second and a third line is well above it, at 1× and at the interface's
 * font cap, where the line is the text's and not the density's conversion of it. The content keeps the 12 dp
 * the dot and its gap take, as it did when the dot was the row's first child. Read from the
 * pixels, since the dot has no semantics of its own: the accent in the dot's 4 dp column, inside
 * the row's 12 dp padding, against the title node's bounds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ListRowTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var accent = Color.Unspecified
    private var density = 1f

    private fun row(subtitle: String?, subtitleMaxLines: Int = 2, leading: Boolean = false) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                accent = Berth.colors.accent
                density = LocalDensity.current.density
                Box(Modifier.requiredWidth(360.dp).background(Berth.colors.surface1)) {
                    ListRow(
                        title = "JetBrains Mono",
                        subtitle = subtitle,
                        subtitleMaxLines = subtitleMaxLines,
                        selected = true,
                        onClick = {},
                        leading = if (leading) ({ Swatch(SwatchColor.TEAL, "JM", 32.dp) }) else null,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The vertical centre of the accent pixels in the dot's column, 12 to 16 dp in from the row's edge; fails when there are none. */
    private fun dotCentreY(): Float {
        val image = compose.onRoot().captureToImage().toPixelMap()
        val left = (12 * density).toInt()
        val right = (16 * density).toInt()
        var top = Int.MAX_VALUE
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in left..right) {
                if (image[x, y].toArgb() == accent.toArgb()) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        assertTrue("no accent dot in the dot's column", bottom >= 0)
        assertTrue("the accent in the dot's column is taller than a 4 dp dot: ${(bottom - top + 1) / density} dp", bottom - top + 1 <= 5 * density)
        return (top + bottom + 1) / 2f
    }

    private fun title() = compose.onNode(hasText("JetBrains Mono"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun titleCentreY(): Float = title().center.y

    /** The row is the merged node the tap owns; the title's node is inside it. */
    private fun rowCentreY(): Float = compose.onNodeWithText("JetBrains Mono").fetchSemanticsNode().boundsInRoot.center.y

    @Test
    fun `on a one-line row the dot is at the row's centre, which is the title's`() {
        row(subtitle = null)
        assertEquals(rowCentreY(), titleCentreY(), 1f)
        assertEquals(titleCentreY(), dotCentreY(), 1.5f)
        // The title begins after the row's 12 dp padding, the dot's 4 dp and its 8 dp gap.
        assertEquals(24 * density, title().left, 1.5f)
    }

    @Test
    fun `on a three-line row the dot stays beside the title`() {
        row(subtitle = "abc -> => != ...\nThe terminal's own face", subtitleMaxLines = 3)
        val title = titleCentreY()
        val rowCentre = rowCentreY()
        assertTrue("the fixture's row is not taller than its title: row centre $rowCentre, title centre $title", rowCentre - title > 12 * density)
        assertEquals(title, dotCentreY(), 1.5f)
    }

    @Test
    fun `a leading swatch taller than the title leaves the dot on the title, and the content where it was`() {
        row(subtitle = null, leading = true)
        assertEquals(titleCentreY(), dotCentreY(), 1.5f)
        // Padding 12, the dot and its gap 12, the 32 dp swatch and its 12 dp gap: the title at 68 dp, as before.
        assertEquals(68 * density, title().left, 1.5f)
    }

    /**
     * At the interface's 1.3 cap the title's line is the text's, 28.6 dp for Body Medium's 22 sp,
     * not the 25 the density's own sp-to-dp makes of 22 sp; the dot sits on the line the text lays
     * out, on a three-line row and on a one-line one.
     */
    @Test
    fun `at the interface's font cap the dot stays on the title's line`() = atTheCap {
        row(subtitle = "abc -> => != ...\nThe terminal's own face", subtitleMaxLines = 3)
        assertEquals("the title's line at the cap", 28.6f, title().height / density, 0.5f)
        assertEquals(titleCentreY(), dotCentreY(), 1.5f)
    }

    @Test
    fun `at the interface's font cap a one-line row's dot is at the title's centre`() = atTheCap {
        row(subtitle = null)
        assertEquals(titleCentreY(), dotCentreY(), 1.5f)
    }

    /** The system's largest font size, 2×, which the interface takes to its 1.3 cap; set before the row composes. */
    private fun atTheCap(block: () -> Unit) {
        RuntimeEnvironment.setFontScale(2f)
        try {
            block()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}

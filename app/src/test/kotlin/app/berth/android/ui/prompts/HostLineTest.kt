package app.berth.android.ui.prompts

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.textLayout
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SwatchColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The host line on a sheet (design review, nit 12): name and endpoint share the line while both fit
 * it whole, the separator between; when the line is too short for the two, the name takes it and
 * the endpoint the line under, both whole, so a name of eight letters is not cut to three at the
 * interface's font cap. The line is laid out at two widths, one with room and one without.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class HostLineTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val prodApi = Host(
        id = "prod-api",
        name = "prod-api",
        color = SwatchColor.COPPER,
        monogram = Host.monogramFor("prod-api"),
        address = "203.0.113.10",
        user = "deploy",
        auth = AuthMethod.Key("id-laptop"),
        createdAt = 0L,
    )

    private fun line(width: Dp) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                // requiredWidth: the line is laid out at [width] whatever the device the test runs on.
                Box(Modifier.requiredWidth(width)) { HostLine(prodApi) }
            }
        }
        compose.waitForIdle()
    }

    private fun text(text: String): SemanticsNode = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode()

    private fun assertWhole(node: SemanticsNode) {
        val layout = node.textLayout()!!
        assertEquals("one line", 1, layout.lineCount)
        assertFalse("'${layout.layoutInput.text}' is cut", layout.isLineEllipsized(0))
    }

    private fun Rect.sameLineAs(other: Rect) = top < other.bottom && other.top < bottom

    @Test
    fun `with room, name and endpoint share the line, the separator between them, both whole`() {
        line(420.dp)
        val name = text("prod-api")
        val separator = text(" \u00B7 ")
        val endpoint = text("deploy@203.0.113.10")
        assertWhole(name)
        assertWhole(endpoint)
        assertTrue("the endpoint is on the name's line", name.boundsInRoot.sameLineAs(endpoint.boundsInRoot))
        assertTrue("the separator stands between the two", separator.boundsInRoot.left >= name.boundsInRoot.right - 1f && separator.boundsInRoot.right <= endpoint.boundsInRoot.left + 1f)
        assertTrue("the name is right of the swatch", name.boundsInRoot.left > 0f)
    }

    @Test
    fun `without room, the name takes the line whole and the endpoint the one under it, whole`() {
        line(240.dp)
        val name = text("prod-api")
        val endpoint = text("deploy@203.0.113.10")
        assertWhole(name)
        assertWhole(endpoint)
        assertFalse("the endpoint is not on the name's line", name.boundsInRoot.sameLineAs(endpoint.boundsInRoot))
        assertTrue("the endpoint is under the name", endpoint.boundsInRoot.top >= name.boundsInRoot.bottom - 1f)
        assertEquals("the two start at one edge", name.boundsInRoot.left, endpoint.boundsInRoot.left, 1f)
    }
}

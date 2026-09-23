package app.berth.android.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
 * The coach mark (spec A9): one-time, anchored, Body text and a single dismiss action, never more
 * than one on screen. Two composed at once take turns; a sheet over the screen puts the one up
 * away until it goes; and the mark stands over its anchor, under it where there is no room above,
 * inside the window.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class CoachMarkTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private fun themed(content: @Composable BoxScope.() -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                CompositionLocalProvider(LocalCoachMarkSlot provides remember { CoachMarkSlot() }) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun marks(text: String) = compose.onAllNodes(hasText(text) and hasAnyAncestor(isPopup())).fetchSemanticsNodes().size

    private fun awaitMarks(text: String, count: Int) = compose.waitUntil(5_000) { marks(text) == count }

    @Test
    fun `a coach mark says its text to a reader as it rises, and its one action is its dismissal`() {
        var dismissed = 0
        themed { Box(Modifier.align(Alignment.BottomCenter).size(48.dp)) { CoachMark("The Nub, explained", onDismiss = { dismissed++ }) } }
        awaitMarks("The Nub, explained", 1)
        compose.onNode(hasText("The Nub, explained") and hasAnyAncestor(isPopup()))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup())).assertCountEquals(1)

        compose.onNode(hasText("Got it") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun `two marks up at once show one, and the other once the first goes`() {
        var first by mutableStateOf(true)
        themed {
            Column(Modifier.align(Alignment.Center)) {
                Box(Modifier.size(48.dp)) { if (first) CoachMark("The first mark", onDismiss = { first = false }) }
                Box(Modifier.size(48.dp)) { CoachMark("The second mark", onDismiss = {}) }
            }
        }
        awaitMarks("The first mark", 1)
        compose.waitForIdle()
        assertEquals("never more than one on screen", 0, marks("The second mark"))
        compose.onAllNodes(isPopup()).assertCountEquals(1)

        compose.onNode(hasText("Got it") and hasAnyAncestor(isPopup())).performClick()
        awaitMarks("The second mark", 1)
        assertEquals(0, marks("The first mark"))
        compose.onAllNodes(isPopup()).assertCountEquals(1)
    }

    @Test
    fun `a sheet over the screen puts the mark away until the sheet goes`() {
        var sheet by mutableStateOf(false)
        themed {
            Box(Modifier.align(Alignment.Center).size(48.dp)) { CoachMark("Under a sheet", onDismiss = {}) }
            if (sheet) BerthSheet(onDismiss = { sheet = false }) { Text("A sheet's content", Modifier.padding(24.dp)) }
        }
        awaitMarks("Under a sheet", 1)

        sheet = true
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("A sheet's content")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("the mark would float over the sheet", 0, marks("Under a sheet"))

        sheet = false
        awaitMarks("Under a sheet", 1)
    }

    @Test
    fun `the mark stands centred over its anchor, under it where there is no room above, and inside the window`() {
        val position = CoachMarkPosition(gap = 8, margin = 8)
        val window = IntSize(400, 800)
        val mark = IntSize(200, 100)
        fun at(anchor: IntRect) = position.calculatePosition(anchor, window, LayoutDirection.Ltr, mark)

        assertEquals("centred, 8 over the anchor", IntOffset(100, 592), at(IntRect(180, 700, 220, 740)))
        assertEquals("held 8 in from the right edge", IntOffset(192, 592), at(IntRect(360, 700, 400, 740)))
        assertEquals("held 8 in from the left edge", IntOffset(8, 592), at(IntRect(0, 700, 40, 740)))
        assertEquals("no room above: 8 under the anchor", IntOffset(100, 58), at(IntRect(180, 10, 220, 50)))
    }
}

package app.berth.android.ui.components

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The header's geometry as every screen shares it: the back action at the leading edge, the title
 * after it, the actions trailing. A caller's trailing lambda is its actions. The four editors
 * (Deck, Interface, the theme gallery and the theme editor) pass theirs that way, and when the
 * navigation slot was added after the actions parameter, their lambdas bound to it instead: the
 * actions stood at the leading edge in place of the back action, and the title after them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class ScreenHeaderTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `a trailing lambda is the header's actions, trailing after the back action and the title`() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                ScreenHeader("Deck", onBack = {}) {
                    BerthButton("Done", onClick = {}, kind = ButtonKind.TEXT)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        val back = compose.onNode(hasContentDescription("Back")).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Deck"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val done = compose.onNodeWithText("Done").fetchSemanticsNode().boundsInRoot
        assertTrue("the title ($title) is not after the back action ($back)", title.left >= back.right)
        assertTrue("Done ($done) is not after the title ($title)", done.left >= title.right - 1f)
    }

    @Test
    fun `a navigation slot takes the back action's place and leaves the actions trailing`() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                ScreenHeader(
                    "3 selected",
                    navigation = { IconAction(onClick = {}, description = "Clear selection") { Glyph("\u00D7", size = 24) } },
                    actions = { BerthButton("All", onClick = {}, kind = ButtonKind.TEXT) },
                )
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isEmpty())
        val clear = compose.onNode(hasContentDescription("Clear selection")).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("3 selected"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithText("All").fetchSemanticsNode().boundsInRoot
        assertTrue("the title ($title) is not after the navigation slot ($clear)", title.left >= clear.right)
        assertTrue("All ($all) is not after the title ($title)", all.left >= title.right - 1f)
    }
}

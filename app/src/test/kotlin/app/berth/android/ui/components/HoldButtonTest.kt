package app.berth.android.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
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
 * The hold-to-confirm button (spec C13): a hold that lasts the hold time confirms once, a finger
 * lifted early confirms nothing, a screen reader's long-press confirms outright, and a disabled
 * button offers neither.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class HoldButtonTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val label = "Replace saved key \u2014 hold to confirm"

    @Test
    fun `held for the hold time it confirms once, lifted early it confirms nothing`() {
        var confirmed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) { HoldButton(label, onConfirm = { confirmed++ }, holdMillis = 1000) }
        }
        val button = compose.onNodeWithText(label)
        button.assert(hasStateDescription("Hold to confirm"))

        // Each touch is followed by an idle pass, as a phone's main thread takes the press into the
        // composition before the next frame; the clock then stands for the hold's duration alone.
        touch(button) { down(center) }
        compose.mainClock.advanceTimeBy(400)
        button.assert(hasStateDescription("Holding"))
        touch(button) { up() }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("lifted at 400 ms of 1000, nothing fires", 0, confirmed)
        button.assert(hasStateDescription("Hold to confirm"))

        touch(button) { down(center) }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("held past the hold time, it fires", 1, confirmed)
        button.assert(hasStateDescription("Hold to confirm"))
        touch(button) { up() }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals("once per hold, not again on the lift", 1, confirmed)
    }

    private fun touch(node: SemanticsNodeInteraction, block: TouchInjectionScope.() -> Unit) {
        node.performTouchInput(block)
        compose.waitForIdle()
    }

    @Test
    fun `a screen reader's long-press confirms outright, a disabled button offers no confirm`() {
        var confirmed = 0
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Column {
                    HoldButton(label, onConfirm = { confirmed++ })
                    HoldButton("Nothing to replace", onConfirm = { confirmed += 100 }, enabled = false)
                }
            }
        }
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(1, confirmed)

        val disabled = compose.onNodeWithText("Nothing to replace")
        disabled.assertIsNotEnabled()
        disabled.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        touch(disabled) { down(center) }
        compose.mainClock.advanceTimeBy(1500)
        touch(disabled) { up() }
        assertEquals("a disabled button ignores the hold", 1, confirmed)
    }
}

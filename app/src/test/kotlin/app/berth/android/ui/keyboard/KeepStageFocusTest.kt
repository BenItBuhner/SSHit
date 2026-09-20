package app.berth.android.ui.keyboard

import android.app.Application
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [KeepStageFocus] on a Stage cut down to its regions, under a window root marked with [windowFocus]
 * the way the shell's is (spec A11, focus order). The frame the Stage spends between the manager's
 * word that a tab is on stage and the tab's body (spec C3: nothing stands over the strip meanwhile):
 * the focus that has nothing to hold in that frame waits for the body rather than settling on the
 * strip, so a shell coming on stage is typed into; with no body coming at all, the strip takes it
 * after the wait. And a control removed from under the focus: the platform puts the focus on the
 * first control it finds, which the keeper tells from a move by the loss the window counted, and
 * puts back where it was. The composition runs on the standard test dispatcher, the order the phone
 * keeps, in which the frame is there to be seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeepStageFocusTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `a body that comes on stage a frame after the strip has the focus, not the strip`() {
        var bodyShown by mutableStateOf(false)
        compose.setContent {
            Stage { focus ->
                Box(Modifier.stageRegion(focus, StageRegion.Strip)) { Box(Modifier.size(48.dp).testTag("tab").focusable()) }
                if (bodyShown) {
                    Box(Modifier.stageRegion(focus, StageRegion.Body)) { Box(Modifier.size(48.dp).testTag("terminal").focusable()) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                // The body a frame behind the strip, as the Stage's tab flows put it.
                LaunchedEffect(Unit) {
                    withFrameNanos {}
                    bodyShown = true
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("terminal").assertIsFocused()
        compose.onNodeWithTag("tab").assertIsNotFocused()
    }

    @Test
    fun `with no body coming, the strip has the focus once the wait is over`() {
        compose.setContent {
            Stage { focus ->
                Box(Modifier.stageRegion(focus, StageRegion.Strip)) { Box(Modifier.size(48.dp).testTag("tab").focusable()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    @Test
    fun `the region the focus left is the one it goes back to, when that region is still there`() {
        var deckShown by mutableStateOf(true)
        compose.setContent {
            Stage { focus ->
                Box(Modifier.stageRegion(focus, StageRegion.Strip)) { Box(Modifier.size(48.dp).testTag("tab").focusable()) }
                Box(Modifier.stageRegion(focus, StageRegion.Body)) { Box(Modifier.size(48.dp).testTag("terminal").focusable()) }
                if (deckShown) Box(Modifier.stageRegion(focus, StageRegion.Deck)) { Box(Modifier.size(48.dp).testTag("key").focusable()) }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("terminal").assertIsFocused()
        // The Deck's key takes the focus by hand; the Deck then goes, and the focus is back on the terminal, not the strip.
        compose.onNodeWithTag("key").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithTag("key").assertIsFocused()
        deckShown = false
        compose.waitForIdle()
        compose.onNodeWithTag("terminal").assertIsFocused()
        compose.onNodeWithTag("tab").assertIsNotFocused()
    }

    /** The Stage's frame: its focus, the keeper with a keyboard attached, and its root marked as the window's, as the shell marks it. */
    @Composable
    private fun Stage(content: @Composable (StageFocus) -> Unit) {
        val focus = rememberStageFocus()
        val window = remember { WindowFocus() }
        KeepStageFocus(focus, enabled = true, window = window)
        Column(Modifier.windowFocus(window)) { content(focus) }
    }
}

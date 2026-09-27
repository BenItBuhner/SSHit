package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Stage pill's words for a reconnect, spec D4's: "Reconnecting · retry in 4 s", with its one action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatePillTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `a reconnect waiting on its next try counts down in seconds with a space before the unit, and the count moves`() {
        val retryIn = MutableStateFlow<Int?>(4)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                StatePill(SessionState.RECONNECTING, retryIn, lastLiveAt = null, onReconnect = {}, onDetach = {}, onClose = {}, now = 0L)
            }
        }
        compose.onNodeWithText("Reconnecting \u00B7 retry in 4 s").assertExists()
        compose.onNodeWithText("Detach").assertExists()
        retryIn.value = 2
        compose.waitForIdle()
        compose.onNodeWithText("Reconnecting \u00B7 retry in 2 s").assertExists()
        compose.onAllNodesWithText("Reconnecting \u00B7 retry in 4 s").assertCountEquals(0)
    }

    @Test
    fun `a reconnect with no try counted yet says so and nothing more`() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                StatePill(SessionState.RECONNECTING, MutableStateFlow(null), lastLiveAt = null, onReconnect = {}, onDetach = {}, onClose = {}, now = 0L)
            }
        }
        compose.onNodeWithText("Reconnecting\u2026").assertExists()
    }
}

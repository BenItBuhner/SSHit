package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * The shell's one notice slot (review #18 nit 17): Reopen, a link's line, Back's line and Landed
 * used to be four bars aligned to the same bottom centre, so a tab closing beside a terminal
 * holding a dropped path drew Reopen over Landed. In the slot the one raised last shows and the one
 * under it is back when it goes, whatever it says by then; the bar leaves with its last line on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class NoticeSlotTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    @Test
    fun `of the notices up the one raised last shows, the one under it is back when it goes, and the line stays through the bar's exit`() {
        val reopen = mutableStateOf<Notice?>(null)
        val landed = mutableStateOf<Notice?>(null)
        val taps = ArrayList<String>()
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    NoticeSlot(
                        notices = mapOf("reopen" to reopen.value, "landed" to landed.value),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Paste path").assertCountEquals(0)

        landed.value = Notice("Landed in /tmp", "Paste path") { taps += "paste" }
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()

        // A tab closes beside it: Reopen comes up over Landed, and the bar's action is Reopen's.
        reopen.value = Notice("Closed prod-web", "Reopen") { taps += "reopen" }
        compose.waitForIdle()
        compose.onNodeWithText("Closed prod-web").assertIsDisplayed()
        compose.onAllNodesWithText("Landed in /tmp").assertCountEquals(0)
        compose.onNodeWithText("Reopen").performClick()
        compose.waitForIdle()
        assertEquals(listOf("reopen"), taps)

        // A second path lands while Reopen shows: Landed's line changes and keeps its place under it.
        landed.value = Notice("2 files landed in /tmp", "Paste paths") { taps += "paste" }
        compose.waitForIdle()
        compose.onNodeWithText("Closed prod-web").assertIsDisplayed()
        compose.onAllNodesWithText("2 files landed in /tmp").assertCountEquals(0)

        // Reopen's six seconds are done: Landed is back, saying what it says now, with its own action.
        reopen.value = null
        compose.waitForIdle()
        compose.onNodeWithText("2 files landed in /tmp").assertIsDisplayed()
        compose.onAllNodesWithText("Closed prod-web").assertCountEquals(0)
        compose.onNodeWithText("Paste paths").performClick()
        compose.waitForIdle()
        assertEquals(listOf("reopen", "paste"), taps)

        // The paths pasted, Landed goes: the bar leaves with its line still on it, then is gone.
        compose.mainClock.autoAdvance = false
        landed.value = null
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("2 files landed in /tmp").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onAllNodesWithText("2 files landed in /tmp").assertCountEquals(0)
        compose.onAllNodesWithText("Paste paths").assertCountEquals(0)
    }

    @Test
    fun `two notices raised in one frame show the later of the slot's order, and one going down while another stays changes nothing`() {
        val back = mutableStateOf<Notice?>(null)
        val landed = mutableStateOf<Notice?>(null)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    NoticeSlot(notices = mapOf("back" to back.value, "landed" to landed.value), modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        back.value = Notice("Sessions keep running. Detach all from the notification.", "OK", maxLines = 2) {}
        landed.value = Notice("Landed in /tmp", "Paste path") {}
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()
        // Back's six seconds end under Landed: Landed stays, nothing flickers to Back's line.
        back.value = null
        compose.waitForIdle()
        compose.onNodeWithText("Landed in /tmp").assertIsDisplayed()
        compose.onAllNodesWithText("Sessions keep running. Detach all from the notification.").assertCountEquals(0)
    }
}

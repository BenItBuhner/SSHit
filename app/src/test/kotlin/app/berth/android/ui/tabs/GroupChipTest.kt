package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A group's chip under the finger (spec C3, Header row): a touch folds a group of more than one
 * tab and the next touch opens it again, as often as the finger asks, each touch answering to the
 * group as it now stands rather than as it stood at the chip's first touch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class GroupChipTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val style = TabStripStyle(tabMinWidth = 120.dp, tabMaxWidth = 120.dp)
    private val home = group(Workspace.DEFAULT_ID, "Home", 0)
    private val work = group("ws-work", "Work", 1)
    private val slots = fakeSlots(2, home.id) + fakeSlots(1, work.id, prefix = "w", from = 2)
    private val groups = mutableStateOf(listOf(home, work))
    private val actions = object : TabActions {
        override fun setGroupCollapsed(groupId: String, collapsed: Boolean) {
            groups.value = groups.value.map { if (it.id == groupId) it.copy(collapsed = collapsed) else it }
        }
    }

    private fun homeCollapsed() = groups.value.first { it.id == home.id }.collapsed

    @Test
    fun `a chip folds its group at one touch and opens it at the next, touch after touch`() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.requiredWidth(411.dp).height(style.height)) {
                    TabStrip(slots, groups.value, "t0", actions, Modifier.fillMaxSize(), rememberTabStripState(), style)
                }
            }
        }
        compose.waitForIdle()
        val seen = (1..4).map {
            // Past the double tap's window, so each touch is a tap of its own.
            compose.mainClock.advanceTimeBy(500)
            compose.onNode(hasContentDescription("Group Home, ", substring = true)).performTouchInput { click() }
            compose.waitForIdle()
            homeCollapsed()
        }
        assertEquals("Home collapsed after each touch", listOf(true, false, true, false), seen)
    }
}

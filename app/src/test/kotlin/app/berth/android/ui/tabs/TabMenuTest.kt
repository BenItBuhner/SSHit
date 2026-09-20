package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.TabKind
import app.berth.domain.model.Workspace
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows a tab's long-press menu offers by the tab's kind (spec C3). A terminal tab leads with
 * Duplicate and offers Files; a Files tab keeps Duplicate (two browsers bind nothing) and offers
 * the terminal it rides; a Tunnels tab has no Duplicate, since a second Tunnels login on the host
 * would bind the same ports again, and offers Terminal, the shell beside it, and Files.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TabMenuTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val home = group(Workspace.DEFAULT_ID, "Home", 0)

    private fun menu(record: SessionRecord) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    TabMenu(expanded = true, record = record, groups = listOf(home), actions = object : TabActions {}, onDismiss = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(text: String): Float = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun `a terminal tab's menu leads with Duplicate and offers Files`() {
        menu(fakeRecord("t0", home.id, 0, title = "homelab"))
        compose.onNodeWithText("Duplicate").assertExists()
        compose.onNodeWithText("Files").assertExists()
        compose.onAllNodesWithText("Terminal").assertCountEquals(0)
        assertTrue("Duplicate first, then Rename", top("Duplicate") < top("Rename"))
    }

    @Test
    fun `a Tunnels tab's menu has no Duplicate, and Terminal is the second tab it offers`() {
        menu(fakeRecord("t1", home.id, 0, title = "Tunnels \u00B7 gateway", kind = TabKind.Tunnels))
        compose.onAllNodesWithText("Duplicate").assertCountEquals(0)
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onNodeWithText("Files").assertExists()
        compose.onNodeWithText("Close others").assertExists()
        assertTrue("Rename leads, Terminal before Files", top("Rename") < top("Terminal") && top("Terminal") < top("Files"))
    }

    @Test
    fun `a Files tab's menu keeps Duplicate and offers the terminal it rides`() {
        menu(fakeRecord("f0", home.id, 0, title = "Files \u00B7 homelab", kind = TabKind.Files))
        compose.onNodeWithText("Duplicate").assertExists()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onAllNodesWithText("Files").assertCountEquals(0)
        compose.onAllNodesWithText("Detach").assertCountEquals(0)
    }
}

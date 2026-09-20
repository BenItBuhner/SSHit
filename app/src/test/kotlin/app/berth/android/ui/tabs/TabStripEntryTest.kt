package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Where a keyboard's chord into the strip lands (spec A11; design review, nit 2): on the active
 * tab, where the user already is, while the strip shows it; on the first tab in view once a hand
 * has scrolled the active one off, since a requester on a tab the lazy row has not composed has
 * nothing to focus. Six tabs of a fixed 120 dp in a 300 dp strip, so two and a slice fit.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TabStripEntryTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private val style = TabStripStyle(tabMinWidth = 120.dp, tabMaxWidth = 120.dp)
    private val home = group(Workspace.DEFAULT_ID, "Home", 0)
    private val slots = fakeSlots(6, home.id)
    private val active = mutableStateOf<String?>("t1")
    private val entry = FocusRequester()
    private lateinit var state: TabStripState

    private fun strip() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                state = rememberTabStripState()
                // requiredWidth: the strip is laid out at 300 dp even on the 411 dp device the test runs on.
                Box(Modifier.requiredWidth(300.dp).height(style.height)) {
                    TabStrip(slots, listOf(home), active.value, object : TabActions {}, Modifier.fillMaxSize(), state, style, entry = entry)
                }
            }
        }
        compose.waitForIdle()
    }

    /** The chord's landing: the entry requested, and the id of the one tab that took the focus, or null when none did. */
    private fun enter(): String? {
        val took = compose.runOnIdle { runCatching { entry.requestFocus() }.getOrDefault(false) }
        compose.waitForIdle()
        if (!took) return null
        val focused = compose.onAllNodes(isFocused() and tab()).fetchSemanticsNodes()
        assertEquals("one tab holds the focus", 1, focused.size)
        return TAB_ID.find(focused.single().config[SemanticsProperties.ContentDescription].joinToString())?.groupValues?.get(1)
    }

    private fun tab() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun scrollTo(index: Int, offset: Int = 0) {
        compose.runOnIdle { runBlocking { state.listState.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }

    private fun shown(id: String) = state.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tabKey(id) }

    /** Whether the tab is laid out with none of it past either edge. */
    private fun whole(id: String): Boolean {
        val info = state.listState.layoutInfo
        val item = shown(id) ?: return false
        return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
    }

    @Test
    fun `the chord lands on the active tab while the strip shows it, not on the first`() {
        strip()
        assertEquals("t1", enter())
    }

    @Test
    fun `with the active tab scrolled off, the chord lands on the first tab wholly in view, and on the active one again once it shows`() {
        strip()
        // The strip cannot scroll t4 to its start: the content ends first, leaving a sliver of t3 behind
        // the leading edge and t4 the first tab whole. The chord lands on t4, not on the sliver.
        scrollTo(4)
        assertNull("t1 is off the strip", shown("t1"))
        assertTrue("t3 is laid out cut by the start", shown("t3") != null && !whole("t3"))
        assertTrue("t4 is whole", whole("t4"))
        assertEquals("t4", enter())
        scrollTo(0)
        assertEquals("t1", enter())
    }

    @Test
    fun `the active tab cut by an edge is still the landing, and the focus brings it back into view`() {
        strip()
        // A third of t1 (120 dp, 315 px here) behind the start: still on the strip, so still where the user is.
        scrollTo(1, 100)
        assertTrue("t1 is laid out cut by the start", shown("t1") != null && !whole("t1"))
        assertEquals("t1", enter())
        assertTrue("the focused tab is whole once focused", whole("t1"))
    }

    @Test
    fun `the active tab changing moves the entry with it`() {
        strip()
        compose.runOnIdle { active.value = "t3" }
        compose.waitForIdle()
        assertEquals("t3", enter())
    }

    private companion object {
        /** The tab's id out of its description, "tab t1, detached, tab 2 of 6". */
        val TAB_ID = Regex("^tab (\\w+),")
    }
}

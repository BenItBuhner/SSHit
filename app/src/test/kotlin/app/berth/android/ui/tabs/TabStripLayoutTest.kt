package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.Workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Where the strip puts the active tab (spec C3, Header row): brought into view resting the edge
 * inset inside whichever end it was past, so its swatch never sits against the screen edge and
 * its × never against the count tile; and the ends fade only while there is strip beyond them.
 * Six tabs of a fixed 120 dp in a 300 dp strip, so the third is cut by the end at rest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class TabStripLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private val style = TabStripStyle(tabMinWidth = 120.dp, tabMaxWidth = 120.dp)
    private val home = group(Workspace.DEFAULT_ID, "Home", 0)
    private val slots = fakeSlots(6, home.id)
    private val active = mutableStateOf<String?>("t0")
    private lateinit var state: TabStripState
    private var insetPx = 0
    private var stripPx = 0

    private fun strip(width: Dp = 300.dp) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                state = rememberTabStripState()
                with(LocalDensity.current) {
                    insetPx = style.edgeInset.roundToPx()
                    stripPx = width.roundToPx()
                }
                // requiredWidth: the strip is laid out at [width] even past the 411 dp device the test runs on.
                Box(Modifier.requiredWidth(width).height(style.height)) {
                    TabStrip(slots, listOf(home), active.value, object : TabActions {}, Modifier.fillMaxSize(), state, style)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun item(id: String) = state.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tabKey(id) }

    /** The active item's distance in from the physical start and end of the viewport. */
    private fun margins(id: String): Pair<Int, Int> {
        val info = state.listState.layoutInfo
        val item = checkNotNull(item(id)) { "$id is not visible; visible: ${info.visibleItemsInfo.map { it.key }}" }
        return (item.offset - info.viewportStartOffset) to (info.viewportEndOffset - (item.offset + item.size))
    }

    private fun activate(id: String) {
        compose.runOnIdle { active.value = id }
        compose.waitForIdle()
    }

    private fun assertAbout(expected: Int, actual: Int, what: String) = assertTrue("$what: expected about $expected, was $actual", abs(expected - actual) <= 1)

    @Test
    fun `at rest the first tab sits the inset inside the start and only the far end fades`() {
        strip()
        val (start, _) = margins("t0")
        assertEquals(insetPx, start)
        assertFalse(state.listState.canScrollBackward)
        assertTrue(state.listState.canScrollForward)
    }

    @Test
    fun `a tab cut by the end scrolls until it rests the inset inside the end`() {
        strip()
        // Two tabs and a slice of the third fit; the third is on the visible list, cut.
        val before = checkNotNull(item("t2"))
        assertTrue(before.offset + before.size > stripPx - insetPx)
        activate("t2")
        val (_, end) = margins("t2")
        assertAbout(insetPx, end, "end margin of the active tab")
        // What went out at the start is now behind a fade.
        assertTrue(state.listState.canScrollBackward)
        assertTrue(state.listState.canScrollForward)
    }

    @Test
    fun `a tab cut by the start scrolls back until it rests the inset inside the start`() {
        strip()
        activate("t2")
        val cut = checkNotNull(item("t0"))
        assertTrue(cut.offset < 0)
        activate("t0")
        val (start, _) = margins("t0")
        assertAbout(insetPx, start, "start margin of the active tab")
        // Back at the start to within the animation's rounding: nothing worth a fade lies before it.
        assertEquals(0, state.listState.firstVisibleItemIndex)
        assertTrue(state.listState.firstVisibleItemScrollOffset <= 1)
    }

    @Test
    fun `a tab off the start comes back to rest the inset inside the start`() {
        strip()
        compose.runOnIdle { runBlocking { state.listState.scrollToItem(6) } }
        compose.waitForIdle()
        assertEquals(null, item("t1"))
        assertFalse(state.listState.canScrollForward)
        activate("t1")
        val (start, _) = margins("t1")
        assertEquals(insetPx, start)
        assertTrue(state.listState.canScrollBackward)
        assertTrue(state.listState.canScrollForward)
    }

    @Test
    fun `a tab off the end comes in to rest the inset inside the start`() {
        strip()
        assertEquals(null, item("t5"))
        activate("t5")
        val (start, _) = margins("t5")
        // The last tabs cannot scroll further than the end of the content allows; t5 lands where the end lets it, never cut.
        assertTrue("t5 rests at least the inset inside the start, was $start", start >= insetPx)
        val (_, end) = margins("t5")
        assertTrue("t5 is wholly in view, end margin $end", end >= insetPx)
        assertTrue(state.listState.canScrollBackward)
    }

    @Test
    fun `a strip that fits fades neither end`() {
        strip(width = 900.dp)
        assertFalse(state.listState.canScrollBackward)
        assertFalse(state.listState.canScrollForward)
        activate("t5")
        assertFalse(state.listState.canScrollBackward)
        assertFalse(state.listState.canScrollForward)
    }
}

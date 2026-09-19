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
    private val work = group("ws-work", "Work", 1)
    private val slots = mutableStateOf(fakeSlots(6, home.id))
    private val groups = mutableStateOf(listOf(home))
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
                    TabStrip(slots.value, groups.value, active.value, object : TabActions {}, Modifier.fillMaxSize(), state, style)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun itemByKey(key: String) = state.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    private fun item(id: String) = itemByKey(tabKey(id))
    private fun chip(groupId: String) = itemByKey(chipKey(groupId))

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
    fun `a chip arriving ahead of the first tab shows at the start rather than behind the edge`() {
        // A cold start's groups can land a frame after its tabs: the strip is laid out headless, then the chips arrive.
        // The active tab does not lead its run, so only the strip staying at its start can show the chip; the strip
        // is wide enough that the active tab stays wholly in view once the chip is ahead of it, so nothing else scrolls.
        slots.value = fakeSlots(3, home.id) + fakeSlots(1, work.id, prefix = "w", from = 3)
        active.value = "t1"
        strip(width = 360.dp)
        assertEquals(null, chip(home.id))
        val (t0Before, _) = margins("t0")
        assertEquals(insetPx, t0Before)
        compose.runOnIdle { groups.value = listOf(home, work) }
        compose.waitForIdle()
        // LazyList would keep t0 where it was and leave the chip at a negative offset behind a fade; the strip stays at its start.
        val chip = checkNotNull(chip(home.id)) { "the chip is not visible; visible: ${state.listState.layoutInfo.visibleItemsInfo.map { it.key }}" }
        assertEquals(insetPx, chip.offset - state.listState.layoutInfo.viewportStartOffset)
        assertFalse(state.listState.canScrollBackward)
        assertEquals(0, state.listState.firstVisibleItemIndex)
    }

    @Test
    fun `a leading tab brought back from off the start brings its chip along`() {
        slots.value = fakeSlots(3, home.id) + fakeSlots(3, work.id, prefix = "w", from = 3)
        groups.value = listOf(home, work)
        strip()
        compose.runOnIdle { runBlocking { state.listState.scrollToItem(7) } }
        compose.waitForIdle()
        assertEquals(null, chip(work.id))
        activate("w0")
        // The chip rests at the inset, its tab directly after it.
        val chip = checkNotNull(chip(work.id))
        assertEquals(insetPx, chip.offset - state.listState.layoutInfo.viewportStartOffset)
        val tab = checkNotNull(item("w0"))
        assertTrue(tab.offset > chip.offset + chip.size)
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

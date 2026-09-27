package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.shortUnder
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.Density
import app.berth.domain.model.Workspace
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The strip's targets in a window with no status bar over it, held to l.102's 44 dp by the finger.
 * The audit cannot hold them there: ATF's edge relief takes an element at the display's edge to
 * 32 dp, and against a scrollable's edge it does not run at all, and the strip is both. So each
 * target is tapped outward from its centre, 2 dp at a step, until something else answers, and that
 * edge is halved down to a tenth of a pixel: the span is what a finger gets, reach included, and a
 * neighbour or a gap ends it. The × is held to its own measure in C3 (l.358, a 24 dp slot) rather
 * than l.102's. The Work chip heads a run of one, so its tap is its tab's (C3, Groups); the gap
 * between them, which answers nothing, is where its span ends.
 *
 * Upright, and on its side through [shortUnder] with no status bar, as the shell hands a phone
 * lying down its strip (`AppRoot`), under both densities.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class StripTargetSizeTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var live: TerminalSession
    private val heard = Heard()
    private var sessionSheets = 0

    private class Heard : TabActions {
        val activated = ArrayList<String>()
        val chips = ArrayList<String>()
        var newTabs = 0
        var switchers = 0
        var closes = 0
        override fun activate(id: String) { activated += id }
        override fun close(id: String) { closes++ }
        override fun newTab() { newTabs++ }
        override fun openSwitcher() { switchers++ }
        override fun setGroupCollapsed(groupId: String, collapsed: Boolean) { chips += groupId }
    }

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        live = StageFixture.liveHomelab()
    }

    @After
    fun tearDown() {
        live.close()
        graph.close()
    }

    /** The Stage with its strip; [lyingDown] gives it the strip the shell gives a phone on its side with no status bar over it. */
    private fun mount(lyingDown: Boolean) {
        compose.setContent {
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) {
                val style = LocalTabStripStyle.current
                CompositionLocalProvider(LocalTabStripStyle provides if (lyingDown) style.shortUnder(0.dp) else style) {
                    Box(Modifier.fillMaxSize()) {
                        StageScreen(graph.viewModel, live, heard, onOpenDrawer = {}, onOpenSessionSheet = { sessionSheets++ }, onEditHost = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private val density get() = compose.density.density
    private val strip = hasContentDescription("Tabs, ", substring = true)
    private val sessionRow = hasText("Session") and hasAnyAncestor(isPopup())

    private fun heardSoFar() = listOf(heard.activated.size, heard.chips.size, heard.newTabs, heard.switchers, heard.closes)

    /**
     * What a finger at [at], in px, reaches: `tab:` and the tab's id, `chip:` and the group's,
     * `plus`, `count`, `close`, `overflow`, or `-` for nothing. Each tap stands a double tap's window
     * after the last, so the terminal under the header never takes two as one.
     */
    private fun answer(at: Offset): String {
        val before = heardSoFar()
        val activated = heard.activated.size
        compose.mainClock.advanceTimeBy(DoubleTapGapMs)
        compose.onAllNodes(isRoot())[0].performTouchInput { down(at); up() }
        compose.waitForIdle()
        if (compose.onAllNodes(sessionRow).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(sessionRow).performClick()
            compose.waitForIdle()
            return "overflow"
        }
        val after = heardSoFar()
        return when {
            after[0] != before[0] -> "tab:" + heard.activated.drop(activated).last()
            after[1] != before[1] -> "chip:" + heard.chips.last()
            after[2] != before[2] -> "plus"
            after[3] != before[3] -> "count"
            after[4] != before[4] -> "close"
            else -> "-"
        }
    }

    /**
     * How far from [from] toward [toward], in px and no further than the window's edge, the finger
     * still reaches [label]: 2 dp at a step to the first miss, then halved between the last hit and it.
     */
    private fun reach(from: Offset, toward: Offset, label: String): Float {
        val window = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().size
        val limit = listOf(
            if (toward.x < 0) from.x else if (toward.x > 0) window.width - 1 - from.x else Float.MAX_VALUE,
            if (toward.y < 0) from.y else if (toward.y > 0) window.height - 1 - from.y else Float.MAX_VALUE,
        ).min()
        val step = 2 * density
        var hit = 0f
        var miss = step
        while (true) {
            if (miss >= limit) {
                if (answer(from + toward * limit) == label) return limit
                miss = limit
                break
            }
            if (answer(from + toward * miss) != label) break
            hit = miss
            miss += step
        }
        while (miss - hit > 0.1f) {
            val mid = (hit + miss) / 2
            if (answer(from + toward * mid) == label) hit = mid else miss = mid
        }
        return hit
    }

    /** What [label] answers across and down, in dp, from [from]: across through it, then down through the middle of that. */
    private fun span(from: Offset, label: String): Pair<Float, Float> {
        val left = from.x - reach(from, Offset(-1f, 0f), label)
        val right = from.x + reach(from, Offset(1f, 0f), label)
        val middle = Offset((left + right) / 2, from.y)
        val top = middle.y - reach(middle, Offset(0f, -1f), label)
        val bottom = middle.y + reach(middle, Offset(0f, 1f), label)
        return (right - left) / density to (bottom - top) / density
    }

    private fun centre(matcher: SemanticsMatcher): Offset = compose.onAllNodes(matcher)[0].fetchSemanticsNode().boundsInRoot.center

    /** Where the × is: its node is the tab's, so it is found by the finger, stepping from the active tab's middle toward its end. */
    private fun closeGlyph(tab: SemanticsMatcher): Offset {
        val bounds = compose.onAllNodes(tab)[0].fetchSemanticsNode().boundsInRoot
        var x = bounds.center.x
        while (x < bounds.right) {
            val at = Offset(x, bounds.center.y)
            if (answer(at) == "close") return at
            x += density
        }
        error("nothing on the active tab closes it")
    }

    /** Every target in the header, each as `name: width × height`, and each under its [least] named in [misses]. */
    private fun measureAll(where: String, table: MutableList<String>, misses: MutableList<String>) {
        fun check(name: String, from: Offset, label: String, least: Float = Target) {
            val answered = answer(from)
            if (answered != label) {
                misses += "$where, $name: its middle answers $answered"
                return
            }
            val (w, h) = span(from, label)
            val row = "$where, $name: ${"%.2f".format(w)} × ${"%.2f".format(h)} dp"
            table += row
            // A pixel under, for the rounding of a dp measure to whole pixels at 2.625.
            if (w < least - 1 / density || h < least - 1 / density) misses += "$row, under $least"
        }
        val homelab = hasContentDescription("homelab, ", substring = true) and hasAnyAncestor(strip)
        check("the Home chip", centre(hasContentDescription("Group Home, ", substring = true)), "chip:${Workspace.DEFAULT_ID}")
        check("homelab's tab", centre(homelab), "tab:s-homelab")
        check("homelab's ×", closeGlyph(homelab), "close", least = CloseSlot)
        check("pi-hole's tab", centre(hasContentDescription("pi-hole, ", substring = true) and hasAnyAncestor(strip)), "tab:s-pihole")
        check("the count tile", centre(hasContentDescription("tabs, open the tab switcher", substring = true)), "count")
        check("Overflow", centre(hasContentDescription("More")), "overflow")
        // The plus tab ends the strip, past a phone's edge with three tabs and two chips.
        compose.onNode(strip).performScrollToNode(hasContentDescription("New tab"))
        compose.waitForIdle()
        check("the Work chip", centre(hasContentDescription("Group Work, ", substring = true)), "tab:s-build")
        check("build box's tab", centre(hasContentDescription("build box, ", substring = true) and hasAnyAncestor(strip)), "tab:s-build")
        check("the plus tab", centre(hasContentDescription("New tab")), "plus")
        compose.onNode(strip).performScrollToNode(hasContentDescription("Group Home, ", substring = true))
        compose.waitForIdle()
    }

    private fun assertEveryTargetStands44(posture: String) {
        val table = ArrayList<String>()
        val misses = ArrayList<String>()
        measureAll("$posture, Comfortable", table, misses)
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = Density.COMPACT))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == Density.COMPACT }
        compose.waitForIdle()
        measureAll("$posture, Compact", table, misses)
        assertEquals("every target in the strip answers a finger across 44 dp each way, the × its 24 (all measured: ${table.joinToString("; ")})", emptyList<String>(), misses)
    }

    @Test
    fun `upright with no status bar, every target in the strip answers 44 dp each way, under both densities`() {
        mount(lyingDown = false)
        assertEveryTargetStands44("upright")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun `on its side with no status bar, every target in the strip answers 44 dp each way, under both densities`() {
        mount(lyingDown = true)
        assertEveryTargetStands44("on its side")
    }

    private companion object {
        /** l.102: touch targets 44 minimum. */
        const val Target = 44f

        /** C3 (l.358): the active tab's × is a 24 dp slot. */
        const val CloseSlot = 24f

        /** Past the platform's 300 ms double-tap window, so two taps in one place are two clicks. */
        const val DoubleTapGapMs = 500L
    }
}

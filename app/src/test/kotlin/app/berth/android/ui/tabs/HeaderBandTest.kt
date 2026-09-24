package app.berth.android.ui.tabs

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.a11y.TerminalTag
import app.berth.android.ui.stage.StageScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.Density
import app.berth.ssh.SshSecurity
import app.berth.terminal.MouseTracking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Stage header in a window with no status bar (spec C3 l.351, l.102): no inset lends its row the
 * 4 dp that makes every target 44, so a band of the header's fill stands above the row instead and
 * the header is 44 tall, the row itself A4's 40. Every target in it takes the band, the tabs, the
 * plus tab, a group chip, the count tile and Overflow, under both densities (Compact stands the
 * skin's 40 dp row where no status bar takes its step). Read by tapping, not from touch bounds,
 * which carry Compose's 48 dp reach and would say 48 on a 40 dp header: a finger 1 dp under the
 * header's top and 1 dp above its foot lands on each. Under the foot the Stage's 4 dp padding is the
 * terminal's (spec C2 l.297, D1 l.1161), however far a target's reach would hang: a finger 1 dp and
 * 3 dp into it, under a tab, a group chip, the count tile and Overflow, clicks the terminal's first
 * row, and 1 dp in from either side its first or last column. Over a terminal the canvas's own hit
 * takes that strip before any target's reach could, so the fixed slots' end at the foot is read over
 * a body that takes no touch there, a Tunnels tab's empty state: the count tile and Overflow answer
 * 1 dp above the foot, and 1 dp and 3 dp under it nothing does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class HeaderBandTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var live: TerminalSession
    private val heard = Heard()
    private var sessionSheets = 0

    /** What the header's targets asked for, target by target. */
    private class Heard : TabActions {
        val activated = ArrayList<String>()
        var newTabs = 0
        var switchers = 0
        val chips = ArrayList<String>()
        override fun activate(id: String) { activated += id }
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
        tunnels?.close()
        graph.close()
    }

    /** A Tunnels tab put on stage instead of [live], closed with it. */
    private var tunnels: TerminalSession? = null

    /** The Stage with [tab] on it, once the strip and [ready], the tab's body, are up. */
    private fun mount(tab: TerminalSession = live, ready: SemanticsMatcher = hasTestTag(TerminalTag)) {
        compose.setContent {
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) {
                Box(Modifier.fillMaxSize()) {
                    StageScreen(graph.viewModel, tab, heard, onOpenDrawer = {}, onOpenSessionSheet = { sessionSheets++ }, onEditHost = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(pihole).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(ready).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private val strip = hasContentDescription("Tabs, ", substring = true)
    private val pihole = hasContentDescription("pi-hole, detached", substring = true)
    private val plus = hasContentDescription("New tab")
    private val chip = hasContentDescription("Group Home, ", substring = true)
    private val countTile = hasContentDescription("tabs, open the tab switcher", substring = true)
    private val overflow = hasContentDescription("More")

    private val density get() = compose.density.density

    /** A finger down and up at [x] px across and [y] dp down the window, which has no status bar over it. */
    private fun tap(x: Float, y: Float) {
        compose.onAllNodes(isRoot())[0].performTouchInput { down(Offset(x, y * density)); up() }
        compose.waitForIdle()
    }

    private fun centreX(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher)[0].fetchSemanticsNode().boundsInRoot.center.x

    /** The header's foot, in dp down the window: the strip's, which stands the band and the row. */
    private fun footDp(): Float = compose.onNode(strip).fetchSemanticsNode().boundsInRoot.bottom / density

    private fun heardSoFar() = mapOf(
        "a tab" to heard.activated.size,
        "a chip" to heard.chips.size,
        "the count tile" to heard.switchers,
        "the plus tab" to heard.newTabs,
        "Overflow's Session row" to sessionSheets,
    )

    private val sessionRow = hasText("Session") and hasAnyAncestor(isPopup())

    private fun menuOpen() = compose.onAllNodes(sessionRow).fetchSemanticsNodes().isNotEmpty()

    /** Each header target answers 1 dp under the header's top and 1 dp above the foot of its 44 dp, the band's 4 and the row's 40. */
    private fun assertTheBandIsTheTargets(where: String) {
        assertEquals("$where: the header stands the band's 4 dp and the row's 40", Header, footDp(), 0.5f)
        val ys = listOf(1f, Header - 1f)
        for (y in ys) {
            val n = heard.activated.size
            tap(centreX(pihole), y)
            assertEquals("$where: pi-hole's tab, $y dp down", n + 1, heard.activated.size)
            assertEquals("s-pihole", heard.activated.last())
        }
        for (y in ys) {
            val g = heard.chips.size
            val a = heard.activated.size
            tap(centreX(chip), y)
            assertEquals("$where: the Home chip, $y dp down (tabs activated meanwhile: ${heard.activated.drop(a)})", g + 1, heard.chips.size)
        }
        for (y in ys) {
            val s = heard.switchers
            tap(centreX(countTile), y)
            assertEquals("$where: the count tile, $y dp down", s + 1, heard.switchers)
        }
        for (y in ys) {
            tap(centreX(overflow), y)
            val menu = hasText("Session") and hasAnyAncestor(isPopup())
            assertTrue("$where: Overflow's menu, $y dp down", compose.onAllNodes(menu).fetchSemanticsNodes().isNotEmpty())
            val sheets = sessionSheets
            compose.onNode(menu).performClick()
            compose.waitForIdle()
            assertEquals(sheets + 1, sessionSheets)
        }
        // The plus tab ends the strip, past the edge of a phone's with three tabs and two chips.
        compose.onNode(strip).performScrollToNode(plus)
        compose.waitForIdle()
        for (y in ys) {
            val p = heard.newTabs
            tap(centreX(plus), y)
            assertEquals("$where: the plus tab, $y dp down", p + 1, heard.newTabs)
        }
        compose.onNode(strip).performScrollToNode(chip)
        compose.waitForIdle()
    }

    /**
     * The mouse report a tap at [x] px across and [y] dp down sends the terminal, or null when it
     * sends none. The tap stands a double tap's window after the last, a click of its own and not a
     * second tap selecting a word.
     */
    private fun clickReport(sent: MutableList<String>, x: Float, y: Float): String? {
        sent.clear()
        compose.mainClock.advanceTimeBy(DoubleTapGapMs)
        tap(x, y)
        runCatching { compose.waitUntil(2_000) { sent.any { it.startsWith(MouseReport) } } }
        return sent.firstOrNull { it.startsWith(MouseReport) }
    }

    /**
     * 1 dp and 3 dp under the header's foot, under each target the header's reach could hang from,
     * the finger clicks the terminal's first row and no header target hears it; 1 dp in from either
     * side, halfway down the grid, it clicks the first or the last column. Every tap is tried and
     * every miss named, so a header whose targets hang shows all of them.
     */
    private fun assertThePaddingIsTheTerminals(where: String) {
        val sent = ArrayList<String>()
        live.sendObserver = { sent += String(it, Charsets.UTF_8) }
        live.emulator.write("\u001b[?1000h")
        compose.waitUntil(5_000) { synchronized(live.emulator.lock) { live.emulator.mouseTracking } != MouseTracking.NONE }
        val misses = ArrayList<String>()
        val foot = footDp()
        val under = listOf("pi-hole's tab" to pihole, "the Home chip" to chip, "the count tile" to countTile, "Overflow" to overflow)
        for ((name, matcher) in under) {
            for (dp in listOf(1f, 3f)) {
                val x = centreX(matcher)
                val heardBefore = heardSoFar()
                // The report is ESC [ M, the button, then the column and the row, each its number plus 33.
                val row = clickReport(sent, x, foot + dp)?.get(5)?.code
                val took = heardSoFar().filter { (target, n) -> n != heardBefore[target] }.keys
                val menu = menuOpen()
                if (row != 33 || took.isNotEmpty() || menu) {
                    misses += "$dp dp under $name: " + listOfNotNull(
                        if (row == null) "no click on the terminal" else if (row != 33) "the terminal's row byte $row" else null,
                        if (took.isNotEmpty()) "heard by ${took.joinToString()}" else null,
                        if (menu) "Overflow's menu opened" else null,
                    ).joinToString()
                }
                if (menu) {
                    compose.onNode(sessionRow).performClick()
                    compose.waitForIdle()
                }
            }
        }
        val grid = compose.onAllNodes(hasTestTag(TerminalTag))[0].fetchSemanticsNode().boundsInRoot
        val width = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().size.width
        val cols = synchronized(live.emulator.lock) { live.emulator.cols }
        for ((side, x, col) in listOf(Triple("the start", density, 0), Triple("the end", width - density, cols - 1))) {
            val at = clickReport(sent, x, grid.center.y / density)?.get(4)?.code
            if (at != col + 33) misses += "1 dp in from $side: " + if (at == null) "no click on the terminal" else "the terminal's column byte $at, not ${col + 33}"
        }
        live.emulator.write("\u001b[?1000l")
        compose.waitUntil(5_000) { synchronized(live.emulator.lock) { live.emulator.mouseTracking } == MouseTracking.NONE }
        live.sendObserver = null
        assertEquals("$where: in the padding the finger clicks the terminal's nearest cell and no header target", emptyList<String>(), misses)
        assertEquals("$where: the grid stands the 4 dp padding under the header's foot, as under the ribbon's", foot + TerminalPadding, grid.top / density, 0.5f)
    }

    @Test
    fun `with no status bar the header stands 44, and every target in it takes the band, under both densities`() {
        mount()
        assertTheBandIsTheTargets("Comfortable")
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = Density.COMPACT))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == Density.COMPACT }
        compose.waitForIdle()
        assertTheBandIsTheTargets("Compact")
    }

    @Test
    fun `under the header's foot the padding is the terminal's first row, under both densities`() {
        mount()
        assertThePaddingIsTheTerminals("Comfortable")
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = Density.COMPACT))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == Density.COMPACT }
        compose.waitForIdle()
        assertThePaddingIsTheTerminals("Compact")
    }

    @Test
    fun `over a body that takes no touch under the header, the count tile and Overflow end at its foot`() {
        tunnels = StageFixture.detachedTunnels().also { mount(it, ready = hasText("No tunnels on pi-hole.")) }
        val foot = footDp()
        val misses = ArrayList<String>()
        for ((name, matcher) in listOf("the count tile" to countTile, "Overflow" to overflow)) {
            for (dp in listOf(-1f, 1f, 3f)) {
                val before = heardSoFar()
                tap(centreX(matcher), foot + dp)
                val took = heardSoFar().filter { (target, n) -> n != before[target] }.keys
                val menu = menuOpen()
                if (dp < 0 && took.isEmpty() && !menu) misses += "${-dp} dp above the foot, $name: nothing answered"
                if (dp > 0 && (took.isNotEmpty() || menu)) {
                    misses += "$dp dp under the foot, $name: " + (took + if (menu) listOf("Overflow's menu opened") else emptyList()).joinToString()
                }
                if (menu) {
                    compose.onNode(sessionRow).performClick()
                    compose.waitForIdle()
                }
            }
        }
        assertEquals("over the Tunnels tab each fixed slot answers above the header's foot and nothing under it", emptyList<String>(), misses)
    }

    private companion object {
        /** The header with no status bar over it: the band's 4 dp and the row's 40. */
        const val Header = 44f

        /** The Stage's gap between the header's foot and the grid, the terminal's to touch (StageScreen's TerminalCanvas padding). */
        const val TerminalPadding = 4f

        /** Past the platform's 300 ms double-tap window, so two taps in one place are two clicks. */
        const val DoubleTapGapMs = 500L

        /** How a click in mouse mode reaches the shell, X10's encoding. */
        const val MouseReport = "\u001b[M"
    }
}

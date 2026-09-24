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
 * header's top and 1 dp above its foot lands on each, and 1 dp into the terminal, under the header
 * and the Stage's 4 dp padding, on the terminal.
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
        graph.close()
    }

    private fun mount() {
        compose.setContent {
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) {
                Box(Modifier.fillMaxSize()) {
                    StageScreen(graph.viewModel, live, heard, onOpenDrawer = {}, onOpenSessionSheet = { sessionSheets++ }, onEditHost = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(pihole).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
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

    /**
     * Each header target answers 1 dp under the header's top and 1 dp above the foot of its 44 dp,
     * the band's 4 and the row's 40; the terminal still stands its padding under the header's foot,
     * and 1 dp into it the finger is the terminal's, no header target's.
     */
    private fun assertTheBandIsTheTargets(where: String) {
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

        val sent = ArrayList<String>()
        live.sendObserver = { sent += String(it, Charsets.UTF_8) }
        live.emulator.write("\u001b[?1000h")
        compose.waitUntil(5_000) { synchronized(live.emulator.lock) { live.emulator.mouseTracking } != MouseTracking.NONE }
        val terminalTop = compose.onAllNodes(hasTestTag(TerminalTag))[0].fetchSemanticsNode().boundsInRoot.top / density
        assertEquals("$where: the terminal stands its 4 dp padding under the header's foot, as under the ribbon's", Header + TerminalPadding, terminalTop, 0.5f)
        val heardBefore = listOf(heard.activated.size, heard.chips.size, heard.switchers, heard.newTabs, sessionSheets)
        tap(compose.onAllNodes(isRoot())[0].fetchSemanticsNode().size.width / 2f, terminalTop + 1f)
        compose.waitUntil(5_000) { sent.any { it.startsWith("\u001b[M") } }
        val report = sent.first { it.startsWith("\u001b[M") }
        assertEquals("$where: the terminal's first row takes a tap 1 dp into it", 32 + 1, report[5].code)
        assertEquals("$where: no header target took it", heardBefore, listOf(heard.activated.size, heard.chips.size, heard.switchers, heard.newTabs, sessionSheets))
        live.emulator.write("\u001b[?1000l")
        compose.waitUntil(5_000) { synchronized(live.emulator.lock) { live.emulator.mouseTracking } == MouseTracking.NONE }
        live.sendObserver = null
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

    private companion object {
        /** The header with no status bar over it: the band's 4 dp and the row's 40. */
        const val Header = 44f

        /** The Stage's padding over the terminal canvas, outside the canvas's own touch (StageScreen's TerminalCanvas modifier). */
        const val TerminalPadding = 4f
    }
}

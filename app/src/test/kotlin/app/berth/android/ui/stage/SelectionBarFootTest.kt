package app.berth.android.ui.stage

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.tabs.TabActions
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.Density
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
import kotlin.math.roundToInt

/**
 * The selection bar's end at the foot (spec C18, `StageTools.kt`'s `LocalTargetEndsAtFoot`): the bar
 * stands where the header stood, its height and its reach above the row, and its ⋮ and × take
 * that reach as target as the header's Overflow does. Their 48 dp targets are taller than the row,
 * so they stand centred over it and would hang under its foot, over the terminal, which fills
 * everything under the bar and takes a tap there as its own (C2 l.297, D1 l.1161); in a header
 * they end at the foot instead. Over a terminal the canvas's own hit takes that strip before any
 * target could, so, as for the header's fixed slots in `HeaderBandTest`, the end is read over a body
 * that takes no touch there, a Tunnels tab's empty state, with its screen's text selected: 1 dp
 * above the bar's foot ⋮ opens its menu and × clears the selection, and 1 dp and 3 dp under it
 * neither does. In a window with no status bar and under a 24 dp one, under both densities, where
 * the targets would hang 4 dp (Compact under a status bar, 10).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class SelectionBarFootTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var tunnels: TerminalSession
    private val tools = StageTools()

    /** The compose view under test, for the insets Robolectric's window never sends it. */
    private var composeView: View? = null

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
        tunnels = StageFixture.detachedTunnels()
        tunnels.emulator.write("pi@pi-hole:~$ ss -tln\r\nState  Recv-Q Send-Q Local Address:Port\r\nLISTEN 0      128    127.0.0.1:8080\r\n")
    }

    @After
    fun tearDown() {
        tunnels.close()
        graph.close()
    }

    /** The Stage with the Tunnels tab on it and [tools] driving its bar, once the strip and the tab's empty state are up. */
    private fun mount() {
        compose.setContent {
            composeView = LocalView.current
            val theme by graph.viewModel.interfaceTheme.collectAsState()
            BerthTheme(theme) {
                Box(Modifier.fillMaxSize()) {
                    StageScreen(graph.viewModel, tunnels, object : TabActions {}, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {}, tools = tools)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodes(strip).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("No tunnels on pi-hole.")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Gives the window a status bar [dp] tall, dispatched to the compose view as the window would. */
    private fun statusBar(dp: Int) {
        val view = checkNotNull(composeView) { "mount first" }
        val px = (dp * compose.density.density).roundToInt()
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0)).build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private fun compact() {
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = Density.COMPACT))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == Density.COMPACT }
        compose.waitForIdle()
    }

    private val strip = hasContentDescription("Tabs, ", substring = true)
    private val copy = hasText("Copy") and hasClickAction()
    private val options = hasContentDescription("Selection options")
    private val clear = hasContentDescription("Clear selection")
    private val selectAll = hasText("Select all") and hasAnyAncestor(isPopup())

    private val density get() = compose.density.density

    /** A finger down and up at [x] px across and [y] dp down the window. */
    private fun tap(x: Float, y: Float) {
        compose.onAllNodes(isRoot())[0].performTouchInput { down(Offset(x, y * density)); up() }
        compose.waitForIdle()
    }

    private fun centreX(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.center.x

    private fun footDp(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.bottom / density

    /** The screen's text selected, as a long press would, so the bar stands in the header's place. */
    private fun select() {
        compose.runOnUiThread { synchronized(tunnels.emulator.lock) { tools.selection.selectAll(tunnels.emulator) } }
        compose.waitUntil(5_000) { compose.onAllNodes(clear).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun selected(): Boolean = compose.runOnIdle { tools.selection.active }

    private fun menuOpen() = compose.onAllNodes(selectAll).fetchSemanticsNodes().isNotEmpty()

    /** Shuts ⋮'s menu through Select all, which leaves the whole screen selected. */
    private fun closeMenu() {
        compose.onNode(selectAll).performClick()
        compose.waitForIdle()
    }

    /**
     * The bar stands the header's height, and its ⋮ and × answer 1 dp above its foot and nothing
     * 1 dp or 3 dp under it. Every tap is tried and every miss named. Ends with the selection cleared.
     */
    private fun assertTheBarEndsAtItsFoot(where: String) {
        val header = footDp(strip)
        select()
        val foot = footDp(copy)
        assertEquals("$where: the bar stands the header's height", header, foot, 0.5f)
        val misses = ArrayList<String>()
        for (dp in listOf(1f, 3f)) {
            tap(centreX(options), foot + dp)
            if (menuOpen()) {
                misses += "$dp dp under the foot, ⋮ opened its menu"
                closeMenu()
            }
            tap(centreX(clear), foot + dp)
            if (!selected()) {
                misses += "$dp dp under the foot, × cleared the selection"
                select()
            }
        }
        tap(centreX(options), foot - 1f)
        if (menuOpen()) closeMenu() else misses += "1 dp above the foot, ⋮ opened no menu"
        tap(centreX(clear), foot - 1f)
        if (selected()) misses += "1 dp above the foot, × left the selection"
        assertEquals("$where: over the Tunnels tab the bar's ⋮ and × answer above its foot and nothing under it", emptyList<String>(), misses)
    }

    @Test
    fun `with no status bar the selection bar's targets end at its foot, under both densities`() {
        mount()
        assertTheBarEndsAtItsFoot("Comfortable")
        compact()
        assertTheBarEndsAtItsFoot("Compact")
    }

    @Test
    fun `under a status bar the selection bar's targets end at its foot, under both densities`() {
        mount()
        statusBar(24)
        assertTheBarEndsAtItsFoot("Comfortable, 24 dp status bar")
        compact()
        assertTheBarEndsAtItsFoot("Compact, 24 dp status bar")
    }
}

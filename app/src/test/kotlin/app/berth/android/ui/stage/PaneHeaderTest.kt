package app.berth.android.ui.stage

import android.app.Application
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.toSize
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.screenshots.textLayout
import app.berth.android.session.PaneSide
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
import app.berth.domain.model.Density
import app.berth.ssh.SshSecurity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pane header's × (spec C23): "A pane header shows its × only where the header stands 40 dp;
 * where it is shorter the pane closes from Overflow's Close pane." On a tablet with no status bar
 * the header stands 40 under Comfortable and under Compact alike (no inset lends the strip Compact's
 * step): the focused pane's × stands at the pane's end whatever the title's length, its 48 dp target
 * takes the Stage's 4 dp padding under the header and no cell of the terminal's, and Overflow has no
 * Close pane. Under Compact beneath a status bar, and on a phone on its side beneath one, the header
 * is shorter: no ×, a finger under where it stood is the terminal's, and Overflow's Close pane,
 * directly under Unsplit, closes the focused pane and leaves its tab in the strip. Through the shell
 * as [AppRoot] mounts it, on the Stage fixture's detached tabs with pi-hole split beside homelab.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land-320dpi")
class PaneHeaderTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph

    /** The compose view under test, for the insets Robolectric's window never sends it. */
    private var composeView: View? = null

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        StageFixture.seed(graph)
        graph.sessions.setActive("s-homelab")
    }

    @After
    fun tearDown() {
        graph.close()
    }

    /** The shell with homelab on stage, then [split]. */
    private fun mountSplit() {
        compose.setContent {
            composeView = LocalView.current
            AppRoot(graph.viewModel)
        }
        compose.waitUntil(10_000) { graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("homelab, detached", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        split()
    }

    /** pi-hole in the right pane beside homelab, and its pane the focused one. */
    private fun split() {
        graph.sessions.placeInPane("s-pihole", PaneSide.RIGHT)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.focused == PaneSide.RIGHT }
        compose.waitUntil(10_000) { compose.onAllNodes(grid(PaneSide.RIGHT)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun focus(id: String, side: PaneSide) {
        graph.viewModel.setActive(id)
        compose.waitUntil(5_000) { graph.sessions.panes.value?.focused == side }
        compose.waitForIdle()
    }

    private fun setDensity(density: Density) {
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = density))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == density }
        compose.waitForIdle()
    }

    /** Gives the window a status bar [dp] tall, dispatched to the compose view as the window would. */
    private fun statusBar(dp: Int) {
        val view = checkNotNull(composeView) { "mountSplit first" }
        val px = (dp * compose.density.density).roundToInt()
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0)).build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private fun pane(side: PaneSide) = hasContentDescription(if (side == PaneSide.LEFT) ", left pane" else ", right pane", substring = true)

    private fun grid(side: PaneSide) = hasTestTag(TerminalTag) and hasAnyAncestor(pane(side))

    private val closeX = hasContentDescription("Close pane")

    private val more = hasContentDescription("More")

    private fun menuRow(text: String) = hasText(text) and hasAnyAncestor(isPopup())

    private fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private val dp get() = compose.density.density

    private fun bounds(matcher: SemanticsMatcher): Rect = compose.onAllNodes(matcher).onFirst().fetchSemanticsNode().boundsInRoot

    /** How many × the pane headers show. */
    private fun closes() = compose.onAllNodes(closeX).fetchSemanticsNodes().size

    /** The ×'s 48 dp box as laid out: the pane's clip cuts its top from the bounds a reader is given. */
    private fun closeBox(): Rect {
        val node = compose.onNode(closeX).fetchSemanticsNode()
        return Rect(node.positionInRoot, node.size.toSize())
    }

    /** A finger down and up at [x] and [y] px in the window, a double tap's window after the last. */
    private fun tap(x: Float, y: Float) {
        compose.mainClock.advanceTimeBy(DoubleTapGapMs)
        compose.onAllNodes(isRoot())[0].performTouchInput { down(Offset(x, y)); up() }
        compose.waitForIdle()
    }

    private fun openOverflow() {
        compose.onNode(more).performClick()
        compose.waitUntil(5_000) { shown(menuRow("Unsplit")) }
        compose.waitForIdle()
    }

    @Test
    fun `on the 40 dp header the focused pane's × stands at the pane's end whatever its title, under both densities`() {
        mountSplit()
        graph.sessions.rename("s-homelab", LongTitle)
        compose.waitUntil(5_000) { shown(hasContentDescription("$LongTitle, left pane")) }
        val misses = ArrayList<String>()
        for (density in listOf(Density.COMFORTABLE, Density.COMPACT)) {
            setDensity(density)
            for ((id, side) in listOf("s-pihole" to PaneSide.RIGHT, "s-homelab" to PaneSide.LEFT)) {
                focus(id, side)
                if (closes() != 1) {
                    misses += "$density, the $side pane: ${closes()} ×"
                    continue
                }
                val box = closeBox()
                val end = bounds(pane(side)).right - 4 * dp
                if (abs(box.right - end) > dp / 2) misses += "$density, the $side pane: the × ends ${(end - box.right) / dp} dp short of the pane's end"
                val name = if (side == PaneSide.LEFT) LongTitle else "pi-hole"
                val title = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and hasAnyAncestor(pane(side)), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .first { it.textLayout()?.layoutInput?.text?.text == name }
                    .boundsInRoot
                if (title.right > box.left + dp / 2) misses += "$density, the $side pane: the title runs ${(title.right - box.left) / dp} dp under the ×"
            }
        }
        assertEquals("the focused pane's × at the pane's end, the title ending before it", emptyList<String>(), misses)
        openOverflow()
        assertTrue("with the × on the header, Overflow has no Close pane", !shown(menuRow("Close pane")))
    }

    @Test
    fun `on the 40 dp header the × takes the Stage's 4 dp padding under the header and no cell of the terminal, under both densities`() {
        mountSplit()
        for (density in listOf(Density.COMFORTABLE, Density.COMPACT)) {
            setDensity(density)
            if (graph.sessions.panes.value == null) split()
            assertEquals("$density: the ×", 1, closes())
            val box = closeBox()
            val gridTop = bounds(grid(PaneSide.RIGHT)).top
            assertEquals("$density: the ×'s target ends where the grid starts, 4 dp under the header's foot", gridTop / dp, box.bottom / dp, 0.5f)
            tap(box.center.x, gridTop + dp)
            assertNotNull("$density: 1 dp into the grid under the × is the terminal's, and the panes stay", graph.sessions.panes.value)
            tap(box.center.x, gridTop - dp)
            compose.waitUntil(5_000) { graph.sessions.panes.value == null }
            assertEquals("$density: the × closed pi-hole's pane, homelab on stage", "s-homelab", graph.viewModel.activeTabId.value)
            assertTrue("$density: pi-hole's tab stays in the strip", graph.viewModel.tabs.value.any { it.id == "s-pihole" })
        }
    }

    /**
     * The header [where] is under 40: no ×, a finger 1 dp above the grid and 1 dp into it under where
     * the × stood is the terminal's, and Overflow's Close pane, directly under Unsplit, closes pi-hole's pane.
     */
    private fun assertClosesFromOverflow(where: String) {
        assertEquals("$where: no ×", 0, closes())
        val gridTop = bounds(grid(PaneSide.RIGHT)).top
        // The ×'s centre stood 28 dp in from the pane's end: the header's 4 dp and half the 48 dp target.
        val x = bounds(pane(PaneSide.RIGHT)).right - 28 * dp
        for (y in listOf(gridTop + dp, gridTop - dp)) {
            tap(x, y)
            assertNotNull("$where: ${(y - gridTop) / dp} dp from the grid's top under where the × stood is the terminal's, and the panes stay", graph.sessions.panes.value)
        }
        openOverflow()
        assertTrue("$where: Overflow offers Close pane", shown(menuRow("Close pane")))
        assertEquals("$where: Close pane directly under Unsplit", bounds(menuRow("Unsplit")).bottom / dp, bounds(menuRow("Close pane")).top / dp, 0.5f)
        compose.onNode(menuRow("Close pane")).performClick()
        compose.waitUntil(5_000) { graph.sessions.panes.value == null }
        assertEquals("$where: Close pane closed pi-hole's pane, homelab on stage", "s-homelab", graph.viewModel.activeTabId.value)
        assertTrue("$where: pi-hole's tab stays in the strip", graph.viewModel.tabs.value.any { it.id == "s-pihole" })
    }

    @Test
    fun `under Compact beneath a status bar the header has no ×, and the pane closes from Overflow`() {
        mountSplit()
        setDensity(Density.COMPACT)
        statusBar(24)
        assertClosesFromOverflow("Compact beneath a status bar")
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun `on a phone on its side beneath a status bar the header has no ×, under both densities, and the pane closes from Overflow`() {
        mountSplit()
        statusBar(24)
        assertClosesFromOverflow("a phone on its side beneath a status bar")
        split()
        setDensity(Density.COMPACT)
        assertClosesFromOverflow("a phone on its side beneath a status bar, Compact")

        split()
        statusBar(0)
        assertEquals("with no status bar the skin's 40 dp header, the × on it", 1, closes())
        openOverflow()
        assertTrue("and no Close pane in Overflow", !shown(menuRow("Close pane")))
    }

    private companion object {
        /** A title far longer than a pane is wide, so it ends in its ellipsis. */
        const val LongTitle = "homelab \u00B7 docker compose logs --follow --tail 200 caddy gitea postgres redis minio"

        /** Past the platform's 300 ms double-tap window, so two taps in one place are two clicks. */
        const val DoubleTapGapMs = 500L
    }
}

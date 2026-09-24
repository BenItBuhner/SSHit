package app.berth.android.ui.tabs

import android.app.Application
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
import app.berth.android.ui.AppRoot
import app.berth.android.ui.a11y.TerminalTag
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
import kotlin.math.roundToInt

/**
 * The shell's header on a phone on its side (spec C23 under l.102 and l.351). With no status bar no
 * inset lends the short strip its 16 dp reach, so the shell keeps the skin's 40 dp row with the 4 dp
 * band over it, the header 44 as upright, and every target in it answers across the 44 through the
 * shell's own actions: a tab, a group chip, the count tile and Overflow, 1 dp under the header's top
 * and 1 dp above its foot, under both densities. Under a phone's status bar the short strip stands.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w914dp-h411dp-land-420dpi")
class LandscapeHeaderTest {
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

    private fun mountShell() {
        compose.setContent {
            composeView = LocalView.current
            AppRoot(graph.viewModel)
        }
        compose.waitUntil(10_000) { graph.viewModel.activeTabId.value == "s-homelab" }
        compose.waitUntil(10_000) { compose.onAllNodes(pihole).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(TerminalTag)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** Gives the window a status bar [dp] tall, dispatched to the compose view as the window would. */
    private fun statusBar(dp: Int) {
        val view = checkNotNull(composeView) { "mountShell first" }
        val px = (dp * compose.density.density).roundToInt()
        val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px, 0, 0)).build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view, insets) }
        compose.waitForIdle()
    }

    private val pihole = hasContentDescription("pi-hole, detached", substring = true)
    private val chip = hasContentDescription("Group Home, ", substring = true)
    private val countTile = hasContentDescription("tabs, open the tab switcher", substring = true)
    private val overflow = hasContentDescription("More")
    private val switcherDone = hasText("Done")
    private fun menuRow(text: String) = hasText(text) and hasAnyAncestor(isPopup())

    private val density get() = compose.density.density

    /** A finger down and up at [x] px across and [y] dp down the window. */
    private fun tap(x: Float, y: Float) {
        compose.onAllNodes(isRoot())[0].performTouchInput { down(Offset(x, y * density)); up() }
        compose.waitForIdle()
    }

    private fun centreX(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher)[0].fetchSemanticsNode().boundsInRoot.center.x

    private fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun homeCollapsed() = graph.viewModel.workspaces.value.first { it.id == Workspace.DEFAULT_ID }.collapsed

    /** Waits a while for what a tap should have done, leaving the assert after it to say what did not. */
    private fun settle(done: () -> Boolean) {
        runCatching { compose.waitUntil(2_000, done) }
    }

    private fun gridTopDp() = compose.onAllNodes(hasTestTag(TerminalTag))[0].fetchSemanticsNode().boundsInRoot.top / density

    /**
     * Each target answers 1 dp under the header's top and 1 dp above the foot of its 44 dp. Every tap
     * is tried and every miss named, so a header short of 44 shows each target it leaves short; each
     * starts from the shell at rest, homelab on stage, Home laid out whole, no sheet or menu up.
     */
    private fun assertTheHeaderIs44(where: String) {
        val misses = ArrayList<String>()
        val ys = listOf(1f, Header - 1f)
        for (y in ys) {
            tap(centreX(pihole), y)
            settle { graph.viewModel.activeTabId.value == "s-pihole" }
            if (graph.viewModel.activeTabId.value != "s-pihole") misses += "pi-hole's tab at $y dp"
            graph.viewModel.setActive("s-homelab")
            compose.waitUntil(5_000) { graph.viewModel.activeTabId.value == "s-homelab" }
            compose.waitForIdle()
        }
        for (y in ys) {
            tap(centreX(chip), y)
            settle { homeCollapsed() }
            if (!homeCollapsed()) misses += "the Home chip at $y dp"
            graph.viewModel.setWorkspaceCollapsed(Workspace.DEFAULT_ID, false)
            compose.waitUntil(5_000) { !homeCollapsed() }
            compose.waitForIdle()
        }
        for (y in ys) {
            tap(centreX(countTile), y)
            settle { shown(switcherDone) }
            if (shown(switcherDone)) {
                compose.onNode(switcherDone).performClick()
                compose.waitUntil(5_000) { !shown(switcherDone) }
            } else {
                misses += "the count tile at $y dp"
            }
        }
        for (y in ys) {
            tap(centreX(overflow), y)
            settle { shown(menuRow("Session")) }
            if (shown(menuRow("Session"))) {
                compose.onNode(if (shown(menuRow("Hide Deck"))) menuRow("Hide Deck") else menuRow("Show Deck")).performClick()
                compose.waitUntil(5_000) { !shown(menuRow("Session")) }
            } else {
                misses += "Overflow at $y dp"
            }
        }
        assertEquals("$where: every target answers 1 dp under the header's top and 1 dp above its 44 dp foot", emptyList<String>(), misses)
        assertEquals("$where: the grid stands under the 44 dp header and the Stage's 4 dp padding", Header + 4f, gridTopDp(), 0.5f)
    }

    @Test
    fun `with no status bar the phone on its side keeps the 44 dp header, every target across it, under both densities`() {
        mountShell()
        assertTheHeaderIs44("Comfortable")
        graph.viewModel.setInterfaceTheme(graph.viewModel.interfaceTheme.value.copy(density = Density.COMPACT))
        compose.waitUntil(5_000) { graph.viewModel.interfaceTheme.value.density == Density.COMPACT }
        compose.waitForIdle()
        assertTheHeaderIs44("Compact")
    }

    @Test
    fun `under a phone's status bar the short strip stands, its reach the inset's`() {
        mountShell()
        statusBar(24)
        assertEquals("the grid under the status bar, the 32 dp row and the padding", 24f + 32f + 4f, gridTopDp(), 0.5f)
        statusBar(0)
        assertEquals("and with none the 44 dp header again", Header + 4f, gridTopDp(), 0.5f)
    }

    private companion object {
        /** The header with no status bar over it: the band's 4 dp and the skin's 40 dp row. */
        const val Header = 44f
    }
}

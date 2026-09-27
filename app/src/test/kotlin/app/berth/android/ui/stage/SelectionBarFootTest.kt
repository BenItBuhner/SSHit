package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import app.berth.terminal.CellPos
import app.berth.terminal.SelectionMode
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
 * The selection bar's end at the foot (spec C18, C2 l.297). The bar stands where the header was, its
 * band and its row, and its icon actions, Selection options and Clear selection, take the header's
 * reach above the row as the header's fixed slots do, their targets ending at the bar's foot rather
 * than hanging the 4 dp below it that centring would give them. On the Stage the terminal's canvas
 * takes that strip by its own hit before any reach could, so the end is read over a body that takes
 * no touch, as the fixed slots' is over a Tunnels tab (HeaderBandTest): each action answers 1 dp
 * above the foot, and 1 dp and 3 dp under it nothing does.
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
    private lateinit var live: TerminalSession
    private val tools = StageTools()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        StageFixture.seed(graph)
        live = StageFixture.liveHomelab()
    }

    @After
    fun tearDown() {
        live.close()
        graph.close()
    }

    private val options = hasContentDescription("Selection options")
    private val clear = hasContentDescription("Clear selection")
    private val selectAll = hasText("Select all") and hasAnyAncestor(isPopup())

    private val density get() = compose.density.density

    private fun select() {
        synchronized(live.emulator.lock) { tools.selection.start(live.emulator, CellPos(0, 0), SelectionMode.CELL) }
        compose.waitUntil(5_000) { compose.onAllNodes(clear).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    /** The bar over a body that takes no touch, with a cell selected, the header it stands in for never shown. */
    private fun mount() {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Column(Modifier.fillMaxSize()) {
                    StageToolbar(tools, live) { Box(Modifier.fillMaxWidth().height(44.dp)) }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        select()
    }

    private fun centreX(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.center.x

    /** The bar's foot, in px down the window: where Copy, which fills the bar's height, ends. */
    private fun foot(): Float = compose.onNode(hasText("Copy")).fetchSemanticsNode().boundsInRoot.bottom

    /** A finger down and up at [x] px across and [dp] dp under the bar's foot (above it where negative). */
    private fun tap(x: Float, dp: Float) {
        val y = foot() + dp * density
        compose.onAllNodes(isRoot())[0].performTouchInput { down(Offset(x, y)); up() }
        compose.waitForIdle()
    }

    @Test
    fun `over a body that takes no touch, the selection bar's icon actions end at its foot`() {
        mount()
        assertEquals("the bar stands the header's band and row", 44f, foot() / density, 0.5f)
        val misses = ArrayList<String>()
        for (dp in listOf(-1f, 1f, 3f)) {
            tap(centreX(options), dp)
            val menu = compose.onAllNodes(selectAll).fetchSemanticsNodes().isNotEmpty()
            if (dp < 0 && !menu) misses += "${-dp} dp above the foot, Selection options: nothing answered"
            if (dp > 0 && menu) misses += "$dp dp under the foot, Selection options: its menu opened"
            if (menu) compose.onNode(selectAll).performClick()
            compose.waitForIdle()
        }
        for (dp in listOf(-1f, 1f, 3f)) {
            tap(centreX(clear), dp)
            val cleared = !tools.selection.active
            if (dp < 0 && !cleared) misses += "${-dp} dp above the foot, Clear selection: nothing answered"
            if (dp > 0 && cleared) misses += "$dp dp under the foot, Clear selection: the selection cleared"
            if (cleared) select()
        }
        assertEquals("each icon action answers above the bar's foot and nothing under it", emptyList<String>(), misses)
    }
}

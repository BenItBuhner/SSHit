package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.toSize
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The pane header's × (spec C23): on a tablet the focused pane's × stands at the pane's end whatever
 * the title's length, under Comfortable and Compact alike, and a title too long for the room ends in
 * its ellipsis before it. Through the shell as [AppRoot] mounts it, on the Stage fixture's detached
 * tabs with pi-hole split beside homelab.
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
        compose.setContent { AppRoot(graph.viewModel) }
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

    private companion object {
        /** A title far longer than a pane is wide, so it ends in its ellipsis. */
        const val LongTitle = "homelab \u00B7 docker compose logs --follow --tail 200 caddy gitea postgres redis minio"
    }
}

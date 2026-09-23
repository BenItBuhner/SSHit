package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.keyboard.LocalPaneActions
import app.berth.android.ui.keyboard.PaneActions
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
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
 * The Deck's app actions act on the Stage as it is when the key is pressed (spec C5): a key given
 * Split splits through the same pane actions as the Overflow's Split and Ctrl+Shift+D, the ones the
 * Stage has now rather than the ones it had when the tab first came on stage; a one-pane Stage says
 * why nothing split; and Next layer steps from the layer on show.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DeckSplitKeyTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sessions = ArrayList<TerminalSession>()

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(context)
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        graph.close()
    }

    private fun deck(vararg layers: DeckLayer) = runBlocking { graph.settings.setDeckLayout(DeckLayout(layers = layers.toList())) }

    private fun key(action: DeckAppAction, display: String) = DeckKey(tap = DeckAction.App(action), display = display)

    private fun stage(session: TerminalSession, panes: () -> PaneActions) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                CompositionLocalProvider(LocalPaneActions provides panes()) {
                    Box(Modifier.fillMaxSize()) {
                        val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                        StageScreen(graph.viewModel, session, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                    }
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a Deck key given Split splits through the pane actions the Stage has when it is pressed`() {
        StageFixture.seed(graph)
        deck(DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), key(DeckAppAction.SPLIT, "Split"), DeckKey(nub = true))))
        val calls = ArrayList<String>()
        // One Stage, as the pane layer provides it: Split while one tab has it, Unsplit once two do.
        var split by mutableStateOf(false)
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live) { if (split) PaneActions(split = { calls += "unsplit" }) else PaneActions(split = { calls += "split"; split = true }) }

        compose.onNodeWithContentDescription("Split").performClick()
        compose.waitForIdle()
        assertEquals(listOf("split"), calls)

        // The tab stayed on stage while the panes came; the key now reaches the way back to one.
        compose.onNodeWithContentDescription("Split").performClick()
        compose.waitForIdle()
        assertEquals(listOf("split", "unsplit"), calls)
    }

    @Test
    fun `on a Stage with no room for panes the key says so instead of doing nothing`() {
        StageFixture.seed(graph)
        deck(DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), key(DeckAppAction.SPLIT, "Split"), DeckKey(nub = true))))
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live) { PaneActions.None }
        assertEquals(0, compose.onAllNodes(hasText(SPLIT_NEEDS_WIDTH)).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("Split").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(SPLIT_NEEDS_WIDTH)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a Next layer key steps from the layer on show, not the one the tab opened on`() {
        StageFixture.seed(graph)
        val next = key(DeckAppAction.NEXT_LAYER, "Next")
        deck(
            DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), next, DeckKey(nub = true))),
            DeckLayer("Symbols", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.TAB)), next)),
            DeckLayer("Nav", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)), next)),
        )
        val live = StageFixture.liveHomelab().also { sessions += it }
        stage(live) { PaneActions.None }
        fun layerShown(name: String) = compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Layer"))
                .and(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name)),
        ).fetchSemanticsNodes().isNotEmpty()
        assertEquals(true, layerShown("Base"))

        compose.onNodeWithContentDescription("Next").performClick()
        compose.waitUntil(5_000) { layerShown("Symbols") }
        compose.onNodeWithContentDescription("Next").performClick()
        compose.waitUntil(5_000) { layerShown("Nav") }
    }
}

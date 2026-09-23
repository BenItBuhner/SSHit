package app.berth.android.ui.stage

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.StageFixture
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.TerminalSession
import app.berth.android.ui.tabs.ShellTabActions
import app.berth.android.ui.tabs.TabUiState
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The layer key's swipe up (spec C4): the layer picker, a menu of the row's layers with the one on
 * show selected, where a choice shows that layer and closes it, and the tab's Predictive text
 * switch (product vision, the IME), which leaves it open. A tap still steps to the next layer; a
 * reader reaches the picker from the key's actions; a second row's picker leaves out the layer the
 * first row shows; and a Deck with no tab to switch has no switch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DeckLayerPickerTest {
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

    private val threeLayers = listOf(
        DeckLayer("Base", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.ESC)), DeckKey(nub = true))),
        DeckLayer("Symbols", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.TAB)))),
        DeckLayer("Nav", listOf(DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)))),
    )

    private fun stage(): TerminalSession {
        StageFixture.seed(graph)
        runBlocking { graph.settings.setDeckLayout(DeckLayout(layers = threeLayers)) }
        val live = StageFixture.liveHomelab().also { sessions += it }
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) {
                    val actions = remember { ShellTabActions(graph.viewModel, TabUiState(), onActivated = {}) }
                    StageScreen(graph.viewModel, live, actions, onOpenDrawer = {}, onOpenSessionSheet = {}, onEditHost = {})
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(layerKey()).fetchSemanticsNodes().isNotEmpty() }
        return live
    }

    private fun layerKey() = hasTestTag(DeckKeyTag) and hasContentDescription("Layer")

    private fun layerKeyIn(name: String) = compose.onNode(layerKey() and hasStateDescription(name))

    private fun SemanticsNodeInteraction.swipeUp() = performTouchInput {
        down(center)
        moveBy(Offset(0f, -40.dp.toPx()))
        up()
    }

    private fun inPicker(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isPopup()))

    private fun pickerLines(text: String) = compose.onAllNodes(hasText(text) and hasAnyAncestor(isPopup())).fetchSemanticsNodes().size

    private fun awaitPicker(open: Boolean) = compose.waitUntil(5_000) { (pickerLines("Symbols") > 0) == open }

    @Test
    fun `the layer key's swipe up opens the picker, whose choice shows that layer and closes it`() {
        stage()
        layerKeyIn("Base").swipeUp()
        awaitPicker(open = true)
        // The swipe opened the picker and stepped nowhere: the row still shows Base, the picker's selection.
        layerKeyIn("Base").assertExists()
        inPicker("Base").assertIsSelected()
        inPicker("Symbols").assertIsNotSelected()
        inPicker("Nav").assertIsNotSelected()

        inPicker("Nav").performClick()
        awaitPicker(open = false)
        layerKeyIn("Nav").assertExists()

        // Opened again, the picker has the row's new layer selected.
        layerKeyIn("Nav").swipeUp()
        awaitPicker(open = true)
        inPicker("Nav").assertIsSelected()
        inPicker("Base").assertIsNotSelected()
    }

    @Test
    fun `a tap on the layer key still steps to the next layer and opens nothing`() {
        stage()
        layerKeyIn("Base").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(layerKey() and hasStateDescription("Symbols")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        assertEquals("no picker on a tap", 0, pickerLines("Nav"))
    }

    @Test
    fun `the picker's Predictive text switch turns it on and off for the tab on stage and leaves the picker open`() {
        val live = stage()
        assertFalse(live.id in graph.viewModel.predictiveTextTabIds.value)
        layerKeyIn("Base").swipeUp()
        awaitPicker(open = true)
        inPicker("Predictive text").assertIsOff()

        inPicker("Predictive text").performClick()
        compose.waitUntil(5_000) { live.id in graph.viewModel.predictiveTextTabIds.value }
        inPicker("Predictive text").assertIsOn()
        inPicker("Base").assertIsSelected()

        inPicker("Predictive text").performClick()
        compose.waitUntil(5_000) { live.id !in graph.viewModel.predictiveTextTabIds.value }
        inPicker("Predictive text").assertIsOff()
    }

    @Test
    fun `a reader opens the picker from the layer key's actions`() {
        stage()
        val key = layerKeyIn("Base")
        val actions = key.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        val layers = actions.single { it.label == "Layers" }
        compose.runOnIdle { layers.action() }
        awaitPicker(open = true)
        inPicker("Base").assertIsSelected()
    }

    @Test
    fun `a second row's picker leaves out the first row's layer, and a Deck with no tab to switch has no switch`() {
        var first by mutableIntStateOf(0)
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
                Deck(layout = DeckLayout(rows = 2, layers = threeLayers), layerIndex = first, onLayerIndexChange = { first = it }, input = input)
            }
        }
        compose.waitForIdle()
        // The second row starts on the layer after the first's.
        layerKeyIn("Symbols").swipeUp()
        compose.waitUntil(5_000) { pickerLines("Nav") > 0 }
        assertEquals("the first row's Base is not the second row's to take", 0, pickerLines("Base"))
        inPicker("Symbols").assertIsSelected()
        assertEquals("no tab, no switch", 0, pickerLines("Predictive text"))

        inPicker("Nav").performClick()
        compose.waitUntil(5_000) { pickerLines("Nav") == 0 }
        layerKeyIn("Nav").assertExists()
        layerKeyIn("Base").assertExists()
        assertEquals("the first row kept its layer", 0, first)
        assertTrue(compose.onAllNodes(layerKey()).fetchSemanticsNodes().size == 2)
    }
}

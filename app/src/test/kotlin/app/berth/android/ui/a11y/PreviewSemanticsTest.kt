package app.berth.android.ui.a11y

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.test.core.app.ApplicationProvider
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.TestGraph
import app.berth.android.ui.deck.DeckEditorScreen
import app.berth.android.ui.theme.BerthTheme
import app.berth.android.ui.themes.AppearanceScreen
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
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
 * A preview is not a control. Spec A11 says nothing of previews, so what in one opens or sends
 * nothing is decoration to a reader, the preview's description standing for it. Under the Deck
 * editor's preview a reader can act on a slot, which selects it, and on a layer key, which steps
 * the row's layer, and on nothing else: not the Grip, not the Deck editor key a one-layer draft
 * ends in, not the keys of a second row the editor does not edit. The Appearance screen's preview
 * acts on nothing, so it is its one description with nothing under it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class PreviewSemanticsTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private lateinit var graph: TestGraph

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        graph.close()
    }

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    /**
     * The node that reads [description] in the tree a reader walks: the merged one, where what a
     * node clears is gone from under it and what it merges is part of it, as TalkBack hears it.
     */
    private fun preview(description: String): SemanticsNode =
        compose.onNode(hasContentDescription(description)).fetchSemanticsNode()

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children.flatMap { listOf(it) + it.descendants() }

    /** What a reader hears a node as: its description, else its text, else its role. */
    private fun SemanticsNode.heard(): String =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString()
            ?: config.getOrNull(SemanticsProperties.Role)?.toString()
            ?: "a node with nothing to say"

    private fun SemanticsNode.isControl(): Boolean =
        config.contains(SemanticsActions.OnClick) || config.contains(SemanticsProperties.Role)

    /** The Deck editor over [layout], once its preview's first slot is up. */
    private fun mountEditor(layout: DeckLayout) {
        graph.viewModel.setDeckLayout(layout)
        compose.waitUntil(5_000) { graph.viewModel.deckLayout.value == layout }
        themed { DeckEditorScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Slot 1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun assertOnlySlotsAndLayerKeysAct(where: String) {
        val others = preview("Deck preview").descendants().filter { it.isControl() }.map { it.heard() }.filterNot { it.startsWith("Slot ") || it == "Layer" }
        assertEquals("$where: under the Deck preview only slots and layer keys are controls", emptyList<String>(), others)
    }

    @Test
    fun `under the Deck editor's preview the Grip is no control`() {
        mountEditor(DeckLayout.default())
        assertOnlySlotsAndLayerKeysAct("the stock layout")
    }

    @Test
    fun `under the Deck editor's preview a one-layer draft's Deck editor key is no control`() {
        val stock = DeckLayout.default()
        mountEditor(stock.copy(layers = stock.layers.take(1)))
        assertOnlySlotsAndLayerKeysAct("one layer")
    }

    @Test
    fun `under the Deck editor's preview the second row's keys are no controls, its layer key still one`() {
        mountEditor(DeckLayout.default().copy(rows = 2))
        assertOnlySlotsAndLayerKeysAct("two rows")
        val layerKeys = preview("Deck preview").descendants().count { it.isControl() && it.heard() == "Layer" }
        assertEquals("both rows keep their layer key", 2, layerKeys)
    }

    @Test
    fun `the Appearance screen's preview is its one description, with nothing under it to act on`() {
        themed { AppearanceScreen(graph.viewModel, onBack = {}) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Interface preview"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val mock = preview("Interface preview")
        assertEquals("nothing under the Interface preview speaks", emptyList<String>(), mock.descendants().map { it.heard() })
        assertEquals("and the preview itself is no control", false, mock.isControl())
    }
}

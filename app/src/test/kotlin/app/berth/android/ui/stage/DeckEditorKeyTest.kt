package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.ui.keyboard.compactForHardwareKeyboard
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The key that trails a one-layer Deck (spec C4's layer key has nothing to cycle there): the Deck
 * editor outright, one button with one activation and no layer actions; disabled where there is no
 * editor to open; and the layer key still on a Deck of more layers than one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeckEditorKeyTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var opened = 0

    private fun mount(layout: DeckLayout, onOpenDeckEditor: (() -> Unit)?) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
                // Index 1: the whole Deck's second layer, which a one-layer Deck holds to its one.
                Deck(layout = layout, layerIndex = 1, onLayerIndexChange = {}, input = input, onOpenDeckEditor = onOpenDeckEditor)
            }
        }
        compose.waitForIdle()
    }

    private fun editorKey() = hasTestTag(DeckKeyTag) and hasContentDescription("Deck editor")
    private fun layerKey() = hasTestTag(DeckKeyTag) and hasContentDescription("Layer")

    @Test
    fun `the compact Deck ends in the Deck editor key, a button whose tap opens the editor and that announces no layer`() {
        mount(DeckLayout.default().compactForHardwareKeyboard(), onOpenDeckEditor = { opened++ })
        assertTrue("no layer key on a one-layer Deck", compose.onAllNodes(layerKey()).fetchSemanticsNodes().isEmpty())
        val key = compose.onNode(editorKey())
        key.assertIsEnabled()
        val node = key.fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        assertNull("no state to announce: there is no layer name to be in", node.config.getOrNull(SemanticsProperties.StateDescription))
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals("no layer actions", emptyList<String>(), actions)
        key.performClick()
        compose.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun `with no editor to open the key stands disabled, so the row still measures as the Stage's`() {
        mount(DeckLayout.default().compactForHardwareKeyboard(), onOpenDeckEditor = null)
        val key = compose.onNode(editorKey())
        key.assertIsNotEnabled()
        assertEquals("the layer key's target: half the gap, its 40 dp face and the 8 dp trailing edge", 50f, key.fetchSemanticsNode().size.width / compose.density.density, 0.5f)
    }

    @Test
    fun `a Deck of more layers than one keeps its layer key, in the shown layer's state`() {
        mount(DeckLayout.default(), onOpenDeckEditor = { opened++ })
        assertTrue("no editor key with layers to cycle", compose.onAllNodes(editorKey()).fetchSemanticsNodes().isEmpty())
        val node = compose.onNode(layerKey()).fetchSemanticsNode()
        assertEquals("Symbols", node.config.getOrNull(SemanticsProperties.StateDescription))
        val actions = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertEquals(listOf("Previous layer", "Layers", "Deck editor"), actions)
    }
}

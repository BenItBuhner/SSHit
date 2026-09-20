package app.berth.android.ui.keyboard

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckModifier
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-row Deck a hardware keyboard's user opens from the folded strip (spec C4, Settings › Hardware keyboard › Compact Deck when expanded). */
class HardwareKeyboardDeckTest {
    private fun modifier(m: DeckModifier) = DeckAction.Modifier(m)
    private fun app(a: DeckAppAction) = DeckAction.App(a)

    @Test
    fun `the stock layout keeps its three modifiers, in order of first appearance, and gains Paste`() {
        val compact = DeckLayout.default().compactForHardwareKeyboard()
        assertEquals(1, compact.rows)
        assertEquals(listOf(HARDWARE_DECK_LAYER), compact.layers.map { it.name })
        val keys = compact.layers.single().keys
        assertEquals(
            listOf(modifier(DeckModifier.CTRL), modifier(DeckModifier.ALT), modifier(DeckModifier.SHIFT), app(DeckAppAction.PASTE)),
            keys.map { it.tap },
        )
        // The user's own alternates ride along: the stock Ctrl key still swipes up to ^C.
        assertEquals(DeckAction.Combo("CTRL c"), keys.first().up)
        assertEquals("Ctrl", keys.first().label)
        // The height and reach are the user's; only the rows and layers change.
        assertEquals(DeckLayout.default().heightDp, compact.heightDp)
        assertEquals(DeckLayout.default().reach, compact.reach)
    }

    @Test
    fun `each modifier and action once, letters and keys dropped, layer switching and hide keyboard gone`() {
        val layout = DeckLayout(
            rows = 2,
            layers = listOf(
                DeckLayer(
                    "One",
                    listOf(
                        DeckKey(tap = modifier(DeckModifier.CTRL)),
                        DeckKey(tap = DeckAction.Text("-")),
                        DeckKey(tap = app(DeckAppAction.PASTE), display = "Paste"),
                        DeckKey(tap = app(DeckAppAction.NEXT_LAYER)),
                        DeckKey(tap = app(DeckAppAction.HIDE_KEYBOARD)),
                        DeckKey(nub = true),
                    ),
                ),
                DeckLayer(
                    "Two",
                    listOf(
                        DeckKey(tap = modifier(DeckModifier.CTRL), display = "C-"),
                        DeckKey(tap = DeckAction.Key(DeckKeyCode.HOME)),
                        DeckKey(tap = app(DeckAppAction.PASTE)),
                        DeckKey(tap = app(DeckAppAction.OPEN_SESSION_SHEET)),
                        DeckKey(tap = modifier(DeckModifier.ALT)),
                        DeckKey(snippets = true),
                    ),
                ),
            ),
        )
        val keys = layout.compactForHardwareKeyboard().layers.single().keys
        assertEquals(
            listOf(modifier(DeckModifier.CTRL), modifier(DeckModifier.ALT), app(DeckAppAction.PASTE), app(DeckAppAction.OPEN_SESSION_SHEET)),
            keys.map { it.tap },
        )
        // The first of a repeated key is the one kept, with its display.
        assertEquals(null, keys[0].display)
        assertEquals("Paste", keys[2].display)
    }

    @Test
    fun `a layout with no modifiers or actions is a Paste key alone`() {
        val layout = DeckLayout(layers = listOf(DeckLayer("Letters", listOf(DeckKey(tap = DeckAction.Text("a")), DeckKey(nub = true)))))
        val keys = layout.compactForHardwareKeyboard().layers.single().keys
        assertEquals(listOf(app(DeckAppAction.PASTE)), keys.map { it.tap })
        assertEquals("Paste", keys.single().label)
    }
}

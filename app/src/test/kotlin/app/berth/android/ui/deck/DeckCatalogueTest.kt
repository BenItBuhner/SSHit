package app.berth.android.ui.deck

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckCatalogueTest {
    @Test
    fun `catalogue covers every key, modifier and app action exactly once`() {
        assertEquals(DeckKeyCode.entries.size, DeckCatalogue.keys.size)
        assertEquals(DeckKeyCode.entries.toSet(), DeckCatalogue.keys.map { (it.action as DeckAction.Key).key }.toSet())
        assertEquals(DeckModifier.entries.toSet(), DeckCatalogue.modifiers.map { (it.action as DeckAction.Modifier).modifier }.toSet())
        assertEquals(DeckAppAction.entries.toSet(), DeckCatalogue.app.map { (it.action as DeckAction.App).action }.toSet())
        assertEquals(DeckCatalogue.searchable.size, DeckCatalogue.searchable.distinct().size)
    }

    @Test
    fun `fn strip holds F1 to F12 in order`() {
        assertEquals((1..12).map { DeckKeyCode.valueOf("F$it") }, DeckCatalogue.FN_STRIP.strip)
    }

    @Test
    fun `search matches titles, captions and key names, and is empty for an empty query`() {
        assertTrue(DeckCatalogue.search("").isEmpty())
        assertTrue(DeckCatalogue.search("   ").isEmpty())
        assertEquals(listOf(DeckAction.Key(DeckKeyCode.ESC)), DeckCatalogue.search("escape").map { it.action })
        assertTrue(DeckCatalogue.search("pgup").any { it.action == DeckAction.Key(DeckKeyCode.PGUP) })
        assertTrue(DeckCatalogue.search("layer").any { it.action == DeckAction.App(DeckAppAction.NEXT_LAYER) })
        assertTrue(DeckCatalogue.search("clear").any { it.action == null })
        assertTrue(DeckCatalogue.search("xyzzy").isEmpty())
    }

    @Test
    fun `the chip to open on follows the bound action`() {
        assertEquals(ActionKind.KEY, ActionKind.of(null))
        assertEquals(ActionKind.TEXT, ActionKind.of(DeckAction.Text("-")))
        assertEquals(ActionKind.COMBO, ActionKind.of(DeckAction.Combo("CTRL c")))
        assertEquals(ActionKind.MACRO, ActionKind.of(DeckAction.Macro("PREFIX d")))
        assertEquals(ActionKind.SNIPPET, ActionKind.of(DeckAction.Snippet("restart")))
        assertEquals(ActionKind.SPECIAL, ActionKind.of(DeckCatalogue.FN_STRIP))
    }

    @Test
    fun `combos are composed in CTRL ALT SHIFT order from a character or a key name`() {
        assertEquals(DeckAction.Combo("CTRL c"), DeckCatalogue.combo(setOf(DeckModifier.CTRL), "c"))
        assertEquals(DeckAction.Combo("CTRL ALT TAB"), DeckCatalogue.combo(setOf(DeckModifier.ALT, DeckModifier.CTRL), "tab"))
        assertEquals(DeckAction.Combo("SHIFT F5"), DeckCatalogue.combo(setOf(DeckModifier.SHIFT), " f5 "))
        assertEquals(DeckAction.Combo("\u00E9"), DeckCatalogue.combo(emptySet(), "\u00E9"))
        assertNull(DeckCatalogue.combo(setOf(DeckModifier.CTRL), ""))
        assertNull(DeckCatalogue.combo(setOf(DeckModifier.CTRL), "ab"))
        assertNull(DeckCatalogue.combo(setOf(DeckModifier.CTRL), "notakey"))
    }
}

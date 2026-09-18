package app.berth.android.ui.stage

import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckAppAction
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckLayer
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.DeckModifier
import app.berth.terminal.Mod
import app.berth.terminal.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StageInputTest {
    @Test
    fun `latch cycles none, one-shot, locked, none`() {
        val latch = ModifierLatch()
        latch.tap(DeckModifier.CTRL)
        assertEquals(LatchState.ONE_SHOT, latch.ctrl)
        latch.tap(DeckModifier.CTRL)
        assertEquals(LatchState.LOCKED, latch.ctrl)
        latch.tap(DeckModifier.CTRL)
        assertEquals(LatchState.NONE, latch.ctrl)
    }

    @Test
    fun `one-shot clears after use and locked survives`() {
        val latch = ModifierLatch()
        latch.tap(DeckModifier.CTRL)
        latch.tap(DeckModifier.ALT)
        latch.tap(DeckModifier.ALT)
        assertEquals(Mod.CTRL or Mod.ALT, latch.bits)
        latch.consumeOneShots()
        assertEquals(LatchState.NONE, latch.ctrl)
        assertEquals(LatchState.LOCKED, latch.alt)
        assertEquals(Mod.ALT, latch.bits)
        latch.clear()
        assertFalse(latch.isActive)
    }

    @Test
    fun `deck key codes map onto terminal keys and arrows repeat`() {
        assertEquals(TerminalKey.ESCAPE, DeckKeyCode.ESC.toTerminalKey())
        assertEquals(TerminalKey.PAGE_DOWN, DeckKeyCode.PGDN.toTerminalKey())
        assertEquals(TerminalKey.F12, DeckKeyCode.F12.toTerminalKey())
        for (code in DeckKeyCode.entries) code.toTerminalKey()
        assertTrue(DeckKeyCode.LEFT.repeats)
        assertTrue(DeckKeyCode.BACKSPACE.repeats)
        assertFalse(DeckKeyCode.ESC.repeats)
        assertFalse(DeckKeyCode.F1.repeats)
    }

    @Test
    fun `the default layout renders without the snippets layer until snippets exist`() {
        val layout = DeckLayout.default()
        val usable = layout.usableLayers()
        assertTrue(usable.isNotEmpty())
        assertTrue(usable.none { layer -> layer.keys.size == 1 && layer.keys[0].snippets })
        assertTrue(usable.first().keys.any { it.tap is DeckAction.Modifier })
        assertTrue(usable.first().keys.any { it.nub })
    }

    @Test
    fun `combo parsing splits modifiers from the target`() {
        val combo = DeckAction.Combo("CTRL SHIFT TAB")
        assertEquals(setOf(DeckModifier.CTRL, DeckModifier.SHIFT), combo.modifiers)
        assertEquals("TAB", combo.target)
        val letter = DeckAction.Combo("CTRL c")
        assertEquals(setOf(DeckModifier.CTRL), letter.modifiers)
        assertEquals("c", letter.target)
    }

    @Test
    fun `app actions and prefix macros reach their targets`() {
        val received = ArrayList<DeckAppAction>()
        val latch = ModifierLatch()
        val input = StageInput(session = { null }, latch = latch, onAppAction = { received += it })
        input.dispatch(DeckAction.App(DeckAppAction.HIDE_KEYBOARD), null)
        input.dispatch(DeckAction.Modifier(DeckModifier.SHIFT), null)
        assertEquals(listOf(DeckAppAction.HIDE_KEYBOARD), received)
        assertEquals(LatchState.ONE_SHOT, latch.shift)
        // With no session the macro is a no-op, but it must not throw on PREFIX expansion.
        input.dispatch(DeckAction.Macro("PREFIX d"), DeckLayer("tmux", emptyList(), prefix = "CTRL a"))
    }
}

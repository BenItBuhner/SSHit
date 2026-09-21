package app.berth.android.ui.stage

import app.berth.android.ui.stage.DeckKeyGesture.Effect
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckKey
import app.berth.domain.model.DeckKeyCode
import app.berth.domain.model.DeckModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gesture on one Deck key (spec D2), driven without a finger: a tap is the primary, a swipe up
 * the secondary, a swipe down the tertiary only while the setting is on, a hold the key's hold
 * action, the Nub's repeat cadence (C4) or the alternates popover, and a swipe across the key one
 * layer step. Time is the caller's, so the cadence is checked to the millisecond.
 */
class DeckKeyGestureTest {
    private val esc = DeckAction.Key(DeckKeyCode.ESC)
    private val tick = DeckAction.Text("`")
    private val tilde = DeckAction.Text("~")
    private val strip = DeckAction.Strip(listOf(DeckKeyCode.F1, DeckKeyCode.F2))

    private fun gesture(
        key: DeckKey,
        repeating: Boolean = false,
        swipeDown: Boolean = true,
        layerSwipe: Boolean = true,
    ) = DeckKeyGesture(key, repeating = repeating, swipeDown = swipeDown, layerSwipe = layerSwipe, threshold = Threshold, slop = Slop)

    /** A finger down at the key's centre at time zero. */
    private fun DeckKeyGesture.press(now: Long = 0L) = down(Cx, Cy, now)

    @Test
    fun `a tap sends the primary and asks for no wait on a key with nothing to hold`() {
        val g = gesture(DeckKey(tap = esc))
        g.press()
        assertNull("nothing to hold, nothing to wait for", g.waitMs(0L))
        assertNull(g.move(Cx + 2f, Cy - 3f))
        assertEquals(Effect.Fire(esc), g.up())
        assertTrue(g.done)
        assertNull("a second release is nothing", g.up())
    }

    @Test
    fun `a swipe up sends the secondary, decided at release`() {
        val g = gesture(DeckKey(tap = esc, up = tick))
        g.press()
        assertNull(g.move(Cx, Cy - Threshold - 1f))
        assertEquals(1, g.swipe)
        assertEquals(Effect.Fire(tick), g.up())
    }

    @Test
    fun `a swipe up that comes back is a tap`() {
        val g = gesture(DeckKey(tap = esc, up = tick))
        g.press()
        g.move(Cx, Cy - Threshold - 10f)
        assertEquals(1, g.swipe)
        g.move(Cx, Cy - 4f)
        assertEquals(0, g.swipe)
        assertEquals(Effect.Fire(esc), g.up())
    }

    @Test
    fun `a swipe up on a key with no secondary still sends the primary`() {
        val g = gesture(DeckKey(tap = esc))
        g.press()
        g.move(Cx, Cy - Threshold - 10f)
        assertEquals(Effect.Fire(esc), g.up())
    }

    @Test
    fun `a swipe down sends the tertiary while the setting is on`() {
        val g = gesture(DeckKey(tap = esc, up = tick, down = tilde), swipeDown = true)
        g.press()
        g.move(Cx, Cy + Threshold + 1f)
        assertEquals(-1, g.swipe)
        assertEquals(Effect.Fire(tilde), g.up())
    }

    @Test
    fun `a swipe down is a tap while the setting is off`() {
        val g = gesture(DeckKey(tap = esc, up = tick, down = tilde), swipeDown = false)
        g.press()
        g.move(Cx, Cy + Threshold + 20f)
        assertEquals("no preview of a swipe that does nothing", 0, g.swipe)
        assertEquals(Effect.Fire(esc), g.up())
    }

    @Test
    fun `a swipe down on a key with no tertiary sends the primary`() {
        val g = gesture(DeckKey(tap = esc, up = tick), swipeDown = true)
        g.press()
        g.move(Cx, Cy + Threshold + 20f)
        assertEquals(Effect.Fire(esc), g.up())
    }

    @Test
    fun `a hold fires the key's hold action once and swallows the release`() {
        val shift = DeckKey(tap = DeckAction.Modifier(DeckModifier.SHIFT), hold = strip)
        val g = gesture(shift)
        g.press(now = 1_000L)
        assertEquals(DeckKeyGesture.HoldMs, g.waitMs(1_000L))
        assertEquals("the wait shrinks as time passes", DeckKeyGesture.HoldMs - 150L, g.waitMs(1_150L))
        assertEquals(Effect.Hold(strip), g.timeout())
        assertNull("one hold, then only the finger", g.waitMs(1_400L))
        assertNull("a further timeout is nothing", g.timeout())
        assertNull("the release sends nothing after a hold", g.up())
    }

    @Test
    fun `a repeating key autorepeats on the Nub's cadence`() {
        val bksp = DeckAction.Key(DeckKeyCode.BACKSPACE)
        val g = gesture(DeckKey(tap = bksp), repeating = true)
        g.press(now = 0L)
        var now = 0L
        val waits = mutableListOf<Long>()
        val effects = mutableListOf<Effect?>()
        repeat(15) {
            val wait = g.waitMs(now)!!
            waits += wait
            now += wait
            effects += g.timeout()
        }
        assertEquals(
            listOf(400L, 180L, 180L, 180L, 180L, 90L, 90L, 90L, 90L, 90L, 90L, 90L, 90L, 45L, 45L),
            waits,
        )
        assertEquals(Effect.Repeat(bksp, first = true), effects.first())
        assertTrue("every repeat after the first is a repeat", effects.drop(1).all { it == Effect.Repeat(bksp, first = false) })
        assertEquals("the cadence never goes below 45 ms", 45L, g.waitMs(now))
        assertNull("the release sends nothing more", g.up())
        assertNull(g.waitMs(now))
    }

    @Test
    fun `the cadence is the Nub's rings, 180 for four, 90 for eight, then 45`() {
        assertEquals(180L, DeckKeyGesture.repeatIntervalMs(1))
        assertEquals(180L, DeckKeyGesture.repeatIntervalMs(4))
        assertEquals(90L, DeckKeyGesture.repeatIntervalMs(5))
        assertEquals(90L, DeckKeyGesture.repeatIntervalMs(12))
        assertEquals(45L, DeckKeyGesture.repeatIntervalMs(13))
        assertEquals(45L, DeckKeyGesture.repeatIntervalMs(100))
    }

    @Test
    fun `a repeating finger that drifts keeps its cadence, a still one that jitters still holds`() {
        val left = DeckAction.Key(DeckKeyCode.LEFT)
        val g = gesture(DeckKey(tap = left), repeating = true)
        g.press(now = 0L)
        // Jitter inside the slop: the hold's deadline stands.
        g.move(Cx + Slop / 2, Cy - Slop / 2)
        assertEquals(400L, g.waitMs(0L))
        assertEquals(Effect.Repeat(left, first = true), g.timeout())
        // Past the slop after the hold fired: still repeating, and never a swipe.
        g.move(Cx, Cy - Threshold - 10f)
        assertEquals(0, g.swipe)
        assertEquals(180L, g.waitMs(400L))
        assertEquals(Effect.Repeat(left, first = false), g.timeout())
        assertNull(g.up())
    }

    @Test
    fun `a finger that moves past the slop before the hold is a swipe in the making, never a hold`() {
        val shift = DeckKey(tap = DeckAction.Modifier(DeckModifier.SHIFT), hold = strip)
        val g = gesture(shift)
        g.press(now = 0L)
        g.move(Cx, Cy - Slop - 1f)
        assertNull("the wait is off once the finger moves", g.waitMs(100L))
        assertNull(g.timeout())
        assertEquals(Effect.Fire(shift.tap!!), g.up())
    }

    @Test
    fun `a hold on a key with alternates and no hold action raises the popover`() {
        val key = DeckKey(tap = esc, up = tick, down = tilde)
        val g = gesture(key)
        g.press()
        assertEquals(listOf(tick, tilde), g.alternates)
        assertEquals(Effect.Alternates(listOf(tick, tilde)), g.timeout())
        assertEquals(listOf(tick, tilde), g.popover)
        assertNull("no chip is chosen until the finger moves", g.hovered)
        assertNull("a release without moving sends nothing: the hold was a peek", g.up())
    }

    @Test
    fun `the popover's alternates are the secondary then the tertiary, whatever the swipe settings`() {
        assertEquals(listOf(tick, tilde), gesture(DeckKey(tap = esc, up = tick, down = tilde), swipeDown = false).alternates)
        assertEquals(listOf(tilde), gesture(DeckKey(tap = esc, down = tilde), swipeDown = false).alternates)
        assertEquals(emptyList<DeckAction>(), gesture(DeckKey(tap = esc)).alternates)
    }

    @Test
    fun `a finger that slides onto a chip and lets go sends it`() {
        val key = DeckKey(tap = esc, up = tick, down = tilde)
        val g = gesture(key)
        g.press()
        g.timeout()
        // Two chips 56 wide, the first beginning 30 to the left of the key's edge.
        g.chipsAt(left = -30f, width = 56f)
        g.move(Cx, Cy - 60f)
        assertEquals("over the key's own middle, which is the second chip's span", 1, g.hovered)
        g.move(-20f, Cy - 60f)
        assertEquals(0, g.hovered)
        assertEquals(Effect.Fire(tick), g.up())
    }

    @Test
    fun `a finger that slides off every chip and lets go sends nothing`() {
        val key = DeckKey(tap = esc, up = tick, down = tilde)
        val g = gesture(key)
        g.press()
        g.timeout()
        g.chipsAt(left = 0f, width = 56f)
        g.move(30f, Cy - 60f)
        assertEquals(0, g.hovered)
        g.move(-40f, Cy - 60f)
        assertNull("left of the first chip", g.hovered)
        g.move(200f, Cy - 60f)
        assertNull("past the last chip", g.hovered)
        assertNull(g.up())
    }

    @Test
    fun `no layer step or swipe once the popover is up`() {
        val key = DeckKey(tap = esc, up = tick, down = tilde)
        val g = gesture(key, layerSwipe = true)
        g.press()
        g.timeout()
        g.chipsAt(left = 0f, width = 56f)
        assertNull(g.move(Cx + Threshold * 3, Cy))
        assertEquals(0, g.swipe)
        assertFalse(g.done)
    }

    @Test
    fun `a swipe across the key steps the layer once and spends the touch`() {
        val g = gesture(DeckKey(tap = esc, up = tick), layerSwipe = true)
        g.press()
        assertNull("inside the threshold nothing happens", g.move(Cx + Threshold - 1f, Cy))
        assertEquals(Effect.LayerStep(1), g.move(Cx + Threshold, Cy + 2f))
        assertTrue(g.done)
        assertNull("the rest of the touch is spent", g.move(Cx + Threshold * 2, Cy))
        assertNull(g.up())
    }

    @Test
    fun `left is the layer before, right the next`() {
        val left = gesture(DeckKey(tap = esc), layerSwipe = true)
        left.press()
        assertEquals(Effect.LayerStep(-1), left.move(Cx - Threshold, Cy))
        val right = gesture(DeckKey(tap = esc), layerSwipe = true)
        right.press()
        assertEquals(Effect.LayerStep(1), right.move(Cx + Threshold, Cy))
    }

    @Test
    fun `a diagonal that is more up than across is a swipe up, not a layer step`() {
        val g = gesture(DeckKey(tap = esc, up = tick), layerSwipe = true)
        g.press()
        assertNull(g.move(Cx + Threshold, Cy - Threshold - 5f))
        assertEquals(1, g.swipe)
        assertEquals(Effect.Fire(tick), g.up())
    }

    @Test
    fun `a swipe across the key is nothing while the setting is off`() {
        val g = gesture(DeckKey(tap = esc), layerSwipe = false)
        g.press()
        assertNull(g.move(Cx + Threshold * 3, Cy))
        assertFalse(g.done)
        assertEquals(Effect.Fire(esc), g.up())
    }

    @Test
    fun `a swipe across the key cancels a hold in the making`() {
        val shift = DeckKey(tap = DeckAction.Modifier(DeckModifier.SHIFT), hold = strip)
        val g = gesture(shift, layerSwipe = true)
        g.press(now = 0L)
        assertEquals(Effect.LayerStep(1), g.move(Cx + Threshold, Cy))
        assertNull(g.waitMs(0L))
        assertNull(g.timeout())
    }

    @Test
    fun `a swipe across the key after a hold fired is nothing`() {
        val shift = DeckKey(tap = DeckAction.Modifier(DeckModifier.SHIFT), hold = strip)
        val g = gesture(shift, layerSwipe = true)
        g.press(now = 0L)
        assertEquals(Effect.Hold(strip), g.timeout())
        assertNull(g.move(Cx + Threshold * 2, Cy))
        assertFalse(g.done)
        assertNull(g.up())
    }

    @Test
    fun `an empty slot does nothing`() {
        val g = gesture(DeckKey())
        g.press()
        assertNull(g.waitMs(0L))
        assertNull(g.up())
    }

    private companion object {
        const val Threshold = 24f
        const val Slop = 8f
        const val Cx = 40f
        const val Cy = 22f
    }
}

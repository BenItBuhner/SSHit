package app.berth.android.ui.stage

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import app.berth.android.ComposeHostRule
import app.berth.android.createBerthComposeRule
import app.berth.android.screenshots.deckTargetFloor
import app.berth.android.ui.theme.BerthTheme
import app.berth.domain.model.DeckArrows
import app.berth.domain.model.DeckLayout
import app.berth.domain.model.InterfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a Deck row's width gives its targets (spec l.102, C5). Every dp of the row is a target's:
 * the Grip's 44 where the row has room beside 40 for each other target, the layer key's 50, and
 * the keys sharing the rest. Short of that the trailing edge gives first, then the Grip, to 40 and
 * no lower; a row with less than 40 for each target gives every target the same share, and the
 * arrow slot counts one target for each of its controls. The audit's floor follows the same rule.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h914dp-420dpi")
class DeckTargetsTest {
    @get:Rule(order = 0)
    val host = ComposeHostRule()

    @get:Rule(order = 1)
    val compose = createBerthComposeRule()

    private var density = 1f

    private fun mount(arrows: DeckArrows) {
        compose.setContent {
            BerthTheme(InterfaceTheme.DEFAULT) {
                density = LocalDensity.current.density
                val input = remember { StageInput(session = { null }, latch = ModifierLatch(), onAppAction = {}) }
                Deck(layout = DeckLayout.default().copy(arrows = arrows), layerIndex = 0, onLayerIndexChange = {}, input = input)
            }
        }
        compose.waitForIdle()
    }

    /** The row's targets left to right, as (left, width) in dp. */
    private fun row(): List<Pair<Float, Float>> =
        compose.onAllNodes(hasTestTag(DeckKeyTag)).fetchSemanticsNodes()
            .map { it.boundsInRoot }
            .sortedBy { it.left }
            .map { it.left / density to it.width / density }

    @Test
    fun `the Base layer's row gives 44, 40 and 50 where it can, the trailing edge first and then the Grip`() {
        fun check(width: Float, grip: Float, key: Float, layerKey: Float) {
            val t = deckRowTargets(width.dp, keys = 7)
            assertEquals("the Grip at $width dp", grip, t.grip.value, 0.01f)
            assertEquals("a key at $width dp", key, t.key.value, 0.01f)
            assertEquals("the layer key at $width dp", layerKey, t.layerKey.value, 0.01f)
            assertEquals("the row is its targets at $width dp", width, t.grip.value + 7 * t.key.value + t.layerKey.value, 0.01f)
        }
        check(411f, 44f, 317f / 7, 50f)
        check(374f, 44f, 40f, 50f)
        check(370f, 44f, 40f, 46f)
        check(364f, 44f, 40f, 40f)
        check(362f, 42f, 40f, 40f)
        check(360f, 40f, 40f, 40f)
        check(359f, 359f / 9, 359f / 9, 359f / 9)
    }

    @Test
    fun `the layer key's face keeps 40 and the gap, and on a short target leaves the trailing edge its half gap`() {
        deckRowTargets(411.dp, 7).let { assertEquals(40f, it.layerFace.value, 0.01f); assertEquals(8f, it.trailingEdge.value, 0.01f) }
        deckRowTargets(370.dp, 7).let { assertEquals(40f, it.layerFace.value, 0.01f); assertEquals(4f, it.trailingEdge.value, 0.01f) }
        deckRowTargets(360.dp, 7).let { assertEquals(36f, it.layerFace.value, 0.01f); assertEquals(2f, it.trailingEdge.value, 0.01f) }
    }

    /**
     * Four keys on a 411 dp phone: the Base layer's six keys, the four arrows, the Grip and the
     * layer key are twelve targets, more than the row has 40 for, so each takes a twelfth, 34.25 dp,
     * the arrows' pitch their neighbours'. The targets meet edge to edge across the whole row.
     */
    @Test
    fun `with Four keys every target on a 411 dp row takes the same share`() {
        mount(DeckArrows.FOUR_KEYS)
        val row = row()
        assertEquals("the Grip, six keys, four arrows and the layer key", 12, row.size)
        val width = row.last().first + row.last().second
        val share = width / row.size
        println("Four keys at 411 dp: row $width dp, ${row.size} targets, share $share dp")
        assertEquals(411f / 12, share, 0.1f)
        row.forEachIndexed { i, (_, w) -> assertEquals("target ${i + 1} across", share, w, 0.5f) }
        row.zipWithNext().forEach { (a, b) -> assertEquals("edge to edge", a.first + a.second, b.first, 0.5f) }
        assertEquals("from the leading edge", 0f, row.first().first, 0.5f)
    }

    /** Both: the Nub and four arrows are five targets in the slot, thirteen on the row, 31.6 dp each. */
    @Test
    fun `with Both the Nub and the four arrows count five, every target the same share`() {
        mount(DeckArrows.BOTH)
        val row = row()
        assertEquals("the Grip, six keys, the Nub, four arrows and the layer key", 13, row.size)
        row.forEachIndexed { i, (_, w) -> assertEquals("target ${i + 1} across", 411f / 13, w, 0.5f) }
    }

    /** The audit's floor for the Grip: 44 on a row of nine from 364 dp, 40 below it, and the share once that is under 40. */
    @Test
    fun `the audit holds the Grip to 44 from 364 dp and to 40 below`() {
        fun floor(width: Float, grip: Boolean = true) = deckTargetFloor(width, 9, grip, keyWidth = 40f, gripWidth = 44f)
        assertEquals(44f, floor(411f), 0.01f)
        assertEquals(44f, floor(364f), 0.01f)
        assertEquals(40f, floor(363f), 0.01f)
        assertEquals(40f, floor(360f), 0.01f)
        assertEquals(359f / 9, floor(359f), 0.01f)
        assertEquals("a key's floor stays 40", 40f, floor(411f, grip = false), 0.01f)
        assertEquals("twelve on 411 dp", 411f / 12, deckTargetFloor(411f, 12, grip = true, keyWidth = 40f, gripWidth = 44f), 0.01f)
    }
}

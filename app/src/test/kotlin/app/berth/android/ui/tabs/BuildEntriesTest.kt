package app.berth.android.ui.tabs

import app.berth.domain.model.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildEntries] is the one model behind the strip and the switcher (spec C3, Groups): chips head
 * runs only once two groups hold tabs, a group without tabs has no run and no chip, a collapsed
 * run folds to its active tab, and every tab keeps its index in the full strip for drag and drop.
 */
class BuildEntriesTest {
    private val home = group(Workspace.DEFAULT_ID, "Home", 0)
    private val work = group("ws-work", "Work", 1)
    private val lab = group("ws-lab", "Lab", 2)

    private fun List<StripEntry>.chips() = filterIsInstance<StripEntry.Chip>()
    private fun List<StripEntry>.tabs() = filterIsInstance<StripEntry.Tab>()
    private fun List<StripEntry>.shape() = map {
        when (it) {
            is StripEntry.Chip -> "chip:${it.group.name}"
            is StripEntry.Tab -> "tab:${it.slot.id}"
            StripEntry.Plus -> "plus"
        }
    }

    @Test
    fun `a single group shows no chip`() {
        val entries = buildEntries(fakeSlots(3, home.id), listOf(home), "t0")
        assertEquals(listOf("tab:t0", "tab:t1", "tab:t2", "plus"), entries.shape())
    }

    @Test
    fun `a second group with tabs heads every run with its chip`() {
        val slots = fakeSlots(2, home.id) + fakeSlots(1, work.id, prefix = "w", from = 2)
        val entries = buildEntries(slots, listOf(home, work), "t0")
        assertEquals(listOf("chip:Home", "tab:t0", "tab:t1", "chip:Work", "tab:w0", "plus"), entries.shape())
        assertEquals(listOf(0, 1), entries.chips().map { it.groupIndex })
        assertEquals(listOf(2, 1), entries.chips().map { it.tabs.size })
    }

    @Test
    fun `a second group without tabs makes no chip`() {
        // One populated group beside an empty one is a single group as far as the strip is concerned.
        val entries = buildEntries(fakeSlots(3, home.id), listOf(home, work), "t0")
        assertEquals(listOf("tab:t0", "tab:t1", "tab:t2", "plus"), entries.shape())
        assertTrue(entries.chips().isEmpty())
    }

    @Test
    fun `an empty group between populated ones has no run and the chips keep the model's indices`() {
        val slots = fakeSlots(1, home.id) + fakeSlots(1, lab.id, prefix = "l", from = 1)
        val entries = buildEntries(slots, listOf(home, work, lab), "t0")
        assertEquals(listOf("chip:Home", "tab:t0", "chip:Lab", "tab:l0", "plus"), entries.shape())
        // Move to the neighbouring chip's place uses the group's index in the model, empty groups included.
        assertEquals(listOf(0, 2), entries.chips().map { it.groupIndex })
    }

    @Test
    fun `groups head their runs in sort order whatever order they arrive in`() {
        val slots = fakeSlots(1, work.id, prefix = "w") + fakeSlots(1, home.id, from = 1)
        val entries = buildEntries(slots, listOf(work, home), "w0")
        assertEquals(listOf("chip:Home", "tab:t0", "chip:Work", "tab:w0", "plus"), entries.shape())
    }

    @Test
    fun `a collapsed run keeps only its active tab and its chip carries every tab`() {
        val folded = work.copy(collapsed = true)
        val slots = fakeSlots(1, home.id) + fakeSlots(3, folded.id, prefix = "w", from = 1)
        val onW1 = buildEntries(slots, listOf(home, folded), "w1")
        assertEquals(listOf("chip:Home", "tab:t0", "chip:Work", "tab:w1", "plus"), onW1.shape())
        assertEquals(3, onW1.chips().last().tabs.size)
        // Active elsewhere: the run is its chip alone.
        val onT0 = buildEntries(slots, listOf(home, folded), "t0")
        assertEquals(listOf("chip:Home", "tab:t0", "chip:Work", "plus"), onT0.shape())
    }

    @Test
    fun `every tab keeps its index in the full strip, hidden tabs included`() {
        val folded = work.copy(collapsed = true)
        val slots = fakeSlots(1, home.id) + fakeSlots(3, folded.id, prefix = "w", from = 1) + fakeSlots(1, lab.id, prefix = "l", from = 4)
        val entries = buildEntries(slots, listOf(home, folded, lab), "w2")
        assertEquals(mapOf("t0" to 0, "w2" to 3, "l0" to 4), entries.tabs().associate { it.slot.id to it.stripIndex })
        assertEquals(listOf(0, 1, 2), entries.tabs().map { it.groupIndex })
        assertSame(folded, entries.tabs()[1].group)
    }

    @Test
    fun `a tab whose group is unknown trails the strip without a chip`() {
        val slots = fakeSlots(1, home.id) + fakeSlots(1, "ws-gone", prefix = "g", from = 1)
        val entries = buildEntries(slots, listOf(home), "t0")
        assertEquals(listOf("tab:t0", "tab:g0", "plus"), entries.shape())
        val stray = entries.tabs().last()
        assertNull(stray.group)
        assertEquals(-1, stray.groupIndex)
    }

    @Test
    fun `no tabs is just the plus tab`() {
        assertEquals(listOf("plus"), buildEntries(emptyList(), listOf(home, work), null).shape())
    }

    @Test
    fun `the chip label follows the style's casing and carries the count while collapsed`() {
        assertEquals("WORK", chipLabel(work, 3, TabStripStyle.Default))
        assertEquals("WORK \u00B7 3", chipLabel(work.copy(collapsed = true), 3, TabStripStyle.Default))
        val plain = TabStripStyle(chipUppercase = false)
        assertEquals("Work", chipLabel(work, 3, plain))
        assertEquals("Work \u00B7 3", chipLabel(work.copy(collapsed = true), 3, plain))
    }
}

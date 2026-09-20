package app.berth.domain

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.domain.model.TabOrder
import app.berth.domain.model.Workspace
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabOrderTest {
    private val host = Host(id = "h", name = "box", color = SwatchColor.OCHRE, monogram = "BO", address = "box", user = "me", createdAt = 0)
    private val home = Workspace(id = "home", name = "Home", color = SwatchColor.OCHRE, monogram = "HO", sortOrder = 0, createdAt = 0)
    private val work = Workspace(id = "work", name = "Work", color = SwatchColor.SLATE, monogram = "WK", sortOrder = 1, createdAt = 1)

    private fun tab(id: String, group: String, position: Int, created: Long = position.toLong()) = SessionRecord(
        id = id, workspaceId = group, hostId = "h", hostSnapshot = host, state = SessionState.DETACHED, sortOrder = position, createdAt = created,
    )

    /** Applies the changed records on top of the strip and re-sorts, the way the manager does after each edit. */
    private fun List<SessionRecord>.applying(changes: List<SessionRecord>, groups: List<Workspace> = listOf(home, work)): List<SessionRecord> {
        val byId = associateBy { it.id }.toMutableMap()
        for (change in changes) byId[change.id] = change
        return TabOrder.strip(byId.values.toList(), groups)
    }

    private fun List<SessionRecord>.ids() = map { it.id }

    @Test
    fun `strip orders groups by their order then tabs by position, orphans last`() {
        val tabs = listOf(tab("w2", "work", 1), tab("h1", "home", 1), tab("lost", "gone", 0), tab("w1", "work", 0), tab("h0", "home", 0))
        assertEquals(listOf("h0", "h1", "w1", "w2", "lost"), TabOrder.strip(tabs, listOf(home, work)).ids())
        assertEquals(listOf("w1", "w2", "h0", "h1", "lost"), TabOrder.strip(tabs, listOf(home.copy(sortOrder = 5), work)).ids())
    }

    @Test
    fun `normalize renumbers per group and reports only what moved`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 4), tab("c", "work", 7), tab("d", "work", 8))
        val changed = TabOrder.normalize(strip)
        assertEquals(listOf("b", "c", "d"), changed.ids())
        assertEquals(listOf(1, 0, 1), changed.map { it.sortOrder })
        assertTrue(TabOrder.normalize(strip.applying(changed)).isEmpty(), "a normalized strip has nothing left to fix")
    }

    @Test
    fun `a new tab opens directly after the active one and shifts the rest of its group`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0))
        val fresh = tab("n", "home", 0, created = 99)
        val changed = TabOrder.insertAfter(strip, fresh, afterId = "a", groupId = null)
        assertEquals(setOf("n", "b"), changed.ids().toSet())
        assertEquals(listOf("a", "n", "b", "c"), strip.applying(changed).ids())
        assertEquals("home", changed.first { it.id == "n" }.workspaceId)
    }

    @Test
    fun `a new tab with no anchor lands at the end of the requested group or the last group`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0))
        val fresh = tab("n", "home", 0, created = 99)
        assertEquals(listOf("a", "b", "n", "c"), strip.applying(TabOrder.insertAfter(strip, fresh, afterId = null, groupId = "home")).ids())
        assertEquals(listOf("a", "b", "c", "n"), strip.applying(TabOrder.insertAfter(strip, fresh, afterId = null, groupId = null)).ids())
        val onlyOne = TabOrder.insertAfter(emptyList(), fresh, afterId = null, groupId = null)
        assertEquals(listOf(fresh), onlyOne, "the first tab is returned even when its position already reads zero")
    }

    @Test
    fun `a reopened tab returns to its old slot in its old group`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0))
        val again = tab("n", "home", 0, created = 99)
        assertEquals(listOf("n", "a", "b", "c"), strip.applying(TabOrder.insertAt(strip, again, "home", 0)).ids())
        assertEquals(listOf("a", "n", "b", "c"), strip.applying(TabOrder.insertAt(strip, again, "home", 1)).ids())
        assertEquals(listOf("a", "b", "n", "c"), strip.applying(TabOrder.insertAt(strip, again, "home", 7)).ids())
        val intoWork = TabOrder.insertAt(strip, again, "work", 0)
        assertEquals(listOf("a", "b", "n", "c"), strip.applying(intoWork).ids())
        assertEquals("work", intoWork.first { it.id == "n" }.workspaceId)
        val spare = Workspace(id = "spare", name = "Spare", color = SwatchColor.MOSS, monogram = "SP", sortOrder = 2, createdAt = 2)
        val intoEmpty = TabOrder.insertAt(strip, again, "spare", 3)
        assertEquals(0, intoEmpty.first { it.id == "n" }.sortOrder)
        assertEquals(listOf("a", "b", "c", "n"), strip.applying(intoEmpty, listOf(home, work, spare)).ids())
    }

    @Test
    fun `move reorders within a group and returns the moved tab even when its number is unchanged`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "home", 2))
        val changed = TabOrder.move(strip, "c", 0)
        assertEquals(listOf("c", "a", "b"), strip.applying(changed).ids())
        assertTrue(changed.any { it.id == "c" })
        assertEquals(listOf("a", "b", "c"), strip.applying(TabOrder.move(strip.applying(changed), "c", 2)).ids())
        assertTrue(TabOrder.move(strip, "missing", 0).isEmpty())
    }

    @Test
    fun `dragging to the end of its own run keeps a tab in its group`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0), tab("d", "work", 1))
        val changed = TabOrder.move(strip, "a", 1)
        assertEquals("home", changed.first { it.id == "a" }.workspaceId)
        assertEquals(listOf("b", "a", "c", "d"), strip.applying(changed).ids())
    }

    @Test
    fun `dragging into another run joins that group and renumbers both groups`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0), tab("d", "work", 1))
        val changed = TabOrder.move(strip, "a", 2)
        val a = changed.first { it.id == "a" }
        assertEquals("work", a.workspaceId)
        assertEquals(1, a.sortOrder)
        assertEquals(listOf("b", "c", "a", "d"), strip.applying(changed).ids())
        assertEquals(0, strip.applying(changed).first { it.id == "b" }.sortOrder)
        assertEquals(2, strip.applying(changed).first { it.id == "d" }.sortOrder)

        val toEnd = TabOrder.move(strip, "a", 3)
        assertEquals(listOf("b", "c", "d", "a"), strip.applying(toEnd).ids())
        assertEquals("work", toEnd.first { it.id == "a" }.workspaceId)
    }

    @Test
    fun `move to group appends at the end of that group, even an empty one`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0))
        val toWork = TabOrder.moveToGroup(strip, "a", "work")
        assertEquals(listOf("b", "c", "a"), strip.applying(toWork).ids())
        assertEquals("work", toWork.first { it.id == "a" }.workspaceId)

        val spare = Workspace(id = "spare", name = "Spare", color = SwatchColor.MOSS, monogram = "SP", sortOrder = 2, createdAt = 2)
        val toSpare = TabOrder.moveToGroup(strip, "b", "spare")
        val b = toSpare.first { it.id == "b" }
        assertEquals("spare", b.workspaceId)
        assertEquals(0, b.sortOrder)
        assertEquals(listOf("a", "c", "b"), strip.applying(toSpare, listOf(home, work, spare)).ids())
    }

    @Test
    fun `closing activates the tab to the right, else the left, else nothing`() {
        val strip = listOf(tab("a", "home", 0), tab("b", "home", 1), tab("c", "work", 0))
        assertEquals("b", TabOrder.nextActiveAfterClose(strip, "a"))
        assertEquals("c", TabOrder.nextActiveAfterClose(strip, "b"))
        assertEquals("b", TabOrder.nextActiveAfterClose(strip, "c"))
        assertEquals("a", TabOrder.nextActiveAfterClose(strip, "unknown"))
        assertNull(TabOrder.nextActiveAfterClose(listOf(tab("a", "home", 0)), "a"))
    }

    @Test
    fun `groups reorder and only the shifted ones come back`() {
        val spare = Workspace(id = "spare", name = "Spare", color = SwatchColor.MOSS, monogram = "SP", sortOrder = 2, createdAt = 2)
        val changed = TabOrder.moveGroup(listOf(home, work, spare), "spare", 0)
        assertEquals(listOf("spare", "home", "work"), changed.sortedBy { it.sortOrder }.map { it.id })
        assertEquals(3, changed.size)
        assertTrue(TabOrder.moveGroup(listOf(home, work), "work", 1).isEmpty(), "a group dropped where it already sits changes nothing")
        assertTrue(TabOrder.moveGroup(listOf(home, work), "nope", 0).isEmpty())
    }

    @Test
    fun `tab kinds serialize by id and unknown ids fall back to ssh`() {
        val json = Json.encodeToString(TabKind.serializer(), TabKind.Ssh)
        assertTrue(json.contains("ssh"))
        assertEquals(TabKind.Ssh, Json.decodeFromString(TabKind.serializer(), json))
        assertEquals(TabKind.Ssh, TabKind.fromId(null))
        assertEquals(TabKind.Ssh, TabKind.fromId("shell-from-the-future"))
        assertEquals("ssh", TabKind.Ssh.id)

        val files = Json.encodeToString(TabKind.serializer(), TabKind.Files)
        assertTrue(files.contains("files"))
        assertEquals(TabKind.Files, Json.decodeFromString(TabKind.serializer(), files))
        assertEquals(TabKind.Files, TabKind.fromId("files"))
        assertEquals("files", TabKind.Files.id)

        val tunnels = Json.encodeToString(TabKind.serializer(), TabKind.Tunnels)
        assertTrue(tunnels.contains("tunnels"))
        assertEquals(TabKind.Tunnels, Json.decodeFromString(TabKind.serializer(), tunnels))
        assertEquals(TabKind.Tunnels, TabKind.fromId("tunnels"))
        assertEquals("tunnels", TabKind.Tunnels.id)
    }

    @Test
    fun `the strip orders files tabs like any other tab`() {
        val files = tab("f", "home", 1).copy(kind = TabKind.Files, title = "Files \u00B7 box")
        val strip = listOf(tab("a", "home", 0), files, tab("b", "home", 2), tab("c", "work", 0))
        assertEquals(listOf("a", "f", "b", "c"), TabOrder.strip(strip.shuffled(), listOf(home, work)).ids())
        assertEquals("Files \u00B7 box", files.displayTitle)
        assertTrue(TabOrder.normalize(TabOrder.strip(strip, listOf(home, work))).isEmpty())
        assertEquals("b", TabOrder.nextActiveAfterClose(strip, "f"))
        val moved = TabOrder.moveToGroup(strip, "f", "work")
        assertEquals(listOf("a", "b", "c", "f"), strip.applying(moved).ids())
        assertEquals(TabKind.Files, strip.applying(moved).last().kind)
    }

    @Test
    fun `display title prefers the rename, then the reported title, then the host`() {
        val plain = tab("a", "home", 0)
        assertEquals("box", plain.displayTitle)
        assertEquals("me@box: ~", plain.copy(title = "me@box: ~").displayTitle)
        assertEquals("deploy", plain.copy(title = "me@box: ~", customTitle = "deploy").displayTitle)
        assertEquals("me@box: ~", plain.copy(title = "me@box: ~", customTitle = "   ").displayTitle)
    }
}

package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SessionState.CLOSED
import app.berth.domain.model.SessionState.CONNECTING
import app.berth.domain.model.SessionState.DETACHED
import app.berth.domain.model.SessionState.FAILED
import app.berth.domain.model.SessionState.LIVE
import app.berth.domain.model.SessionState.RECONNECTING
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Files tab's own logic: which of a host's terminal tabs the browser rides and what the tab
 * is called. The election runs on records, so every state a strip can be in is a list literal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesTabTest {
    private val host = Host(id = "h", name = "homelab", color = SwatchColor.MOSS, monogram = "HL", address = "192.168.1.20", user = "ben", createdAt = 0)

    private fun terminal(id: String, state: SessionState, position: Int = 0, lastLiveAt: Long? = null) = SessionRecord(
        id = id, workspaceId = "home", hostId = host.id, hostSnapshot = host, state = state, sortOrder = position, createdAt = position.toLong(), lastLiveAt = lastLiveAt,
    )

    private fun elect(tabs: List<SessionRecord>, current: String? = null, preferred: String? = null) =
        FilesTab.electRide(tabs, currentId = current, preferredId = preferred)

    // ---- the election ----------------------------------------------------------------------------

    @Test
    fun `no terminal on the host means no ride`() {
        assertNull(elect(emptyList()))
        assertNull(elect(listOf(terminal("a", CLOSED)), current = "a", preferred = "a"))
    }

    @Test
    fun `the terminal Files was opened from wins while it is connecting or live`() {
        val tabs = listOf(terminal("a", LIVE), terminal("b", CONNECTING, 1))
        assertEquals("b", elect(tabs, current = "a", preferred = "b"))
        assertEquals("a", elect(tabs, current = "b", preferred = "a"))
    }

    @Test
    fun `a live ride is sticky, so the browser never hops between two live terminals`() {
        val tabs = listOf(terminal("a", LIVE), terminal("b", LIVE, 1))
        assertEquals("b", elect(tabs, current = "b"))
        assertEquals("a", elect(tabs, current = "a"))
        // A preference for a terminal that is down does not pull the browser off a live one.
        assertEquals("b", elect(tabs + terminal("c", DETACHED, 2), current = "b", preferred = "c"))
    }

    @Test
    fun `with no ride yet, live beats connecting beats strip order`() {
        assertEquals("b", elect(listOf(terminal("a", CONNECTING), terminal("b", LIVE, 1))))
        assertEquals("a", elect(listOf(terminal("a", CONNECTING), terminal("b", DETACHED, 1))))
        assertEquals("a", elect(listOf(terminal("a", RECONNECTING), terminal("b", CONNECTING, 1))))
        // A ride that dropped gives way to a terminal that is up.
        assertEquals("b", elect(listOf(terminal("a", DETACHED), terminal("b", LIVE, 1)), current = "a"))
    }

    @Test
    fun `with nothing up, the preferred or current terminal is kept so Reconnect has a target`() {
        val tabs = listOf(terminal("a", DETACHED), terminal("b", FAILED, 1), terminal("c", DETACHED, 2))
        assertEquals("b", elect(tabs, current = "c", preferred = "b"))
        assertEquals("c", elect(tabs, current = "c"))
        assertEquals("a", elect(tabs, current = "gone"))
        assertEquals("a", elect(tabs))
    }

    @Test
    fun `a ride that closed is dropped for the next open terminal`() {
        assertEquals("b", elect(listOf(terminal("a", CLOSED), terminal("b", DETACHED, 1)), current = "a"))
        assertNull(elect(listOf(terminal("a", CLOSED)), current = "a"))
    }

    // ---- the title -----------------------------------------------------------------------------

    @Test
    fun `the title names the host at home and the folder anywhere else`() {
        assertEquals("Files \u00B7 homelab", FilesTab.titleFor("homelab", null, null))
        assertEquals("Files \u00B7 homelab", FilesTab.titleFor("homelab", "/home/ben", "/home/ben"))
        assertEquals("Files \u00B7 berth", FilesTab.titleFor("homelab", "/home/ben/projects/berth", "/home/ben"))
        assertEquals("Files \u00B7 berth", FilesTab.titleFor("homelab", "/home/ben/projects/berth/", "/home/ben"))
        assertEquals("Files \u00B7 /", FilesTab.titleFor("homelab", "/", "/home/ben"))
        // Before home is known every folder is named, home included.
        assertEquals("Files \u00B7 ben", FilesTab.titleFor("homelab", "/home/ben", null))
    }

    @Test
    fun `a fresh record is a detached Files tab titled for its host, starting at the folder given`() {
        val record = FilesTab.newRecord("f", host, "home", createdAt = 7, folder = "/var/log")
        assertEquals(TabKind.Files, record.kind)
        assertEquals(DETACHED, record.state)
        assertEquals("Files \u00B7 homelab", record.title)
        assertEquals("/var/log", record.cwd)
        assertEquals(host, record.hostSnapshot)
        assertEquals(7L, record.createdAt)
        assertNull(FilesTab.newRecord("g", host, "home", createdAt = 0).cwd)
    }

    // ---- the tab -------------------------------------------------------------------------------

    @Test
    fun `showing a folder keeps it as cwd, retitles the tab and writes the record once per change`() = runTest {
        val written = ArrayList<SessionRecord>()
        val tab = FilesTab(FilesTab.newRecord("f", host, "home", 0), backgroundScope) { written += it }

        tab.showing("/home/ben", "/home/ben")
        assertEquals("/home/ben", tab.folder)
        assertEquals("Files \u00B7 homelab", tab.record.value.title)
        // The same folder again changes nothing and writes nothing.
        tab.showing("/home/ben", "/home/ben")
        tab.showing("/var/log", "/home/ben")
        assertEquals("/var/log", tab.folder)
        assertEquals("Files \u00B7 log", tab.record.value.displayTitle)
        runCurrent()
        assertEquals(listOf("/home/ben", "/var/log"), written.map { it.cwd })

        // A rename shows over the automatic title until it is cleared; blanks clear it.
        tab.rename("  Logs ")
        assertEquals("Logs", tab.record.value.displayTitle)
        tab.rename("   ")
        assertEquals("Files \u00B7 log", tab.record.value.displayTitle)
        runCurrent()
        assertEquals(4, written.size)
    }

    @Test
    fun `a copy waiting on an answer raises attention off stage, and the answer or a look clears it`() = runTest {
        val written = ArrayList<SessionRecord>()
        val tab = FilesTab(FilesTab.newRecord("f", host, "home", 0), backgroundScope) { written += it }

        // On stage the pane is asking already; nothing to ring about.
        tab.onStage = true
        tab.waitingOnUser(true)
        assertFalse(tab.record.value.needsAttention)

        // Leaving with the question open is when the tab has to say so: the ring on the tab, the mark on its tile.
        tab.onStage = false
        assertTrue(tab.record.value.needsAttention)
        assertEquals(FilesTab.WAITING_ON_YOU, tab.record.value.attentionReason)

        // Coming back (the manager's markSeen on arrival) clears it; leaving again with the copy still waiting raises it again.
        tab.onStage = true
        tab.markSeen()
        assertFalse(tab.record.value.needsAttention)
        tab.onStage = false
        assertTrue(tab.record.value.needsAttention)

        // The answer, from whichever tab it came, clears it and going off stage no longer rings.
        tab.waitingOnUser(false)
        assertFalse(tab.record.value.needsAttention)
        assertNull(tab.record.value.attentionReason)
        tab.onStage = true
        tab.onStage = false
        assertFalse(tab.record.value.needsAttention)

        // A question raised while already off stage rings at once.
        tab.waitingOnUser(true)
        assertTrue(tab.record.value.needsAttention)
        runCurrent()
        assertEquals(listOf(true, false, true, false, true), written.map { it.needsAttention })
    }

    @Test
    fun `follow rides the elected terminal, mirrors its state and spends the preference on one election`() = runTest {
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(text: String) = Unit
        }
        val a = TerminalSession(terminal("a", DETACHED, lastLiveAt = 500), backgroundScope, env) {}
        val b = TerminalSession(terminal("b", DETACHED, 1, lastLiveAt = 900), backgroundScope, env) {}
        val tab = FilesTab(FilesTab.newRecord("f", host, "home", 0), backgroundScope) {}

        tab.follow(listOf(a, b))
        assertEquals("a", tab.ride.value?.id)
        assertEquals(500L, tab.record.value.lastLiveAt)

        // Files opened from b: the next election honours it, then the ride is sticky again.
        tab.prefer("b")
        tab.follow(listOf(a, b))
        assertEquals("b", tab.ride.value?.id)
        assertEquals(900L, tab.record.value.lastLiveAt)
        tab.follow(listOf(a, b))
        assertEquals("b", tab.ride.value?.id)

        // b closes: the browser moves to a; with no terminal left it rides nothing and reads Detached.
        tab.follow(listOf(a))
        assertEquals("a", tab.ride.value?.id)
        tab.follow(emptyList())
        assertNull(tab.ride.value)
        assertEquals(DETACHED, tab.state)
        assertEquals(500L, tab.record.value.lastLiveAt)

        tab.close()
        assertEquals(CLOSED, tab.state)
        assertNull(tab.ride.value)
    }
}

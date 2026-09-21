package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PROMPT = "demo@box:~$ "

/**
 * How a session collects its command history (spec C16) with no shell attached: the emulator is
 * fed what the remote would have echoed, and the session's input methods stand for the keys. The
 * session hands each command to the host's history through its environment; [store] is what it
 * handed over, under which host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandHistorySessionTest {
    private val host = Host(id = "h", name = "box", color = SwatchColor.MOSS, monogram = "B", address = "127.0.0.1", user = "demo", createdAt = 0)

    // Live with no shell behind it, as the Stage fixtures read: what is typed is tracked and goes
    // nowhere. A Detached record would have the first key reconnect (review #15), against a host
    // nothing answers for, on a fixture whose network flow ends at once.
    private fun record(id: String = "s") = SessionRecord(
        id = id, workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.LIVE, layer = PersistenceLayer.IN_APP, sortOrder = 0, createdAt = 0,
    )

    /** Stands in for the manager: what the sessions recorded, and what a restored frame handed over, each with its host key. */
    private class Store(var historyOn: Boolean) : SessionEnvironment {
        val recorded = ArrayList<Triple<String, String, Long>>()
        val imported = ArrayList<Pair<String, List<Pair<String, Long>>>>()
        override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
        override fun commandHistoryEnabled(): Boolean = historyOn
        override fun now(): Long = 1_700_000_000_000L
        override fun recordCommand(hostId: String, text: String, at: Long) { recorded += Triple(hostId, text, at) }
        override fun importCommands(hostId: String, entries: List<Pair<String, Long>>) { imported += hostId to entries }
    }

    private val store = Store(historyOn = true)

    private fun TestScope.session(historyOn: Boolean = true, id: String = "s"): TerminalSession {
        store.historyOn = historyOn
        return TerminalSession(record(id), backgroundScope, store) {}
    }

    @Suppress("UnusedReceiverParameter")
    private fun TerminalSession.texts() = store.recorded.map { it.second }

    // ---- typed-line fallback ---------------------------------------------------------------------

    @Test
    fun `a command typed key by key is recorded on Enter at once`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        for (ch in "ls -la") {
            s.sendText(ch.toString())
            s.emulator.write(ch.toString())
        }
        s.sendKey(TerminalKey.ENTER)
        // Recorded before the command's output, so a command that clears the screen is not lost; under the host, at the session's clock.
        assertEquals(listOf("ls -la"), s.texts())
        assertEquals(listOf(Triple(host.commandHistoryKey, "ls -la", 1_700_000_000_000L)), store.recorded)
        assertEquals("ls -la", s.record.value.lastCommand)
    }

    @Test
    fun `a command sent with its Enter in one write is recorded once the echo lands`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.sendText("uptime\n")
        assertTrue(s.texts().isEmpty())
        s.emulator.write("uptime\r\n 20:00:01 up 3 days\r\n$PROMPT")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("uptime"), s.texts())
    }

    @Test
    fun `a line that is never echoed records nothing, so a password stays out`() = runTest {
        val s = session()
        s.emulator.write("Password: ")
        s.sendText("hunter2\n")
        s.emulator.write("\r\n$PROMPT")
        advanceTimeBy(250)
        runCurrent()
        assertTrue(s.texts().isEmpty())
    }

    @Test
    fun `Backspace edits the line and a blank Enter records nothing`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.sendText("lx")
        s.sendKey(TerminalKey.BACKSPACE)
        s.sendText("s")
        s.emulator.write("ls")
        s.sendKey(TerminalKey.ENTER)
        s.emulator.write("\r\n$PROMPT")
        s.sendKey(TerminalKey.ENTER)
        s.emulator.write("\r\n$PROMPT")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("ls"), s.texts())
    }

    @Test
    fun `Tab completion is read off the screen from what was typed before it`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.sendText("git sta")
        s.emulator.write("git sta")
        s.sendKey(TerminalKey.TAB)
        s.emulator.write("tus")
        s.sendText(" -s")
        s.emulator.write(" -s")
        s.sendKey(TerminalKey.ENTER)
        assertEquals(listOf("git status -s"), s.texts())
    }

    @Test
    fun `an arrow hands the line to the shell and nothing is recorded until the next Enter`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.sendKey(TerminalKey.UP)
        s.emulator.write("ls -la")
        s.sendKey(TerminalKey.ENTER)
        s.emulator.write("\r\n$PROMPT")
        s.sendText("pwd")
        s.emulator.write("pwd")
        s.sendKey(TerminalKey.ENTER)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("pwd"), s.texts())
    }

    @Test
    fun `Ctrl+C abandons the line`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.sendText("rm -rf")
        s.emulator.write("rm -rf")
        s.sendControl('c')
        s.emulator.write("^C\r\n$PROMPT")
        s.sendText("ls")
        s.emulator.write("ls")
        s.sendKey(TerminalKey.ENTER)
        assertEquals(listOf("ls"), s.texts())
    }

    @Test
    fun `a one-line paste lands on the prompt like typing and a multi-line paste is left to the shell`() = runTest {
        val s = session()
        s.emulator.write(PROMPT)
        s.paste("df -h")
        s.emulator.write("df -h")
        s.sendKey(TerminalKey.ENTER)
        s.emulator.write("\r\n$PROMPT")
        s.paste("cd /tmp\nls\n")
        s.emulator.write("cd /tmp\r\nls\r\n$PROMPT")
        s.sendText("pwd")
        s.emulator.write("pwd")
        s.sendKey(TerminalKey.ENTER)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("df -h", "pwd"), s.texts())
    }

    @Test
    fun `nothing typed on the alternate screen is a command`() = runTest {
        val s = session()
        s.emulator.write("$PROMPT\u001b[?1049h\u001b[H")
        s.sendText(":wq")
        s.emulator.write(":wq")
        s.sendKey(TerminalKey.ENTER)
        advanceTimeBy(250)
        runCurrent()
        assertTrue(s.texts().isEmpty())
    }

    @Test
    fun `history off records nothing`() = runTest {
        val s = session(historyOn = false)
        s.emulator.write(PROMPT)
        s.sendText("ls")
        s.emulator.write("ls")
        s.sendKey(TerminalKey.ENTER)
        assertTrue(s.texts().isEmpty())
    }

    // ---- OSC 133 marks ---------------------------------------------------------------------------

    @Test
    fun `prompt marks report the command and stand the typed-line fallback down`() = runTest {
        val s = session()
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007")
        // The user typed it; the fallback would have recorded it too, and must not.
        s.sendText("make test")
        s.emulator.write("make test")
        s.sendKey(TerminalKey.ENTER)
        s.emulator.write("\r\n\u001b]133;C\u0007ok\r\n\u001b]133;D;0\u0007")
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("make test"), s.texts())
        // A cmdline parameter wins over the screen.
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007echo hi\r\n\u001b]133;C;cmdline=echo hi\u0007hi\r\n")
        assertEquals(listOf("make test", "echo hi"), s.texts())
    }

    /** The store folds a repeat of the host's latest into one (RoomRepositoriesTest); the session reports every run and leaves that to it. */
    @Test
    fun `every command run reaches the host's history, a repeat included`() = runTest {
        val s = session()
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007")
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007")
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007")
        assertEquals(listOf("ls", "ls", "pwd"), s.texts())
        assertTrue(store.recorded.all { it.first == host.commandHistoryKey })
    }

    /** Two tabs on one host write to the same history; a quick connect's goes under its login, not its throwaway id. */
    @Test
    fun `commands are keyed by the host, and a quick connect by its login`() = runTest {
        val s = session()
        val t = session(id = "t")
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007")
        t.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007")
        assertEquals(listOf("h", "h"), store.recorded.map { it.first })

        val quick = host.copy(id = Host.QUICK_ID_PREFIX + "abc", port = 2222)
        val q = TerminalSession(record("q").copy(hostId = quick.id, hostSnapshot = quick), backgroundScope, store) {}
        q.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007uptime\r\n\u001b]133;C\u0007")
        assertEquals("quick:demo@127.0.0.1:2222", store.recorded.last().first)
        assertEquals("demo@127.0.0.1:2222", Host.quickConnectLabel(store.recorded.last().first))
    }

    // ---- the frame -------------------------------------------------------------------------------

    /** The frame a session writes is text alone (version 3); the history is the host's and is not in it. */
    @Test
    fun `the frame is text alone and restoring it hands nothing over`() = runTest {
        val s = session()
        s.emulator.write("$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007a  b\r\n$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007/home/demo\r\n$PROMPT")
        val frame = s.snapshotFrame()
        assertEquals(3, java.io.DataInputStream(frame.inputStream()).use { it.readInt() })

        val back = session(id = "t")
        back.restoreFrame(frame)
        assertTrue(store.imported.isEmpty())
        assertTrue(back.emulator.screenText().any { it.contains("/home/demo") })
    }

    /** A frame the build before this one saved ends in the tab's commands; restoring it hands them to the host's history, once, and the next frame drops them. */
    @Test
    fun `a version 2 frame's commands are handed to the host's history`() = runTest {
        val old = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { d ->
                d.writeInt(2)
                d.writeInt(1)
                d.writeUTF("$PROMPT echo old")
                d.writeInt(2)
                d.writeUTF("ls -la"); d.writeLong(1_600_000_000_000L)
                d.writeUTF("echo old"); d.writeLong(1_600_000_001_000L)
            }
        }.toByteArray()
        val v2 = session(id = "u")
        v2.restoreFrame(old)
        assertEquals(listOf(host.commandHistoryKey to listOf("ls -la" to 1_600_000_000_000L, "echo old" to 1_600_000_001_000L)), store.imported)
        assertTrue(v2.emulator.screenText().first().contains("echo old"))
        assertTrue("handed over, not recorded again at the session's clock", store.recorded.isEmpty())

        // The frame written now is text alone; another restore of it hands nothing over.
        val again = session(id = "v")
        again.restoreFrame(v2.snapshotFrame())
        assertEquals(1, store.imported.size)
        assertTrue(again.emulator.screenText().first().contains("echo old"))
    }

    @Test
    fun `a text-only frame from the first build still restores`() = runTest {
        val old = java.io.ByteArrayOutputStream().also { out ->
            java.io.DataOutputStream(out).use { d ->
                d.writeInt(1)
                d.writeInt(1)
                d.writeUTF("$PROMPT echo old")
            }
        }.toByteArray()
        val v1 = session(id = "u")
        v1.restoreFrame(old)
        assertTrue(v1.texts().isEmpty())
        assertTrue(store.imported.isEmpty())
        assertTrue(v1.emulator.screenText().first().contains("echo old"))
    }
}

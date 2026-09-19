package app.berth.android.session

import app.berth.domain.model.Host
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
 * fed what the remote would have echoed, and the session's input methods stand for the keys.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandHistorySessionTest {
    private val host = Host(id = "h", name = "box", color = SwatchColor.MOSS, monogram = "B", address = "127.0.0.1", user = "demo", createdAt = 0)

    private fun record(id: String = "s") = SessionRecord(
        id = id, workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0,
    )

    private fun env(historyOn: Boolean = true) = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override fun onClipboardText(text: String) = Unit
        override fun commandHistoryEnabled(): Boolean = historyOn
        override fun now(): Long = 1_700_000_000_000L
    }

    private fun TestScope.session(historyOn: Boolean = true, id: String = "s") = TerminalSession(record(id), backgroundScope, env(historyOn)) {}

    private fun TerminalSession.texts() = commands.value.map { it.text }

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
        // Recorded before the command's output, so a command that clears the screen is not lost.
        assertEquals(listOf("ls -la"), s.texts())
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

    @Test
    fun `the same command run twice in a row is one entry`() = runTest {
        val s = session()
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007")
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007")
        s.emulator.write("\u001b]133;A\u0007$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007")
        assertEquals(listOf("ls", "pwd"), s.texts())
    }

    // ---- the frame -------------------------------------------------------------------------------

    @Test
    fun `the frame carries the history and a text-only frame still restores`() = runTest {
        val s = session()
        s.emulator.write("$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007a  b\r\n$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007/home/demo\r\n$PROMPT")
        val frame = s.snapshotFrame()

        val back = session(id = "t")
        back.restoreFrame(frame)
        assertEquals(listOf("ls", "pwd"), back.texts())
        assertEquals(1_700_000_000_000L, back.commands.value.first().at)
        assertTrue(back.emulator.screenText().any { it.contains("/home/demo") })

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
        assertTrue(v1.emulator.screenText().first().contains("echo old"))
    }

    @Test
    fun `removing an entry takes it out of the next frame`() = runTest {
        val s = session()
        s.emulator.write("$PROMPT\u001b]133;B\u0007ls\r\n\u001b]133;C\u0007$PROMPT\u001b]133;B\u0007pwd\r\n\u001b]133;C\u0007")
        s.removeCommand(s.commands.value.first())
        assertEquals(listOf("pwd"), s.texts())
        val back = session(id = "t")
        back.restoreFrame(s.snapshotFrame())
        assertEquals(listOf("pwd"), back.texts())
    }
}

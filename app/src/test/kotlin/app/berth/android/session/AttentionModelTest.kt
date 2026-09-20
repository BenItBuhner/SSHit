package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The attention model at the session (vision §4.5, spec C3): what an off-stage tab may raise its
 * ring for, and the ten-second floor under OSC 133 so a quick `ls` in a background tab is not
 * news. The clock is the environment's, so the threshold is tested to the millisecond; the
 * sequences go through the real emulator's OSC parser.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AttentionModelTest {
    private var clock = 1_700_000_000_000L

    /** Thrown from the environment's auth step, so a connect fails the way the network or the server would, without either. */
    private var authFailure: Throwable? = null

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = authFailure?.let { throw it } ?: emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        // Never emits and never completes, like the real monitor between outages.
        override val networkAvailable: Flow<Unit> = MutableSharedFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
        override fun now(): Long = clock
    }

    private val host = Host(id = "h", name = "homelab", color = SwatchColor.MOSS, monogram = "HL", address = "192.168.1.20", user = "ben", createdAt = 0)

    private fun record(state: SessionState = SessionState.DETACHED) = SessionRecord(
        id = "s", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = state, sortOrder = 0, createdAt = 0,
    )

    // ---- OSC 133 ---------------------------------------------------------------------------------

    @Test
    fun `a command that finishes under ten seconds off stage is not attention`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write(COMMAND_STARTED)
        clock += TerminalSession.ATTENTION_COMMAND_MS - 1
        session.emulator.write(commandFinished(0))
        assertFalse(session.record.value.needsAttention)
        assertNull(session.attentionAt)
    }

    @Test
    fun `a command that ran ten seconds finishes as attention, stamped when it finished`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write(COMMAND_STARTED)
        clock += TerminalSession.ATTENTION_COMMAND_MS
        session.emulator.write(commandFinished(0))
        assertTrue(session.record.value.needsAttention)
        assertEquals("Command finished", session.record.value.attentionReason)
        assertEquals(clock, session.attentionAt)
    }

    @Test
    fun `a long command that failed says so with its exit code`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write(COMMAND_STARTED)
        clock += TerminalSession.ATTENTION_COMMAND_MS * 3
        session.emulator.write(commandFinished(127))
        assertEquals("Command failed (127)", session.record.value.attentionReason)
    }

    @Test
    fun `a long command finishing on stage is seen, not attention`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = true
        session.emulator.write(COMMAND_STARTED)
        clock += TerminalSession.ATTENTION_COMMAND_MS * 2
        session.emulator.write(commandFinished(0))
        assertFalse(session.record.value.needsAttention)
    }

    @Test
    fun `a finish without a start, or a second finish, is ignored`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write(commandFinished(0))
        assertFalse(session.record.value.needsAttention)
        // The start is spent by its finish: a stray D an hour later does not count from the old C.
        session.emulator.write(COMMAND_STARTED)
        session.emulator.write(commandFinished(0))
        clock += 3_600_000
        session.emulator.write(commandFinished(0))
        assertFalse(session.record.value.needsAttention)
    }

    @Test
    fun `the threshold is measured from C, not from the prompt`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        // A prompt shown for a minute, then a quick command: the minute at the prompt is not the command's.
        session.emulator.write("\u001b]133;A\u0007")
        clock += 60_000
        session.emulator.write("\u001b]133;B\u0007")
        session.emulator.write(COMMAND_STARTED)
        clock += 500
        session.emulator.write(commandFinished(0))
        assertFalse(session.record.value.needsAttention)
    }

    // ---- bells, notices, seeing ------------------------------------------------------------------

    @Test
    fun `a bell off stage is attention, on stage it only rings`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = true
        session.emulator.write("\u0007")
        assertFalse(session.record.value.needsAttention)
        session.onStage = false
        session.emulator.write("\u0007")
        assertTrue(session.record.value.needsAttention)
        assertEquals("Bell", session.record.value.attentionReason)
        assertEquals(clock, session.attentionAt)
        assertNull("output, not a lost connection: away, this one is the Attention notification's", session.attentionProblem)
    }

    @Test
    fun `an OSC 9 notice off stage is attention under its text`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write("\u001b]9;Build finished\u0007")
        assertTrue(session.record.value.needsAttention)
        assertEquals("Build finished", session.record.value.attentionReason)
    }

    @Test
    fun `coming on stage clears attention and its timestamp`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write("\u0007")
        assertTrue(session.record.value.needsAttention)
        session.markSeen()
        assertFalse(session.record.value.needsAttention)
        assertNull(session.record.value.attentionReason)
        assertNull(session.attentionAt)
    }

    @Test
    fun `a later event moves the timestamp, so jump-to-unread finds the most recent`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.emulator.write("\u0007")
        val first = session.attentionAt
        clock += 5_000
        session.emulator.write("\u0007")
        assertEquals(first!! + 5_000, session.attentionAt)
    }

    // ---- losing the server (vision §4.5: "remote exit or unexpected disconnect") -----------------

    @Test
    fun `a reconnect that gives up off stage is attention, and the problem for the shade`() = runTest {
        authFailure = SshError.ConnectFailed(host.address, host.port, IOException("Network is unreachable"))
        val session = TerminalSession(record(), backgroundScope, env) {}
        val problems = ArrayList<SessionProblem>()
        backgroundScope.launch { session.problems.collect { problems += it } }
        session.onStage = false
        session.connect()
        runCurrent()
        assertEquals("a transient failure starts the backoff", SessionState.RECONNECTING, session.state)
        assertFalse("still trying, so nothing to say yet", session.record.value.needsAttention)
        // The host's window closes while the loop waits out its first second: the next failure is the last.
        clock += 16 * 60_000L
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SessionState.DETACHED, session.state)
        assertTrue(session.record.value.needsAttention)
        assertEquals("Couldn't reconnect", session.record.value.attentionReason)
        assertEquals(clock, session.attentionAt)
        assertEquals(listOf<SessionProblem>(SessionProblem.GaveUp(16 * 60_000L)), problems)
        assertEquals("the attention is the problem's, so the shade tells it once", problems.single(), session.attentionProblem)
        assertTrue(session.emulator.screenText().any { it.contains("gave up reconnecting") })
    }

    @Test
    fun `a sign-in the server refused off stage is attention in its own words`() = runTest {
        authFailure = SshError.AuthenticationFailed(host.user, null)
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = false
        session.connect()
        runCurrent()
        assertEquals(SessionState.FAILED, session.state)
        assertTrue(session.record.value.needsAttention)
        assertEquals("The server did not accept the credentials for ben.", session.record.value.attentionReason)
        assertEquals(clock, session.attentionAt)
        assertEquals(SessionProblem.Failed("The server did not accept the credentials for ben.", authentication = true), session.attentionProblem)
    }

    @Test
    fun `on stage a lost connection is seen as it happens, not attention`() = runTest {
        authFailure = SshError.AuthenticationFailed(host.user, null)
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.onStage = true
        session.connect()
        runCurrent()
        assertEquals(SessionState.FAILED, session.state)
        assertFalse(session.record.value.needsAttention)
        assertNull(session.attentionAt)
    }

    // ---- frames on relaunch ----------------------------------------------------------------------

    @Test
    fun `a frame restored for a tab that was live when killed ends in a detached marker dated to its last save`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        val detachedAt = clock - 4 * 60_000
        session.restoreFrame(frame(listOf("ben@homelab:~$ docker compose ps", "caddy   Up 3 days")), detachedAt = detachedAt)
        val screen = session.emulator.screenText()
        assertTrue(screen.any { it.contains("docker compose ps") })
        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(detachedAt))
        // The pill above it says Detached; the marker says the same word, the one Detach all and tmux use.
        assertTrue("detached marker with $stamp in ${screen.filter { it.isNotBlank() }}", screen.any { it.contains("detached $stamp") })
        assertFalse("no second word for the one state", screen.any { it.contains("paused") })
    }

    @Test
    fun `a frame restored for a tab that was already detached carries no new marker`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.restoreFrame(frame(listOf("ben@homelab:~$ ")), detachedAt = null)
        assertFalse(session.emulator.screenText().any { it.contains("detached") })
    }

    @Test
    fun `a tab without a saved frame still says it was detached`() = runTest {
        val session = TerminalSession(record(), backgroundScope, env) {}
        session.restoreFrame(null, detachedAt = clock)
        assertTrue(session.emulator.screenText().any { it.contains("detached") })
    }

    @Test
    fun `snapshot and restore round-trip the screen`() = runTest {
        val a = TerminalSession(record(), backgroundScope, env) {}
        a.emulator.write("first line\r\nsecond line\r\n")
        val b = TerminalSession(record(), backgroundScope, env) {}
        b.restoreFrame(a.snapshotFrame())
        val text = b.emulator.screenText()
        assertEquals("first line", text[0].trimEnd())
        assertEquals("second line", text[1].trimEnd())
    }

    @Test
    fun `markLive only re-dates a live tab`() = runTest {
        val session = TerminalSession(record(SessionState.DETACHED), backgroundScope, env) {}
        session.markLive()
        assertNull(session.record.value.lastLiveAt)
    }

    companion object {
        private const val COMMAND_STARTED = "\u001b]133;C\u0007"
        private fun commandFinished(exit: Int) = "\u001b]133;D;$exit\u0007"

        fun frame(lines: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(1)
                d.writeInt(lines.size)
                lines.forEach(d::writeUTF)
            }
            return out.toByteArray()
        }
    }
}

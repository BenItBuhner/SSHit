package app.berth.android.session

import android.app.Application
import app.berth.domain.model.Host
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a shell's echo does to [TerminalSession.echoOff], against the local sshd (spec C2's
 * password prompt): typing at the prompt is echoed and raises nothing; at `read -s` it is not, and
 * the session says so until Enter or Ctrl+C ends the line; a full-screen program is not read at
 * all, since a key there need not draw. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EchoOffSessionTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ArrayList<TerminalSession>()

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = listOf(SshAuth.Password { sshPassword.toCharArray() })
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
    }

    private val host = Host(id = "box", name = "box", color = SwatchColor.TEAL, monogram = "BX", address = sshHost, port = sshPort, user = sshUser, createdAt = 0)

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    @Test
    fun `typing at the prompt is echoed, at read -s it is not until Enter, and Ctrl+C ends it too`() {
        val s = live()
        type(s, "echo typed")
        await("the echo") { s.emulator.screenText().any { it.trimEnd().endsWith("echo typed") } }
        Thread.sleep(EchoWatch.ECHO_OFF_AFTER_MS * 2)
        assertFalse("an echoed line raises nothing", s.echoOff.value)
        s.sendKey(TerminalKey.ENTER)
        await("the command") { s.emulator.screenText().any { it.trim() == "typed" } }

        s.sendText("read -rs secret; echo read-done\r")
        Thread.sleep(300)
        type(s, "hunter2")
        await("the silence read as echo off", 5_000) { s.echoOff.value }
        s.sendKey(TerminalKey.ENTER)
        assertFalse("Enter ends the line at once", s.echoOff.value)
        await("read to return") { s.emulator.screenText().any { it.trim() == "read-done" } }
        assertFalse("nothing typed at read -s reached the screen", s.emulator.screenText().any { it.contains("hunter2") })

        s.sendText("read -rs again\r")
        Thread.sleep(300)
        type(s, "abc")
        await("the silence again", 5_000) { s.echoOff.value }
        s.sendControl('c')
        assertFalse("Ctrl+C ends it too", s.echoOff.value)
    }

    @Test
    fun `a full-screen program's silence is not read as echo off`() {
        val s = live()
        s.sendText("printf '\\033[?1049h'; read -rs x; printf '\\033[?1049l'\r")
        await("the alternate screen") { s.emulator.isAlternateScreen }
        type(s, "quiet")
        Thread.sleep(EchoWatch.ECHO_OFF_AFTER_MS * 2)
        assertFalse(s.echoOff.value)
        s.sendControl('c')
    }

    private fun live(): TerminalSession {
        val record = SessionRecord(id = "s${sessions.size + 1}", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)
        val session = TerminalSession(record, scope, env) {}
        sessions += session
        session.connect()
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        await("the prompt") { session.emulator.screenText().any { it.contains("$") } }
        return session
    }

    /** Key by key, as a keyboard types: each character its own write. */
    private fun type(session: TerminalSession, text: String) {
        for (ch in text) {
            session.sendText(ch.toString())
            Thread.sleep(40)
        }
    }

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

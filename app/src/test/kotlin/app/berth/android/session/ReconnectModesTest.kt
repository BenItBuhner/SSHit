package app.berth.android.session

import android.app.Application
import app.berth.domain.model.Host
import app.berth.domain.model.PersistencePolicy
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshSecurity
import app.berth.terminal.KeyboardProtocol
import app.berth.terminal.Mod
import app.berth.terminal.MouseTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A program's modes end with the shell it ran in, against the local sshd: a full-screen program
 * that turned on kitty's keyboard flags, modifyOtherKeys, any-motion mouse reports and application
 * cursor keys on the alternate screen is cut off by a detach, and the fresh login
 * shell the reconnect opens gets a terminal that asks for none of it, on the main screen with the
 * frame kept, where Ctrl+C is 0x03 and interrupts. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReconnectModesTest {
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

    private val host = Host(
        id = "box", name = "box", color = SwatchColor.TEAL, monogram = "BX", address = sshHost, port = sshPort, user = sshUser,
        createdAt = 0, persistence = PersistencePolicy(keepaliveSeconds = 120),
    )

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
    fun `the fresh shell a reconnect opens gets none of the last program's modes, the main screen and its frame kept, and Ctrl+C interrupts`() {
        val session = live()
        val em = session.emulator
        session.sendText("echo before-the-program\r")
        await("the shell answers") { lines(session).any { it.trim() == "before-the-program" } }
        session.sendText("printf '\\033[?1049h\\033[>1u\\033[>4;2m\\033[?1003h\\033[?1006h\\033[?1h'; sleep 600\r")
        await("the program's modes") { em.isAlternateScreen && em.keyboardProtocol.kittyFlags == 1 && em.mouseTracking == MouseTracking.ANY_EVENT && em.applicationCursorKeys }

        session.detach()
        await("the detach") { session.state == SessionState.DETACHED }
        assertEquals("the kept frame is the program's until a shell takes it", MouseTracking.ANY_EVENT, em.mouseTracking)

        session.reconnectNow()
        await("the reconnect", 45_000) { session.state == SessionState.LIVE && lines(session).any { it.contains("reconnected") } }
        await("the fresh shell's prompt") { lines(session).dropWhile { !it.contains("reconnected") }.drop(1).any { it.contains("$") } }
        assertEquals(KeyboardProtocol.LEGACY, em.keyboardProtocol)
        assertEquals(MouseTracking.NONE, em.mouseTracking)
        assertFalse(em.mouseSgrEncoding)
        // Bracketed paste is not asked here: bash 5.1 and later turn it on at every prompt of their own.
        assertFalse(em.applicationCursorKeys)
        assertFalse("back on the main screen", em.isAlternateScreen)
        val screen = lines(session)
        assertEquals(screen.joinToString("\n"), 1, screen.count { it.trim() == "before-the-program" })

        val sent = CopyOnWriteArrayList<ByteArray>()
        session.sendText("sleep 30; echo slept-through\r")
        await("the command's echo") { lines(session).any { it.contains("sleep 30; echo slept-through") } }
        session.sendObserver = { sent += it }
        session.sendText("c", Mod.CTRL)
        session.sendObserver = null
        assertEquals("Ctrl+C is ETX", listOf(0x03), sent.single().map { it.toInt() })
        session.sendText("echo after-the-interrupt\r")
        await("the interrupted shell's next command, long before the sleep would end") { lines(session).any { it.trim() == "after-the-interrupt" } }
        assertFalse(lines(session).any { it.trim() == "slept-through" })
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun live(): TerminalSession {
        val record = SessionRecord(id = "s${sessions.size + 1}", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)
        val session = TerminalSession(record, scope, env) {}
        sessions += session
        session.connect()
        await("the login to be live", 45_000) { session.state == SessionState.LIVE }
        await("the prompt") { session.emulator.screenText().any { it.contains("$") } }
        return session
    }

    /** History and screen, top to bottom. */
    private fun lines(session: TerminalSession): List<String> {
        val em = session.emulator
        return synchronized(em.lock) {
            val sb = em.scrollbackSize
            (0 until sb).map { em.viewLine(0, sb - it).toText() } + em.screenText()
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

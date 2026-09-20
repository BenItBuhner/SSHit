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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A transport failure is told to the environment as it is told to the terminal (the gap audit's
 * crash handling: the same capture path for connection failures, which used to only log). One
 * call per failure, naming the phase, carrying what the transport threw, and never one while
 * the retries still run.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportFailureReportTest {
    private var clock = 1_700_000_000_000L
    private var authFailure: Throwable? = null
    private val failures = ArrayList<Failure>()

    private data class Failure(val host: String, val phase: String, val error: Throwable?, val detail: String)

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = authFailure?.let { throw it } ?: emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = MutableSharedFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
        override fun now(): Long = clock
        override fun onTransportFailure(host: Host, phase: String, error: Throwable?, detail: String) {
            failures += Failure(host.name, phase, error, detail)
        }
    }

    private val host = Host(id = "h", name = "homelab", color = SwatchColor.MOSS, monogram = "HL", address = "192.168.1.20", user = "ben", createdAt = 0)
    private val record = SessionRecord(id = "s", workspaceId = "home", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, sortOrder = 0, createdAt = 0)

    @Test
    fun `a sign-in the server refused is one report, with the error and the words the terminal shows`() = runTest {
        val error = SshError.AuthenticationFailed(host.user, null)
        authFailure = error
        val session = TerminalSession(record, backgroundScope, env) {}
        session.connect()
        runCurrent()
        assertEquals(SessionState.FAILED, session.state)
        assertEquals(1, failures.size)
        val f = failures.single()
        assertEquals("homelab", f.host)
        assertEquals("Couldn't connect", f.phase)
        assertSame(error, f.error)
        assertEquals("The server did not accept the credentials for ben.", f.detail)
    }

    @Test
    fun `retries report nothing until the window closes, then once, with the last attempt's error`() = runTest {
        val error = SshError.ConnectFailed(host.address, host.port, IOException("Network is unreachable"))
        authFailure = error
        val session = TerminalSession(record, backgroundScope, env) {}
        session.connect()
        runCurrent()
        assertEquals(SessionState.RECONNECTING, session.state)
        assertTrue("still trying, so nothing to report yet", failures.isEmpty())
        clock += 16 * 60_000L
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(SessionState.DETACHED, session.state)
        val f = failures.single()
        assertEquals("homelab", f.host)
        assertEquals("Gave up reconnecting", f.phase)
        assertSame(error, f.error)
        assertEquals("after 960 s and 1 retry", f.detail)
    }
}

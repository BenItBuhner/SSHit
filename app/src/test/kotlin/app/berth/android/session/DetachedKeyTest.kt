package app.berth.android.session

import android.app.Application
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.Workspace
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.terminal.TerminalKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A key typed into a detached tab (review #15): the tab reconnects, as the pill's own action
 * would, and says the key itself went nowhere, once; the keys that follow while the connection is
 * being made are dropped in silence, as they always were, and a tab that is not detached is left
 * to its own state by a key with no shell to take it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DetachedKeyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ArrayList<TerminalSession>()

    private val env = object : SessionEnvironment {
        override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
        override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
        override val networkAvailable: Flow<Unit> = emptyFlow()
        override fun onClipboardText(host: Host, text: String) = Unit
    }

    @After
    fun tearDown() {
        sessions.forEach { it.close() }
        scope.cancel()
    }

    /** A tab on a host at a port nothing listens on, so a reconnect is refused at once rather than left to a timeout. */
    private fun session(state: SessionState): TerminalSession {
        val now = System.currentTimeMillis()
        val host = Host(
            id = "box", name = "box", color = SwatchColor.SLATE, monogram = Host.monogramFor("box"), address = "127.0.0.1", port = 1, user = "demo",
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")), lastConnectedAt = now - TimeUnit.MINUTES.toMillis(5), createdAt = now - TimeUnit.DAYS.toMillis(1),
        )
        val record = SessionRecord(
            id = "s-box", workspaceId = Workspace.DEFAULT_ID, hostId = host.id, hostSnapshot = host, state = state,
            layer = if (state == SessionState.LIVE) PersistenceLayer.IN_APP else PersistenceLayer.LOCAL_FRAME, title = host.name,
            cwd = "~", lastCommand = "ls", sortOrder = 0, createdAt = now - TimeUnit.HOURS.toMillis(1), lastLiveAt = now - TimeUnit.MINUTES.toMillis(5),
        )
        return TerminalSession(record, scope, env) {}.also { sessions += it }
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** Collects the session's key reports; subscribed before the key, since the flow keeps nothing for a late reader. */
    private fun reportsOf(session: TerminalSession): CopyOnWriteArrayList<Unit> {
        val reports = CopyOnWriteArrayList<Unit>()
        val subscribed = CountDownLatch(1)
        scope.launch {
            session.keyReconnected.onSubscription { subscribed.countDown() }.collect { reports += it }
        }
        assertTrue(subscribed.await(5, TimeUnit.SECONDS))
        return reports
    }

    @Test
    fun `the first key into a detached tab reconnects it and is reported as not sent, once`() {
        val session = session(SessionState.DETACHED)
        val reports = reportsOf(session)
        session.sendKey(TerminalKey.ENTER)
        await("the key reported") { reports.size == 1 }
        await("the tab leaves Detached") { session.state != SessionState.DETACHED }
        // Text typed while the connection is being made is dropped as before: nothing more is said.
        session.sendText("ls\n")
        session.sendKey(TerminalKey.ENTER)
        Thread.sleep(200)
        assertEquals(1, reports.size)
        assertNotEquals(SessionState.DETACHED, session.state)
    }

    @Test
    fun `a paste into a detached tab reconnects it the same way`() {
        val session = session(SessionState.DETACHED)
        val reports = reportsOf(session)
        session.paste("docker compose ps\n")
        await("the paste reported") { reports.size == 1 }
        await("the tab leaves Detached") { session.state != SessionState.DETACHED }
    }

    @Test
    fun `a key into a tab that is not detached says nothing and changes nothing`() {
        for (state in listOf(SessionState.LIVE, SessionState.CLOSED, SessionState.FAILED)) {
            val session = session(state)
            val reports = reportsOf(session)
            session.sendKey(TerminalKey.ENTER)
            session.sendText("x")
            Thread.sleep(150)
            assertTrue("$state: no report", reports.isEmpty())
            assertEquals(state, session.state)
        }
    }
}

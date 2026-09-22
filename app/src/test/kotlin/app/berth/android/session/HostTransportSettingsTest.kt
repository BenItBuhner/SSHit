package app.berth.android.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.PersistenceLayer
import app.berth.domain.model.SessionRecord
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TerminalSettings
import app.berth.ssh.AcceptAllHostKeys
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.SshAuth
import app.berth.ssh.SshCiphers
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The host editor's Advanced › Scrollback and Advanced › Ciphers through the production session
 * stack. Scrollback: the cap a tab keeps is the saved host's, or Settings › Terminal › Scrollback
 * under Inherit, held from the moment the tab exists rather than once it is on stage; an edit to
 * either reaches a tab already open, and a frame kept under a wide cap is restored whole. Ciphers:
 * the host's list is what the login offers, and a server that accepts none of it fails the tab at
 * once, in words that name the row, rather than retrying a mismatch no retry can fix. The ciphers
 * tests need the local sshds and are skipped unless `SSH_TEST_*` is set; the scrollback ones do not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HostTransportSettingsTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull() ?: 0
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val framers = ArrayList<TerminalSession>()

    /** Every state each tab passed through, in order, by tab id. */
    private val seen = CopyOnWriteArrayList<Pair<String, SessionState>>()

    private val box = Host(
        id = "box",
        name = "box",
        color = SwatchColor.TEAL,
        monogram = "BX",
        address = sshHost.ifBlank { "127.0.0.1" },
        port = sshPort,
        user = sshUser.ifBlank { "berth" },
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("box")),
        createdAt = 0L,
    )

    @Before
    fun setUp() {
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        bg.launch { graph.prompts.current.collect { if (it is Prompt.TrustHostKey) it.trust() } }
    }

    @After
    fun tearDown() {
        framers.forEach { it.close() }
        if (::graph.isInitialized) graph.close()
        bg.cancel()
    }

    // ---- scrollback --------------------------------------------------------------------------------

    @Test
    fun `a host's own scrollback caps its tab, an edit reaches the open tab, and Inherit follows Settings`(): Unit = runBlocking {
        val host = box.copy(scrollbackLines = 50_000)
        graph.hosts.upsert(host)
        detachedTab("s-box", host, frameOf(30_000))
        graph.sessions.restore()
        val tab = graph.sessions.get("s-box")!!

        await(5_000, "the host's cap") { cap(tab) == 50_000 }
        assertEquals("the frame kept under the host's cap came back whole", "line 1", oldest(tab))
        assertTrue(screen(tab).contains("line 30000"))

        graph.hosts.upsert(host.copy(scrollbackLines = 2_000))
        await(5_000, "the edit to reach the open tab") { cap(tab) == 2_000 }
        assertEquals(2_000, tab.emulator.scrollbackSize)
        assertTrue("the oldest lines go, the newest stay", screen(tab).contains("line 30000"))

        graph.hosts.upsert(host.copy(scrollbackLines = null))
        await(5_000, "Inherit to take the app's") { cap(tab) == TerminalSettings.DEFAULT_SCROLLBACK }
        graph.settings.updateTerminalSettings { it.copy(scrollbackLines = 20_000) }
        await(5_000, "the Settings change to reach the open tab") { cap(tab) == 20_000 }

        // A host with a cap of its own does not move with Settings.
        graph.hosts.upsert(host.copy(scrollbackLines = 5_000))
        await(5_000, "the host's cap again") { cap(tab) == 5_000 }
        graph.settings.updateTerminalSettings { it.copy(scrollbackLines = 100_000) }
        delay(300)
        assertEquals(5_000, cap(tab))
    }

    /**
     * After process death, with no Stage composed: a tab on Inherit under a wide app cap, and one
     * on a host whose cap is below the frame. The first comes back whole rather than held to the
     * emulator's default until a Stage sets it; the second keeps the newest lines its cap allows.
     */
    @Test
    fun `a restored frame is held to the tab's cap, not the emulator's default, with no Stage to set it`(): Unit = runBlocking {
        graph.settings.updateTerminalSettings { it.copy(scrollbackLines = 50_000) }
        val wide = box.copy(id = "wide", name = "wide")
        val narrow = box.copy(id = "narrow", name = "narrow", scrollbackLines = 2_000)
        graph.hosts.upsert(wide)
        graph.hosts.upsert(narrow)
        detachedTab("s-wide", wide, frameOf(30_000), sortOrder = 0)
        detachedTab("s-narrow", narrow, frameOf(30_000), sortOrder = 1)
        graph.sessions.restore()
        val wideTab = graph.sessions.get("s-wide")!!
        val narrowTab = graph.sessions.get("s-narrow")!!

        await(5_000, "the app's cap on the Inherit tab") { cap(wideTab) == 50_000 }
        await(5_000, "the host's cap on the other") { cap(narrowTab) == 2_000 }
        assertEquals("line 1", oldest(wideTab))
        assertEquals(2_000, narrowTab.emulator.scrollbackSize)
        assertTrue(screen(narrowTab).contains("line 30000"))
    }

    // ---- ciphers -----------------------------------------------------------------------------------

    @Test
    fun `a host offering only ciphers the server lacks fails at once in words that name the row, and Modern only connects`(): Unit = runBlocking {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
        val legacy = box.copy(ciphers = listOf("3des-cbc"))
        graph.hosts.upsert(legacy)
        graph.sessions.restore()

        val tab = graph.sessions.connect(legacy)
        watch(tab)
        await(20_000, "the login to fail") { tab.state == SessionState.FAILED }
        assertEquals("The server accepts none of the ciphers set under Advanced \u203A Ciphers.", tab.failure.value?.plain)
        assertFalse("a mismatch is not retried", seen.any { it.first == tab.id && it.second == SessionState.RECONNECTING })

        val modern = legacy.copy(ciphers = SshCiphers.MODERN)
        graph.hosts.upsert(modern)
        val again = graph.sessions.connect(modern)
        await(45_000, "Modern only to connect") { again.state == SessionState.LIVE }
    }

    @Test
    fun `a jump host offering only ciphers the server lacks is named as the hop that failed`(): Unit = runBlocking {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_JUMP_PORT not set", jumpPort > 0)
        val bastion = box.copy(
            id = "bastion",
            name = "bastion",
            port = jumpPort,
            auth = AuthMethod.Password(AuthResolver.passwordSecretId("bastion")),
            ciphers = listOf("3des-cbc"),
        )
        val target = box.copy(jumpHostIds = listOf(bastion.id))
        graph.secrets.put(AuthResolver.passwordSecretId(bastion.id), sshPassword.toByteArray())
        graph.secrets.put(AuthResolver.passwordSecretId(target.id), sshPassword.toByteArray())
        graph.hosts.upsert(bastion)
        graph.hosts.upsert(target)
        graph.sessions.restore()

        val tab = graph.sessions.connect(target)
        watch(tab)
        await(20_000, "the login to fail at the hop") { tab.state == SessionState.FAILED }
        val failure = tab.failure.value!!
        assertEquals("bastion (jump host) accepts none of the ciphers set under Advanced \u203A Ciphers.", failure.plain)
        assertEquals(FailedHop("bastion", "bastion"), failure.hop)
        assertFalse(seen.any { it.first == tab.id && it.second == SessionState.RECONNECTING })

        // The bastion's list put back to Default, the chain is made.
        graph.hosts.upsert(bastion.copy(ciphers = emptyList()))
        graph.sessions.reconnect(tab.id)
        await(45_000, "the chain to be made") { tab.state == SessionState.LIVE }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private suspend fun detachedTab(id: String, host: Host, frame: ByteArray, sortOrder: Int = 0) {
        graph.sessionRecords.upsert(
            SessionRecord(
                id = id,
                workspaceId = graph.workspaces.ensureDefault().id,
                hostId = host.id,
                hostSnapshot = host,
                state = SessionState.DETACHED,
                layer = PersistenceLayer.LOCAL_FRAME,
                sortOrder = sortOrder,
                createdAt = 1L,
            ),
        )
        graph.sessionRecords.saveFrame(id, frame)
    }

    /** A frame as a tab writes one ([TerminalSession.snapshotFrame]): [lines] numbered lines, kept under the widest cap. */
    private suspend fun frameOf(lines: Int): ByteArray {
        val env = object : SessionEnvironment {
            override suspend fun authFor(host: Host): List<SshAuth> = emptyList()
            override fun hostKeyPolicyFor(host: Host): HostKeyPolicy = AcceptAllHostKeys
            override val networkAvailable: Flow<Unit> = emptyFlow()
            override fun onClipboardText(host: Host, text: String) = Unit
        }
        val host = box.copy(id = "framer", scrollbackLines = TerminalSettings.MAX_SCROLLBACK)
        val record = SessionRecord(id = "framer-${framers.size}", workspaceId = "w", hostId = host.id, hostSnapshot = host, state = SessionState.DETACHED, createdAt = 0L)
        val framer = TerminalSession(record, bg, env) {}
        framers += framer
        await(5_000, "the framer's cap") { cap(framer) == TerminalSettings.MAX_SCROLLBACK }
        framer.emulator.write((1..lines).joinToString("\r\n") { "line $it" })
        return framer.snapshotFrame()
    }

    private fun watch(tab: TerminalSession) {
        bg.launch { tab.record.collect { seen += tab.id to it.state } }
    }

    /** The tab's cap, read under the emulator's lock so a lowering is seen only once its evictions are done. */
    private fun cap(tab: TerminalSession): Int = synchronized(tab.emulator.lock) { tab.emulator.maxScrollback }

    private fun oldest(tab: TerminalSession): String =
        synchronized(tab.emulator.lock) { tab.emulator.viewLine(0, tab.emulator.scrollbackSize).toText().trim() }

    private fun screen(tab: TerminalSession): String = tab.emulator.screenText().joinToString("\n")

    private suspend fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

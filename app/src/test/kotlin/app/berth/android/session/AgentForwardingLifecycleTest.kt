package app.berth.android.session

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import app.berth.android.diagnostics.BerthLog
import app.berth.android.screenshots.TestGraph
import app.berth.android.security.FakeAuthenticator
import app.berth.android.security.FakeKeystore
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.domain.model.TabKind
import app.berth.ssh.AgentSignPurpose
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Agent forwarding through the production session stack against the two local sshds: a tab on the
 * first logs in with the phone's Keystore key (the software stand-in holding the P-256 key in the
 * test user's authorized_keys), forwards its agent, and the programs a user runs there, `ssh-add`
 * and `ssh` on to the second sshd, talk to it through the `SSH_AUTH_SOCK` sshd makes. The user's
 * answers go through [Prompt.AgentRequest], the object the sheet's buttons call; the app is away
 * throughout, so every tab is off stage and a waiting request lights the ring and the shade.
 * Skipped unless `SSH_TEST_*`, `SSH_TEST_JUMP_PORT` and `SSH_TEST_P256_KEY_FILE` are set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AgentForwardingLifecycleTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val jumpPort = System.getenv("SSH_TEST_JUMP_PORT").orEmpty().toIntOrNull() ?: 0
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val p256KeyFile = System.getenv("SSH_TEST_P256_KEY_FILE").orEmpty()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var graph: TestGraph
    private lateinit var keystore: FakeKeystore
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    /** Every sign request the user was asked about, in order. */
    private val asked = CopyOnWriteArrayList<Prompt.AgentRequest>()

    private val box = Host(
        id = "agent-box",
        name = "agent-box",
        color = SwatchColor.TEAL,
        monogram = "AB",
        address = sshHost,
        port = sshPort,
        user = sshUser,
        auth = AuthMethod.Key("id-phone"),
        agentForwarding = true,
        createdAt = 0L,
    )

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        assumeTrue("SSH_TEST_JUMP_PORT not set", jumpPort > 0)
        assumeTrue("SSH_TEST_P256_KEY_FILE not set", p256KeyFile.isNotBlank())
        SshSecurity.ensureProviders()
        graph = TestGraph(app)
        val loaded = SshKeys.load(File(p256KeyFile).readText())
        keystore = FakeKeystore(KeyAuthModel.NONE, KeyPair(loaded.public, loaded.private))
        graph.keystore = keystore
        bg.launch {
            graph.prompts.current.collect { prompt ->
                when (prompt) {
                    is Prompt.TrustHostKey -> prompt.trust()
                    is Prompt.AgentRequest -> asked += prompt
                    else -> Unit
                }
            }
        }
        runBlocking {
            graph.identities.insert(
                Identity(
                    "id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC,
                    SshKeys.openSshPublic(keystore.pair.public, "berth@pixel"), SshKeys.fingerprintSha256(keystore.pair.public), "berth@pixel",
                    keystoreAlias = "berth-id-phone", createdAt = 0L,
                ),
                null,
            )
            graph.hosts.upsert(box)
            graph.sessions.restore()
        }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.notifier.refresh()
        graph.process.start()
        graph.process.stop()
    }

    @After
    fun tearDown() {
        if (!::graph.isInitialized) return
        graph.close()
        bg.cancel()
    }

    // ---- listing and signing ---------------------------------------------------------------------

    @Test
    fun `with forwarding on, ssh-add on the first host lists the one key that logged in, unasked`(): Unit = runBlocking {
        val session = openLive(box)
        val sh = remote(session)
        val (sock, _) = sh.run("printf '[%s]\\n' \"\$SSH_AUTH_SOCK\"")
        assertNotEquals("the server made an SSH_AUTH_SOCK", "[]", sock)

        val (listed, status) = sh.run("ssh-add -L")
        assertEquals(listed, 0, status)
        assertEquals(listOf(SshKeys.openSshPublic(keystore.pair.public, "berth@pixel")), listed.lines().filter { it.isNotBlank() })
        assertTrue("listing needs no answer", asked.isEmpty())
        assertFalse(session.record.value.needsAttention)
        assertTrue(BerthLog.ring.snapshot().any { it.contains("[agent-box] agent forwarding on; the agent holds ecdsa-sha2-nistp256") })
    }

    @Test
    fun `a hop to the second sshd waits on the user with the ring lit, signs after Allow once, and the next one's Deny falls through`(): Unit = runBlocking {
        val session = openLive(box)
        val sh = remote(session)
        assertFalse("the app is away: no tab is on stage", session.onStage)

        val hop = sh.start(hop("hop-ok"))
        val ask = awaitValue(20_000, "the sign request") { asked.firstOrNull() }
        assertEquals(box.id, ask.host.id)
        assertEquals("this phone", ask.keyName)
        val login = ask.purpose as AgentSignPurpose.Login
        assertEquals(sshUser, login.user)
        assertNotNull("OpenSSH binds the login to the second sshd's key, which the sheet shows", login.serverFingerprint)
        assertTrue(session.record.value.needsAttention)
        assertEquals(TerminalSession.AGENT_REQUEST_REASON, session.record.value.attentionReason)
        await(5_000, "the shade carries it while the app is away") { attentionPosted(session) }
        assertEquals("signature request", graph.notifier.attentionText(session.record.value))

        ask.allowOnce()
        val (said, status) = hop.await()
        assertEquals(said, 0, status)
        assertTrue(said, said.contains("hop-ok on") && said.contains(" $jumpPort"))
        await(5_000, "the ring goes dark once answered") { !session.record.value.needsAttention }
        await(5_000, "and the shade with it") { !attentionPosted(session) }

        // Once was once: the next hop asks again, and Deny answers the remote with an agent failure.
        val second = sh.start(hop("not-run"))
        val again = awaitValue(20_000, "the second request") { asked.getOrNull(1) }
        assertTrue(session.record.value.needsAttention)
        again.deny()
        val (refused, refusedStatus) = second.await()
        assertEquals(refused, 255, refusedStatus)
        assertTrue(refused, refused.contains("Permission denied"))
        assertFalse(refused, refused.contains("not-run"))
        await(5_000, "dark again") { !session.record.value.needsAttention }
        assertEquals("the tab stays up", SessionState.LIVE, session.state)
        assertEquals(0, sh.run("true").second)
        assertEquals(2, asked.size)
    }

    @Test
    fun `Allow for this session answers the tab's later requests, and a new tab asks again`(): Unit = runBlocking {
        val session = openLive(box)
        val sh = remote(session)
        val first = sh.start(hop("hop-ok"))
        awaitValue(20_000, "the sign request") { asked.firstOrNull() }.allowForSession()
        assertEquals(0, first.await().second)

        val (said, status) = sh.run(hop("hop-ok"))
        assertEquals(said, 0, status)
        assertEquals("the second hop was not asked about", 1, asked.size)
        assertFalse(session.record.value.needsAttention)

        graph.sessions.close(session.id)
        val next = remote(openLive(box))
        val third = next.start(hop("hop-ok"))
        awaitValue(20_000, "the new tab's request") { asked.getOrNull(1) }.allowOnce()
        assertEquals(0, third.await().second)
    }

    @Test
    fun `a host set to sign silently asks nothing, and a biometric key still prompts for each signature`(): Unit = runBlocking {
        graph.security.setHostAgentSilent(box.id, true)
        val session = openLive(box)
        val sh = remote(session)
        val (said, status) = sh.run(hop("hop-ok"))
        assertEquals(said, 0, status)
        assertTrue(said, said.contains("hop-ok on"))
        assertTrue(asked.isEmpty())

        keystore.model = KeyAuthModel.PER_USE
        repeat(2) { n ->
            val pending = sh.start(hop("hop-ok"))
            val unlock = awaitValue(20_000, "the biometric sheet") { graph.prompts.current.value as? Prompt.UnlockKey }
            assertTrue(unlock.forwarded)
            assertEquals(box.id, unlock.host.id)
            await(5_000, "the system prompt") { graph.authenticator.requests.size == n + 1 }
            assertEquals("Sign for agent-box", graph.authenticator.requests[n].title)
            graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
            assertEquals(0, pending.await().second)
        }
        assertTrue("silent skips the question, not the key's own prompt", asked.isEmpty())
    }

    @Test
    fun `a software key is held and signs the same way`(): Unit = runBlocking {
        graph.identities.insert(
            Identity(
                "id-file", "imported p256", KeyAlgorithm.ECDSA_P256, KeyStorage.SOFTWARE_ENCRYPTED, KeyProtection.NONE,
                SshKeys.openSshPublic(keystore.pair.public, ""), SshKeys.fingerprintSha256(keystore.pair.public), "",
                createdAt = 0L,
            ),
            File(p256KeyFile).readBytes(),
        )
        val onFile = box.copy(id = "agent-file", name = "agent-file", auth = AuthMethod.Key("id-file"))
        graph.hosts.upsert(onFile)
        val sh = remote(openLive(onFile))
        val (listed, _) = sh.run("ssh-add -L")
        assertEquals("a key with no comment goes by its name", listOf(SshKeys.openSshPublic(keystore.pair.public, "imported p256")), listed.lines().filter { it.isNotBlank() })

        val hop = sh.start(hop("hop-ok"))
        val ask = awaitValue(20_000, "the sign request") { asked.firstOrNull() }
        assertEquals("imported p256", ask.keyName)
        ask.allowOnce()
        val (said, status) = hop.await()
        assertEquals(said, 0, status)
        assertTrue(said, said.contains("hop-ok on"))
    }

    // ---- where there is no agent -----------------------------------------------------------------

    @Test
    fun `with forwarding off the first host has no SSH_AUTH_SOCK`(): Unit = runBlocking {
        val plain = box.copy(agentForwarding = false)
        graph.hosts.upsert(plain)
        val sh = remote(openLive(plain))
        assertEquals("[]", sh.run("printf '[%s]\\n' \"\$SSH_AUTH_SOCK\"").first)
        val (said, status) = sh.run("ssh-add -L")
        assertEquals(said, 2, status)
        val (hopped, hopStatus) = sh.run(hop("not-run"))
        assertEquals(hopped, 255, hopStatus)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a Tunnels only tab has no agent, where a terminal on the same host has one`(): Unit = runBlocking {
        val carrier = box.copy(id = "agent-tunnels", name = "agent-tunnels", tunnelsOnly = true)
        graph.hosts.upsert(carrier)
        val tunnels = graph.sessions.openTunnels(carrier)
        assertEquals(TabKind.Tunnels, tunnels.kind)
        await(45_000, "the Tunnels tab live") { tunnels.state == SessionState.LIVE }
        delay(500)
        assertTrue(BerthLog.ring.snapshot().none { it.contains("[agent-tunnels] agent forwarding") })

        val terminal = graph.sessions.openTerminalFor(tunnels.id)!!
        await(45_000, "the terminal live") { terminal.state == SessionState.LIVE }
        await(5_000, "the terminal's agent") { BerthLog.ring.snapshot().any { it.contains("[agent-tunnels] agent forwarding on") } }
        assertNotEquals("[]", remote(terminal).run("printf '[%s]\\n' \"\$SSH_AUTH_SOCK\"").first)
        assertTrue(asked.isEmpty())
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private suspend fun openLive(host: Host): TerminalSession {
        val session = graph.sessions.open(host)
        await(45_000, "${host.name} live") { session.state == SessionState.LIVE }
        return session
    }

    /** `ssh` from the first sshd to the second with the forwarded agent as its only way in; [word] is printed there, split as typed so only its output matches. */
    private fun hop(word: String): String =
        "ssh -n -p $jumpPort -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o BatchMode=yes " +
            "-o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o IdentityFile=none -o LogLevel=ERROR " +
            "$sshUser@$sshHost 'echo ${word.take(3)}\"\"${word.drop(3)} on \$SSH_CONNECTION'"

    private fun attentionPosted(session: TerminalSession): Boolean =
        shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(SessionNotifier.attentionTag(session.id), 2) != null

    /** The tab's shell, ready to run commands ([Remote]). */
    private suspend fun remote(session: TerminalSession): Remote = Remote(session).apply {
        session.resize(400, 40)
        run("stty -echo; bind 'set enable-bracketed-paste off' 2>/dev/null; PS1=''; PS2=''; export PS1 PS2")
    }

    /**
     * The tab's shell as a command runner: wide enough that no line of output wraps, no echo or
     * prompt, each command run on a cleared screen and followed by a marker with its status.
     */
    private inner class Remote(private val session: TerminalSession) {
        private var next = 0

        private fun screen() = session.emulator.screenText().joinToString("\n") { it.trimEnd() }

        inner class Pending(private val id: Int) {
            suspend fun await(timeoutMs: Long = 30_000): Pair<String, Int> {
                val marker = Regex("__berth_done_$id:(\\d+)")
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val text = screen()
                    marker.find(text)?.let { return text.substring(0, it.range.first).trim() to it.groupValues[1].toInt() }
                    delay(50)
                }
                throw AssertionError("no status from command $id in $timeoutMs ms; the screen:\n${screen()}")
            }
        }

        fun start(command: String): Pending {
            val id = next++
            session.sendText("clear; $command 2>&1; echo \"__berth\"\"_done_$id:\$?\"\n")
            return Pending(id)
        }

        suspend fun run(command: String): Pair<String, Int> = start(command).await()
    }

    private suspend fun <T : Any> awaitValue(timeoutMs: Long, what: String, probe: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            probe()?.let { return it }
            delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private suspend fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        awaitValue(timeoutMs, what) { if (condition()) Unit else null }
    }
}

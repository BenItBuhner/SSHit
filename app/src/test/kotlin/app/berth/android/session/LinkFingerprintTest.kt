package app.berth.android.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SessionState
import app.berth.domain.model.SwatchColor
import app.berth.ssh.FingerprintCheck
import app.berth.ssh.HostKeyFingerprints
import app.berth.ssh.SshKeys
import app.berth.ssh.SshSecurity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The fingerprint an `ssh://` or `sftp://` link carries, through the production session stack against
 * the local sshd: `SessionManager.open` takes it, the session hands it to its policy, and the sheet
 * the transport raises compares it with the key the sshd presents, and on the changed-key sheet with
 * the saved key too. Skipped unless `SSH_TEST_*` is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LinkFingerprintTest {
    private val sshHost = System.getenv("SSH_TEST_HOST").orEmpty()
    private val sshPort = System.getenv("SSH_TEST_PORT").orEmpty().toIntOrNull() ?: 22
    private val sshUser = System.getenv("SSH_TEST_USER").orEmpty()
    private val sshPassword = System.getenv("SSH_TEST_PASSWORD").orEmpty()

    private lateinit var graph: TestGraph
    private val bg = CoroutineScope(Dispatchers.Default + Job())

    /** Every prompt the transport raised, so a test can say a connection raised none. */
    private val prompts = CopyOnWriteArrayList<Prompt>()

    private val box = Host(
        id = "link-box",
        name = "Link box",
        color = SwatchColor.TEAL,
        monogram = "LB",
        address = sshHost,
        port = sshPort,
        user = sshUser,
        auth = AuthMethod.Password(AuthResolver.passwordSecretId("link-box")),
        createdAt = 0L,
    )

    /** Some other key's fingerprint: what a link written for another server, or by someone lying, carries. */
    private val someOtherKey = SshKeys.fingerprintSha256(SshKeys.generate(KeyAlgorithm.ED25519).public)

    @Before
    fun setUp() {
        assumeTrue("SSH_TEST_HOST not set", sshHost.isNotBlank())
        SshSecurity.ensureProviders()
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        bg.launch { graph.prompts.current.filterNotNull().collect { prompts += it } }
        runBlocking {
            graph.secrets.put(AuthResolver.passwordSecretId(box.id), sshPassword.toByteArray())
            graph.hosts.upsert(box)
            graph.sessions.restore()
        }
    }

    @After
    fun tearDown() {
        if (!::graph.isInitialized) return
        graph.sessions.tabs.value.forEach { graph.sessions.close(it.id) }
        bg.cancel()
    }

    @Test
    fun `the sheet compares the sshd's key with what the link said, in either form, and the saved key ends the comparing`() = runBlocking<Unit> {
        // A link carrying another key's fingerprint: the first-connection sheet leads in danger, and the
        // fingerprint it shows is the sshd's own, so the user can still check by hand.
        val first = graph.sessions.open(box, linkFingerprint = someOtherKey)
        val mismatch = awaitPrompt<Prompt.TrustHostKey>()
        assertEquals(FingerprintCheck.MISMATCH, mismatch.link?.check)
        assertEquals(someOtherKey, mismatch.link?.expected)
        assertEquals(box.address, mismatch.request.host)
        assertEquals(box.port, mismatch.request.port)
        val serverKey = mismatch.request.publicKey
        mismatch.cancel()
        await(15_000, "the refused login ends") { first.state != SessionState.CONNECTING }
        assertTrue("nothing is saved for a cancelled sheet", graph.knownHosts.find(box.address, box.port).isEmpty())
        graph.sessions.close(first.id)

        // The same link with the right fingerprint, written as MD5 hex the way an older ssh-keygen prints
        // it: the sheet says so, and trusting saves the key.
        val md5 = HostKeyFingerprints.md5(serverKey).removePrefix("MD5:").uppercase()
        val second = graph.sessions.open(box, linkFingerprint = md5)
        val match = awaitPrompt<Prompt.TrustHostKey>()
        assertEquals(FingerprintCheck.MATCH, match.link?.check)
        assertEquals(SshKeys.fingerprintSha256(serverKey), match.request.fingerprintSha256)
        match.trust()
        await(45_000, "second live") { second.state == SessionState.LIVE }
        assertEquals(1, graph.knownHosts.find(box.address, box.port).size)
        graph.sessions.close(second.id)

        // Once the key is saved, the saved key decides: a link carrying anything else raises no sheet,
        // since a saved key that matches is the stronger fact and the link is only what opened the tab.
        prompts.clear()
        val third = graph.sessions.open(box, linkFingerprint = someOtherKey)
        await(45_000, "third live") { third.state == SessionState.LIVE }
        assertTrue("no prompt once the key is known: $prompts", prompts.none { it is Prompt.TrustHostKey || it is Prompt.HostKeyChanged })
        graph.sessions.close(third.id)
    }

    @Test
    fun `on the changed-key sheet the link is compared with the saved key as well as the offered one`() = runBlocking<Unit> {
        // The sshd's key, learnt the way a first connection learns it; the sheet is cancelled, nothing saved.
        val first = graph.sessions.open(box)
        val fresh = awaitPrompt<Prompt.TrustHostKey>()
        val serverKey = fresh.request.publicKey
        val keyType = fresh.request.keyType
        fresh.cancel()
        await(15_000, "the refused login ends") { first.state != SessionState.CONNECTING }
        graph.sessions.close(first.id)

        // A key of the sshd's type saved for the box that is not the sshd's: the entry a rotation leaves behind.
        val algorithm = KeyAlgorithm.entries.firstOrNull { it.sshName == keyType } ?: throw AssertionError("the sshd offered a $keyType key, which Berth does not generate")
        val stale = SshKeys.generate(algorithm).public
        val savedAt = System.currentTimeMillis() - 40L * 86_400_000
        graph.knownHosts.upsert(KnownHostKey("stale", box.address, box.port, keyType, SshKeys.publicKeyBase64(stale), SshKeys.fingerprintSha256(stale), savedAt, savedAt))

        // A link written while that key was current carries its fingerprint: the saved key's, which the sheet says,
        // rather than a fingerprint that is neither key's.
        val second = graph.sessions.open(box, linkFingerprint = SshKeys.fingerprintSha256(stale))
        val fromBefore = awaitPrompt<Prompt.HostKeyChanged>()
        assertEquals(SshKeys.fingerprintSha256(stale), fromBefore.saved.fingerprintSha256)
        assertEquals(SshKeys.fingerprintSha256(serverKey), fromBefore.request.fingerprintSha256)
        assertEquals(FingerprintCheck.MISMATCH, fromBefore.link?.check)
        assertEquals(FingerprintCheck.MATCH, fromBefore.link?.savedCheck)
        assertTrue(fromBefore.link!!.matchesSaved)
        fromBefore.decide(HostKeyChangedDecision.DISCONNECT)
        await(15_000, "the refused login ends") { second.state != SessionState.CONNECTING }
        graph.sessions.close(second.id)

        // The offered key's fingerprint, written as MD5: the offered key's, and not the saved key's.
        val third = graph.sessions.open(box, linkFingerprint = HostKeyFingerprints.md5(serverKey))
        val offered = awaitPrompt<Prompt.HostKeyChanged>()
        assertEquals(FingerprintCheck.MATCH, offered.link?.check)
        assertEquals(FingerprintCheck.MISMATCH, offered.link?.savedCheck)
        offered.decide(HostKeyChangedDecision.DISCONNECT)
        await(15_000, "the refused login ends") { third.state != SessionState.CONNECTING }
        graph.sessions.close(third.id)

        // A third key's fingerprint is neither's. Replacing the saved key ends the comparing: the sshd's key is saved.
        val fourth = graph.sessions.open(box, linkFingerprint = someOtherKey)
        val neither = awaitPrompt<Prompt.HostKeyChanged>()
        assertEquals(FingerprintCheck.MISMATCH, neither.link?.check)
        assertEquals(FingerprintCheck.MISMATCH, neither.link?.savedCheck)
        neither.decide(HostKeyChangedDecision.REPLACE_SAVED)
        await(45_000, "fourth live") { fourth.state == SessionState.LIVE }
        assertEquals(listOf(SshKeys.fingerprintSha256(serverKey)), graph.knownHosts.find(box.address, box.port).map { it.fingerprintSha256 })
        graph.sessions.close(fourth.id)
    }

    @Test
    fun `an sftp link's fingerprint rides the Files tab to the login the pane asks for`() = runBlocking<Unit> {
        val files = graph.sessions.openFilesForHost(box, linkFingerprint = someOtherKey)
        assertEquals(someOtherKey, files.linkFingerprint)
        assertNull("the browser has no login to ride yet", files.ride.value)

        // The pane's Connect: the terminal it opens for the browser takes the link's fingerprint along.
        graph.sessions.connectFor(files)
        val prompt = awaitPrompt<Prompt.TrustHostKey>()
        assertEquals(FingerprintCheck.MISMATCH, prompt.link?.check)
        assertEquals(someOtherKey, prompt.link?.expected)
        prompt.cancel()
        await(15_000, "the refused login ends") { graph.sessions.sessions.value.all { it.state != SessionState.CONNECTING } }
    }

    private suspend inline fun <reified T : Prompt> awaitPrompt(): T =
        withTimeout(30_000) { graph.prompts.current.first { it is T } } as T

    private suspend fun await(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        throw AssertionError("timed out waiting for $what")
    }
}

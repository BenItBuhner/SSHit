package app.berth.android.session

import app.berth.android.screenshots.InMemoryKnownHosts
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KnownHostKey
import app.berth.domain.model.SwatchColor
import app.berth.ssh.FingerprintCheck
import app.berth.ssh.HostKeyFingerprints
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.SshKeys
import app.berth.ssh.TrustedHostKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class KnownHostsPolicyTest {
    private val repo = InMemoryKnownHosts()
    private val prompts = PromptCenter()
    private val host = Host(id = "h", name = "box", color = SwatchColor.TEAL, monogram = "BX", address = "10.0.0.5", user = "ben", auth = AuthMethod.AskEachTime, createdAt = 0L)
    private val policy = KnownHostsPolicy(host, repo, prompts, now = { 1_000L })
    private val transport = Executors.newSingleThreadExecutor()

    private fun request(keyType: String = "ssh-ed25519"): HostKeyRequest {
        val public = SshKeys.generate(if (keyType == "ssh-ed25519") KeyAlgorithm.ED25519 else KeyAlgorithm.ECDSA_P256).public
        return HostKeyRequest(host.address, host.port, keyType, public, SshKeys.openSshPublic(public).split(" ")[1], SshKeys.fingerprintSha256(public))
    }

    private fun saved(request: HostKeyRequest, pinned: Boolean) = KnownHostKey(
        id = "k-" + request.keyType, host = request.host, port = request.port, keyType = request.keyType,
        publicKeyBase64 = request.publicKeyBase64, fingerprintSha256 = request.fingerprintSha256,
        firstSeenAt = 1L, lastSeenAt = 1L, pinned = pinned,
    )

    /** Runs a blocking policy callback the way sshj does, on its own thread, and returns its answer. */
    private fun <T> onTransport(block: () -> T): java.util.concurrent.Future<T> = transport.submit(block)

    private fun <T : Prompt> awaitPrompt(): T = runBlocking {
        @Suppress("UNCHECKED_CAST")
        withTimeout(5_000) { prompts.current.first { it != null } } as T
    }

    @Test
    fun `first contact saves the key when trusted`() {
        val req = request()
        val answer = onTransport { policy.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().trust()
        assertTrue(answer.get())
        val stored = runBlocking { repo.find(host.address, host.port) }
        assertEquals(1, stored.size)
        assertEquals(req.fingerprintSha256, stored[0].fingerprintSha256)
        assertFalse(stored[0].pinned)
    }

    /** The policy made for a jump host says so on every prompt it raises, with the plain policy's prompts carrying nothing. */
    @Test
    fun `a hop's policy carries its role on the trust, changed-key and refused prompts`() {
        val target = host.copy(id = "t", name = "prod-db", address = "10.0.4.12")
        val role = HopRole(0, 2, target)
        val hop = KnownHostsPolicy(host, repo, prompts, now = { 1_000L }, via = role)

        val first = request()
        val trust = onTransport { hop.onUnknownHost(first) }
        val trustPrompt = awaitPrompt<Prompt.TrustHostKey>()
        assertEquals(role, trustPrompt.via)
        assertEquals("It is hop 1 of 2 on the way to prod-db.", trustPrompt.via!!.sentence)
        trustPrompt.trust()
        assertTrue(trust.get())

        val changed = onTransport { hop.onChangedHostKey(request(), listOf(TrustedHostKey(first.keyType, first.publicKeyBase64, first.fingerprintSha256))) }
        val changedPrompt = awaitPrompt<Prompt.HostKeyChanged>()
        assertEquals(role, changedPrompt.via)
        changedPrompt.decide(HostKeyChangedDecision.DISCONNECT)
        assertFalse(changed.get())

        runBlocking { repo.upsert(saved(first, pinned = true)) }
        val refused = onTransport { hop.onUnknownHost(request("ecdsa-sha2-nistp256")) }
        assertEquals(role, awaitPrompt<Prompt.PinnedKeyRefused>().also { it.acknowledge() }.via)
        assertFalse(refused.get())

        val plain = onTransport { policy.onUnknownHost(request("ecdsa-sha2-nistp256")) }
        assertNull(awaitPrompt<Prompt.PinnedKeyRefused>().also { it.acknowledge() }.via)
        assertFalse(plain.get())
    }

    @Test
    fun `a pinned key refuses a new key type without asking`() {
        val ed = request("ssh-ed25519")
        runBlocking { repo.upsert(saved(ed, pinned = true)) }
        val ecdsa = request("ecdsa-sha2-nistp256")
        val answer = onTransport { policy.onUnknownHost(ecdsa) }
        val notice = awaitPrompt<Prompt.PinnedKeyRefused>()
        assertEquals(ed.fingerprintSha256, notice.pinned.fingerprintSha256)
        notice.acknowledge()
        assertFalse(answer.get())
        assertEquals(1, runBlocking { repo.find(host.address, host.port) }.size)
    }

    @Test
    fun `a pinned key refuses a changed key of the same type`() {
        val old = request()
        runBlocking { repo.upsert(saved(old, pinned = true)) }
        val changed = request()
        val answer = onTransport { policy.onChangedHostKey(changed, listOf(TrustedHostKey(old.keyType, old.publicKeyBase64, old.fingerprintSha256))) }
        awaitPrompt<Prompt.PinnedKeyRefused>().acknowledge()
        assertFalse(answer.get())
        assertEquals(old.fingerprintSha256, runBlocking { repo.find(host.address, host.port) }.single().fingerprintSha256)
    }

    @Test
    fun `an unpinned changed key still offers the three choices and replace swaps the saved key`() {
        val old = request()
        runBlocking { repo.upsert(saved(old, pinned = false)) }
        val changed = request()
        val answer = onTransport { policy.onChangedHostKey(changed, listOf(TrustedHostKey(old.keyType, old.publicKeyBase64, old.fingerprintSha256))) }
        awaitPrompt<Prompt.HostKeyChanged>().decide(HostKeyChangedDecision.REPLACE_SAVED)
        assertTrue(answer.get())
        val stored = runBlocking { repo.find(host.address, host.port) }.single()
        assertEquals(changed.fingerprintSha256, stored.fingerprintSha256)
        assertNull(prompts.current.value)
    }

    @Test
    fun `seeing a known key only refreshes its timestamp`() {
        val req = request()
        runBlocking { repo.upsert(saved(req, pinned = true)) }
        policy.onKnownHostSeen(req)
        val stored = runBlocking { repo.find(host.address, host.port) }.single()
        assertEquals(1_000L, stored.lastSeenAt)
        assertTrue(stored.pinned)
    }

    @Test
    fun `a link's fingerprint reaches the first-connection sheet as a comparison with the offered key`() {
        val req = request()
        val matching = KnownHostsPolicy(host, repo, prompts, linkFingerprint = req.fingerprintSha256)
        val first = onTransport { matching.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertEquals(FingerprintCheck.MATCH, prompt.link?.check)
            assertEquals(req.fingerprintSha256, prompt.link?.expected)
            prompt.cancel()
        }
        assertFalse(first.get())

        // The forms differ, the key is the same: the link wrote the MD5 hex, upper case, as some tools print it.
        val md5 = HostKeyFingerprints.md5(req.publicKey).removePrefix("MD5:").uppercase()
        val matchingMd5 = KnownHostsPolicy(host, repo, prompts, linkFingerprint = md5)
        val second = onTransport { matchingMd5.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertEquals(FingerprintCheck.MATCH, prompt.link?.check)
            assertEquals("MD5:${md5.lowercase()}", prompt.link?.shown)
            prompt.cancel()
        }
        assertFalse(second.get())

        val other = request()
        val mismatching = KnownHostsPolicy(host, repo, prompts, linkFingerprint = other.fingerprintSha256)
        val third = onTransport { mismatching.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertEquals(FingerprintCheck.MISMATCH, prompt.link?.check)
            assertEquals(other.fingerprintSha256, prompt.link?.expected)
            assertEquals(req.fingerprintSha256, prompt.request.fingerprintSha256)
            // The comparison is a line on the sheet, not a decision: trusting still saves the offered key.
            prompt.trust()
        }
        assertTrue(third.get())
        assertEquals(req.fingerprintSha256, runBlocking { repo.find(host.address, host.port) }.single().fingerprintSha256)
    }

    @Test
    fun `a fingerprint in no form Berth reads is said to be unreadable, and a blank one is no link at all`() {
        val req = request()
        val garbage = KnownHostsPolicy(host, repo, prompts, linkFingerprint = "SHA256:not-really")
        val first = onTransport { garbage.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertEquals(FingerprintCheck.UNREADABLE, prompt.link?.check)
            assertEquals("SHA256:not-really", prompt.link?.expected)
            assertEquals("SHA256:not-really", prompt.link?.shown)
            assertEquals("SHA256:not-really", prompt.link?.quoted)
            prompt.cancel()
        }
        assertFalse(first.get())

        // The unreadable notice is the one place the link's own text is shown, so what a link can
        // carry through percent-encoding (a bidi override, an escape sequence, a newline, a
        // zero-width joiner, an unpaired surrogate) is dropped from the quote and the rest is cut short.
        val hostile = LinkFingerprint.of("\u202Eeman\u001B[2J\u001B[Htsoh\n\u200D\uD800" + "x".repeat(60), req.publicKey)!!
        assertEquals(FingerprintCheck.UNREADABLE, hostile.check)
        val visible = "eman[2J[Htsoh" // the escapes' own bytes go; the letters after them are only letters
        assertEquals(visible + "x".repeat(LinkFingerprint.QUOTED_MAX - visible.length) + "\u2026", hostile.quoted)
        assertEquals(LinkFingerprint.QUOTED_MAX + 1, hostile.quoted.length)

        val blank = KnownHostsPolicy(host, repo, prompts, linkFingerprint = "  ")
        val second = onTransport { blank.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertNull(prompt.link)
            prompt.cancel()
        }
        assertFalse(second.get())

        val plain = onTransport { policy.onUnknownHost(req) }
        awaitPrompt<Prompt.TrustHostKey>().let { prompt ->
            assertNull(prompt.link)
            prompt.cancel()
        }
        assertFalse(plain.get())
    }

    @Test
    fun `the changed-key sheet compares the link with the key offered, not the one saved`() {
        val old = request()
        runBlocking { repo.upsert(saved(old, pinned = false)) }
        val changed = request()
        val known = listOf(TrustedHostKey(old.keyType, old.publicKeyBase64, old.fingerprintSha256))

        // The administrator rotated the key and sent a link with the new fingerprint: the link agrees with the server.
        val agreesWithServer = KnownHostsPolicy(host, repo, prompts, linkFingerprint = changed.fingerprintSha256)
        val first = onTransport { agreesWithServer.onChangedHostKey(changed, known) }
        awaitPrompt<Prompt.HostKeyChanged>().let { prompt ->
            assertEquals(FingerprintCheck.MATCH, prompt.link?.check)
            prompt.decide(HostKeyChangedDecision.DISCONNECT)
        }
        assertFalse(first.get())

        // The link carries the fingerprint Berth saved: the server is the one that changed.
        val agreesWithSaved = KnownHostsPolicy(host, repo, prompts, linkFingerprint = old.fingerprintSha256)
        val second = onTransport { agreesWithSaved.onChangedHostKey(changed, known) }
        awaitPrompt<Prompt.HostKeyChanged>().let { prompt ->
            assertEquals(FingerprintCheck.MISMATCH, prompt.link?.check)
            assertEquals(old.fingerprintSha256, prompt.link?.expected)
            prompt.decide(HostKeyChangedDecision.DISCONNECT)
        }
        assertFalse(second.get())
        assertEquals(old.fingerprintSha256, runBlocking { repo.find(host.address, host.port) }.single().fingerprintSha256)
    }

    @Test
    fun `a pinned key refuses without a sheet whatever the link says`() {
        val pinned = request()
        runBlocking { repo.upsert(saved(pinned, pinned = true)) }
        val changed = request()
        val linkAgrees = KnownHostsPolicy(host, repo, prompts, linkFingerprint = changed.fingerprintSha256)
        val answer = onTransport { linkAgrees.onChangedHostKey(changed, listOf(TrustedHostKey(pinned.keyType, pinned.publicKeyBase64, pinned.fingerprintSha256))) }
        awaitPrompt<Prompt.PinnedKeyRefused>().acknowledge()
        assertFalse(answer.get())
    }

    @Test
    fun `prompts queue so two connecting sessions never share one sheet`() {
        val a = request()
        val b = request("ecdsa-sha2-nistp256")
        val first = onTransport { policy.onUnknownHost(a) }
        val second = CoroutineScope(Dispatchers.IO).launch { KnownHostsPolicy(host.copy(id = "h2"), repo, prompts).onUnknownHost(b) }
        awaitPrompt<Prompt.TrustHostKey>().cancel()
        assertFalse(first.get())
        awaitPrompt<Prompt.TrustHostKey>().trust()
        runBlocking { second.join() }
        assertEquals(listOf(b.keyType), runBlocking { repo.find(host.address, host.port) }.map { it.keyType })
    }
}

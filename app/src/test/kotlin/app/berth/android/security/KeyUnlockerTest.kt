package app.berth.android.security

import android.app.Application
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import app.berth.android.screenshots.TestGraph
import app.berth.android.session.Prompt
import app.berth.data.crypto.KeyAuthModel
import app.berth.domain.model.AuthMethod
import app.berth.domain.model.Host
import app.berth.domain.model.Identity
import app.berth.domain.model.KeyAlgorithm
import app.berth.domain.model.KeyProtection
import app.berth.domain.model.KeyStorage
import app.berth.domain.model.SecuritySettings
import app.berth.domain.model.SwatchColor
import app.berth.ssh.SshAuth
import app.berth.ssh.SshKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.SignatureECDSA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.PublicKey

/**
 * Getting the user past a Keystore key's authentication before the handshake (spec C20, Biometric
 * gating). The Keystore is a software stand-in that throws the Keystore's own exceptions on cue; the
 * prompt is scripted. What comes out is checked the way a server would: the wire signature over the
 * challenge verifies against the identity's public key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeyUnlockerTest {
    private lateinit var graph: TestGraph
    private lateinit var keystore: FakeKeystore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val host = Host(
        id = "pi-hole",
        name = "pi-hole",
        color = SwatchColor.MOSS,
        monogram = "PI",
        address = "192.168.1.2",
        port = 22,
        user = "pi",
        auth = AuthMethod.Key("id-phone"),
        createdAt = 0L,
    )

    private lateinit var identity: Identity

    @Before
    fun setUp() {
        graph = TestGraph(ApplicationProvider.getApplicationContext())
        keystore = FakeKeystore(KeyAuthModel.PER_USE)
        graph.keystore = keystore
        identity = Identity(
            "id-phone", "this phone", KeyAlgorithm.ECDSA_P256, KeyStorage.ANDROID_KEYSTORE, KeyProtection.BIOMETRIC,
            SshKeys.openSshPublic(keystore.pair.public, "berth@pixel"), SshKeys.fingerprintSha256(keystore.pair.public), "berth@pixel",
            keystoreAlias = "berth-id-phone", createdAt = 0L,
        )
        runBlocking {
            graph.identities.insert(identity, null)
            graph.hosts.upsert(host)
        }
    }

    private fun authFor(): SshAuth.PublicKey = runBlocking { graph.keyUnlocker.authFor(host, identity) }

    /** Runs [authFor] on the side, so the test can look at the prompt while it is up. */
    private class Attempt {
        var result: SshAuth.PublicKey? = null
        var error: Throwable? = null
        val done get() = result != null || error != null
    }

    private fun attempt(): Attempt {
        val attempt = Attempt()
        scope.launch {
            try {
                attempt.result = graph.keyUnlocker.authFor(host, identity)
            } catch (e: Throwable) {
                attempt.error = e
            }
        }
        return attempt
    }

    private val challenge = "session id || SSH_MSG_USERAUTH_REQUEST".toByteArray()

    /** Whether [auth]'s signer proves possession of [public] to sshj's own verifier. */
    private fun signs(auth: SshAuth.PublicKey, public: PublicKey = keystore.pair.public): Boolean {
        val blob = auth.signer!!.sign(challenge)
        val wire = Buffer.PlainBuffer().putString(KeyType.ECDSA256.toString()).putBytes(blob).compactData
        return SignatureECDSA.Factory256().create().run {
            initVerify(public)
            update(challenge)
            verify(wire)
        }
    }

    @Test
    fun `a key that needs nobody signs without a prompt`() {
        keystore.model = KeyAuthModel.NONE
        val auth = authFor()
        assertTrue(graph.authenticator.requests.isEmpty())
        assertTrue(signs(auth))
        assertEquals(KeyType.ECDSA256, auth.keyProvider.type)
        assertEquals("berth-id-phone", keystore.aliases.single())
    }

    @Test
    fun `a per-use key is unlocked by a prompt that carries the very Signature that then signs`() {
        val attempt = attempt()
        assertFalse(attempt.done)
        val prompt = graph.prompts.current.value as Prompt.UnlockKey
        assertEquals("this phone", prompt.identityName)
        assertEquals(host, prompt.host)
        val request = graph.authenticator.requests.single()
        assertEquals("Sign in to pi-hole", request.title)
        assertEquals("Confirm to sign with the key \u201Cthis phone\u201D", request.subtitle)
        assertNotNull("the CryptoObject", request.signature)

        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        assertNull("the sheet is gone", graph.prompts.current.value)
        val auth = attempt.result!!
        assertTrue(signs(auth))
        assertEquals("one Signature, initialised once, before the prompt", 1, keystore.beginSignCalls)
    }

    @Test
    fun `a timed-window key inside its window signs without a prompt`() {
        keystore.model = KeyAuthModel.TIMED_WINDOW
        val auth = authFor()
        assertTrue(graph.authenticator.requests.isEmpty())
        assertTrue(signs(auth))
    }

    @Test
    fun `a timed-window key whose window has closed is prompted for and tried once more`() {
        keystore.model = KeyAuthModel.TIMED_WINDOW
        keystore.failures += UserNotAuthenticatedException()
        val attempt = attempt()
        assertFalse(attempt.done)
        assertTrue(graph.prompts.current.value is Prompt.UnlockKey)
        val request = graph.authenticator.requests.single()
        assertNull("no CryptoObject: the prompt only reopens the window", request.signature)

        graph.authenticator.answer(FakeAuthenticator.SUCCEEDED)
        assertTrue(signs(attempt.result!!))
        assertEquals("the locked try, then the one after the prompt", 2, keystore.beginSignCalls)
    }

    @Test
    fun `a timed-window key still locked after the prompt fails plainly, with no third try`() {
        keystore.model = KeyAuthModel.TIMED_WINDOW
        keystore.failures += UserNotAuthenticatedException()
        keystore.failures += UserNotAuthenticatedException()
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        try {
            authFor()
            fail("expected the plain failure")
        } catch (e: IllegalStateException) {
            assertEquals("The key \u201Cthis phone\u201D stayed locked after the unlock. Try connecting again.", e.message)
        }
        assertEquals(2, keystore.beginSignCalls)
        assertEquals(1, graph.authenticator.requests.size)
    }

    @Test
    fun `backing out of the system prompt names the key and the host`() {
        graph.authenticator.queue(AuthOutcome.Cancelled)
        try {
            authFor()
            fail("expected the plain failure")
        } catch (e: IllegalStateException) {
            assertEquals("Cancelled before the key \u201Cthis phone\u201D could sign, so Berth did not sign in to pi-hole.", e.message)
        }
        assertNull(graph.prompts.current.value)
    }

    @Test
    fun `Cancel on the sheet withdraws the system prompt and fails the same way`() {
        val attempt = attempt()
        val prompt = graph.prompts.current.value as Prompt.UnlockKey
        assertTrue(graph.authenticator.pending)
        prompt.cancel()
        assertFalse("the system prompt was taken down", graph.authenticator.pending)
        assertEquals("Cancelled before the key \u201Cthis phone\u201D could sign, so Berth did not sign in to pi-hole.", attempt.error?.message)
        assertNull(graph.prompts.current.value)
    }

    @Test
    fun `a prompt that errors carries the system's reason`() {
        graph.authenticator.queue(AuthOutcome.Failed("Too many attempts. Try again later."))
        try {
            authFor()
            fail("expected the plain failure")
        } catch (e: IllegalStateException) {
            assertEquals("The key \u201Cthis phone\u201D could not be unlocked: Too many attempts. Try again later.", e.message)
        }
    }

    @Test
    fun `an invalidated key is named, regenerated on request, and the identity updated`() {
        keystore.failures += KeyPermanentlyInvalidatedException()
        val before = keystore.pair.public
        val attempt = attempt()
        val prompt = graph.prompts.current.value as Prompt.KeyInvalidated
        assertEquals(identity, prompt.identity)
        assertEquals(host, prompt.host)
        assertTrue("no system prompt for a key that can never sign", graph.authenticator.requests.isEmpty())

        prompt.regenerate()
        assertEquals(listOf("berth-id-phone" to KeyProtection.BIOMETRIC), keystore.regenerated)
        val updated = runBlocking { graph.identities.get("id-phone") }!!
        assertNotEquals(before, keystore.pair.public)
        assertEquals(SshKeys.openSshPublic(keystore.pair.public, "berth@pixel"), updated.publicKeyOpenSsh)
        assertEquals(SshKeys.fingerprintSha256(keystore.pair.public), updated.fingerprintSha256)
        assertEquals("berth-id-phone", updated.keystoreAlias)
        assertEquals(
            "The key \u201Cthis phone\u201D has a new key pair. Add its public key to pi-hole (Keys, Copy public key), then connect again.",
            attempt.error?.message,
        )
        assertNull(graph.prompts.current.value)
    }

    @Test
    fun `an invalidated key left alone fails with the reason and keeps the identity`() {
        keystore.failures += KeyPermanentlyInvalidatedException()
        val attempt = attempt()
        (graph.prompts.current.value as Prompt.KeyInvalidated).cancel()
        assertEquals("The key \u201Cthis phone\u201D can no longer sign: this device's fingerprints or face changed since the key was made.", attempt.error?.message)
        assertTrue(keystore.regenerated.isEmpty())
        assertEquals(identity, runBlocking { graph.identities.get("id-phone") })
    }

    @Test
    fun `the invalidation of a timed-window key is caught the same way`() {
        keystore.model = KeyAuthModel.TIMED_WINDOW
        keystore.failures += KeyPermanentlyInvalidatedException()
        attempt()
        assertTrue(graph.prompts.current.value is Prompt.KeyInvalidated)
    }

    @Test
    fun `no prompt appears over the lock screen`() {
        graph.settings.security.value = SecuritySettings(appLock = true)
        graph.appLock.onForeground()
        assertEquals(LockState.LOCKED, graph.appLock.state.value)
        val attempt = attempt()
        assertNull("the key prompt waits", graph.prompts.current.value)
        assertTrue(graph.authenticator.requests.isEmpty())

        // The unlock consumes the first answer; the key prompt that follows straight after it, the second.
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED, FakeAuthenticator.SUCCEEDED)
        runBlocking { graph.appLock.unlock() }
        assertEquals(listOf("Unlock Berth", "Sign in to pi-hole"), graph.authenticator.requests.map { it.title })
        assertTrue(signs(attempt.result!!))
    }

    @Test
    fun `the resolver puts the unlocked key first, ahead of the interactive fallbacks`() {
        graph.authenticator.queue(FakeAuthenticator.SUCCEEDED)
        val methods = runBlocking { graph.authResolver.resolve(host) }
        assertEquals(3, methods.size)
        val key = methods[0] as SshAuth.PublicKey
        assertNotNull(key.signer)
        assertTrue(signs(key))
        assertTrue(methods[1] is SshAuth.KeyboardInteractive)
        assertTrue(methods[2] is SshAuth.Password)
    }

    @Test
    fun `the signer's signature does not verify against another key`() {
        keystore.model = KeyAuthModel.NONE
        val auth = authFor()
        assertFalse(signs(auth, FakeKeystore.newP256().public))
    }
}

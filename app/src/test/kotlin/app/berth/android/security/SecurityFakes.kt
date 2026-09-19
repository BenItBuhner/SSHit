package app.berth.android.security

import app.berth.data.crypto.KeyAuthModel
import app.berth.data.crypto.KeystoreSigning
import app.berth.domain.model.KeyProtection
import kotlinx.coroutines.CompletableDeferred
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A clock the test steps by hand. */
class FakeClock(start: Long = 1_000_000L) : MonotonicClock {
    var now: Long = start
    override fun elapsed(): Long = now
    fun advance(millis: Long) {
        now += millis
    }
}

/**
 * Answers the system prompt from a script. With no answers queued the prompt stays up, the way a
 * device waits for a finger; [pending] tells the test whether one is up. [signature] echoes the
 * `CryptoObject` back on success, like the framework does.
 */
class FakeAuthenticator(override var deviceSecure: Boolean = true) : DeviceAuthenticator {
    private val answers = ArrayDeque<AuthOutcome>()
    private var waiting: CompletableDeferred<AuthOutcome>? = null
    val requests = ArrayList<Request>()

    data class Request(val title: String, val subtitle: String?, val signature: Signature?)

    /** Whether a prompt is up and unanswered. */
    val pending: Boolean get() = waiting?.isActive == true

    fun queue(vararg outcomes: AuthOutcome) {
        answers.addAll(outcomes)
    }

    /** Answers the prompt that is up now. */
    fun answer(outcome: AuthOutcome) {
        val w = waiting ?: error("no prompt is up")
        waiting = null
        w.complete(outcome)
    }

    override suspend fun authenticate(title: String, subtitle: String?, signature: Signature?): AuthOutcome {
        requests += Request(title, subtitle, signature)
        answers.removeFirstOrNull()?.let { return if (it is AuthOutcome.Succeeded && it.signature == null) AuthOutcome.Succeeded(signature) else it }
        val deferred = CompletableDeferred<AuthOutcome>()
        waiting = deferred
        try {
            return deferred.await()
        } finally {
            if (waiting === deferred) waiting = null
        }
    }

    companion object {
        /** A success that hands back whatever signature was passed in. */
        val SUCCEEDED: AuthOutcome = AuthOutcome.Succeeded(null)
    }
}

/**
 * Android Keystore without Android Keystore: a software P-256 pair behind the [KeystoreSigning]
 * seam, so the signatures are real and a server can check them, while the Keystore's own
 * behaviour (the auth model, `UserNotAuthenticatedException`, `KeyPermanentlyInvalidatedException`)
 * is scripted through [failures].
 */
class FakeKeystore(var model: KeyAuthModel, var pair: KeyPair = newP256()) : KeystoreSigning {
    /** Thrown by the next calls to [beginSign], in order, before any signature is made. */
    val failures = ArrayDeque<Throwable>()
    var beginSignCalls = 0
    val aliases = ArrayList<String>()
    val regenerated = ArrayList<Pair<String, KeyProtection>>()

    override fun authModel(alias: String): KeyAuthModel = model

    override fun beginSign(alias: String): Signature {
        aliases += alias
        beginSignCalls++
        failures.removeFirstOrNull()?.let { throw it }
        return Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private) }
    }

    override fun regenerate(alias: String, protection: KeyProtection): PublicKey {
        regenerated += alias to protection
        pair = newP256()
        return pair.public
    }

    override fun keyProvider(alias: String): KeyProvider {
        val current = pair
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = current.private
            override fun getPublic(): PublicKey = current.public
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    companion object {
        fun newP256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
}

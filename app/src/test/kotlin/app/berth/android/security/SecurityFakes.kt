package app.berth.android.security

import kotlinx.coroutines.CompletableDeferred
import java.security.Signature

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

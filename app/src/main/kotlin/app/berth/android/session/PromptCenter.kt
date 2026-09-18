package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.KnownHostKey
import app.berth.ssh.HostKeyRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Something the transport needs a human for. The UI shows exactly one at a time. */
sealed interface Prompt {
    val host: Host

    /** First contact: trust on first use. */
    class TrustHostKey(
        override val host: Host,
        val request: HostKeyRequest,
        /** Keys already saved for this endpoint but of other types (rotation), if any. */
        val otherKnown: List<KnownHostKey>,
        internal val answer: CompletableDeferred<Boolean>,
    ) : Prompt {
        fun trust() = answer.complete(true)
        fun cancel() = answer.complete(false)
    }

    /** A saved key of the same type no longer matches. */
    class HostKeyChanged(
        override val host: Host,
        val request: HostKeyRequest,
        val saved: KnownHostKey,
        internal val answer: CompletableDeferred<HostKeyChangedDecision>,
    ) : Prompt {
        fun decide(decision: HostKeyChangedDecision) = answer.complete(decision)
    }

    class Password(
        override val host: Host,
        /** Server-provided prompt for keyboard-interactive, or null for a plain password. */
        val serverPrompt: String?,
        val instruction: String?,
        internal val answer: CompletableDeferred<CharArray?>,
    ) : Prompt {
        fun submit(password: CharArray) = answer.complete(password)
        fun cancel() = answer.complete(null)
    }

    class Passphrase(
        override val host: Host,
        val identityName: String,
        internal val answer: CompletableDeferred<CharArray?>,
    ) : Prompt {
        fun submit(passphrase: CharArray) = answer.complete(passphrase)
        fun cancel() = answer.complete(null)
    }
}

enum class HostKeyChangedDecision { DISCONNECT, TRUST_ONCE, REPLACE_SAVED }

/**
 * Serialises prompts from any number of connecting sessions into one observable slot. Callers on
 * transport threads block with `runBlocking`; the UI answers through the prompt object.
 */
@Singleton
class PromptCenter @Inject constructor() {
    private val _current = MutableStateFlow<Prompt?>(null)
    val current: StateFlow<Prompt?> = _current.asStateFlow()
    private val gate = Mutex()

    private suspend fun <T> ask(build: (CompletableDeferred<T>) -> Prompt): T = gate.withLock {
        val deferred = CompletableDeferred<T>()
        _current.value = build(deferred)
        try {
            deferred.await()
        } finally {
            _current.value = null
        }
    }

    suspend fun trustHostKey(host: Host, request: HostKeyRequest, otherKnown: List<KnownHostKey>): Boolean =
        ask { Prompt.TrustHostKey(host, request, otherKnown, it) }

    suspend fun hostKeyChanged(host: Host, request: HostKeyRequest, saved: KnownHostKey): HostKeyChangedDecision =
        ask { Prompt.HostKeyChanged(host, request, saved, it) }

    suspend fun password(host: Host, serverPrompt: String? = null, instruction: String? = null): CharArray? =
        ask { Prompt.Password(host, serverPrompt, instruction, it) }

    suspend fun passphrase(host: Host, identityName: String): CharArray? =
        ask { Prompt.Passphrase(host, identityName, it) }
}

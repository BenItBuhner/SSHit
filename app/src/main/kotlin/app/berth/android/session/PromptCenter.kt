package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.Identity
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

/**
 * Where a host sits in a login's jump chain: hop [index] (zero-based) of [count], on the way to
 * [target]. A key decision about a hop carries this, so its sheet says it is a jump host and
 * where the chain is going, while the tab behind it is titled with the target.
 */
data class HopRole(val index: Int, val count: Int, val target: Host) {
    /** `It is hop 1 of 2 on the way to prod-db.` */
    val sentence: String get() = "It is hop ${index + 1} of $count on the way to ${target.name}."
}

/** Something the transport needs a human for. The UI shows exactly one at a time. */
sealed interface Prompt {
    val host: Host

    /** First contact: trust on first use. [via] is set when [host] is a jump host of the login being made. */
    class TrustHostKey(
        override val host: Host,
        val request: HostKeyRequest,
        /** Keys already saved for this endpoint but of other types (rotation), if any. */
        val otherKnown: List<KnownHostKey>,
        internal val answer: CompletableDeferred<Boolean>,
        val via: HopRole? = null,
    ) : Prompt {
        fun trust() = answer.complete(true)
        fun cancel() = answer.complete(false)
    }

    /** A saved key of the same type no longer matches. [via] is set when [host] is a jump host of the login being made. */
    class HostKeyChanged(
        override val host: Host,
        val request: HostKeyRequest,
        val saved: KnownHostKey,
        internal val answer: CompletableDeferred<HostKeyChangedDecision>,
        val via: HopRole? = null,
    ) : Prompt {
        fun decide(decision: HostKeyChangedDecision) = answer.complete(decision)
    }

    /**
     * The server offered a key that a pinned entry rules out. There is nothing to decide: the
     * connection is refused, and this explains why and where to unpin. [via] is set when [host]
     * is a jump host of the login being made.
     */
    class PinnedKeyRefused(
        override val host: Host,
        val request: HostKeyRequest,
        val pinned: KnownHostKey,
        internal val answer: CompletableDeferred<Unit>,
        val via: HopRole? = null,
    ) : Prompt {
        fun acknowledge() = answer.complete(Unit)
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

    /**
     * A Keystore key needs the user before it signs. The system prompt is up over this sheet, which
     * says which key and why; Cancel here withdraws the prompt and the connection fails plainly.
     */
    class UnlockKey(
        override val host: Host,
        val identityName: String,
        internal val cancelled: CompletableDeferred<Unit>,
    ) : Prompt {
        fun cancel() {
            cancelled.complete(Unit)
        }
    }

    /**
     * Android destroyed the key's ability to sign because the device's biometrics changed. The
     * only way forward is a new key pair, which the server has to be told about.
     */
    class KeyInvalidated(
        override val host: Host,
        val identity: Identity,
        internal val answer: CompletableDeferred<Boolean>,
    ) : Prompt {
        fun regenerate() = answer.complete(true)
        fun cancel() = answer.complete(false)
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

    suspend fun trustHostKey(host: Host, request: HostKeyRequest, otherKnown: List<KnownHostKey>, via: HopRole? = null): Boolean =
        ask { Prompt.TrustHostKey(host, request, otherKnown, it, via) }

    suspend fun hostKeyChanged(host: Host, request: HostKeyRequest, saved: KnownHostKey, via: HopRole? = null): HostKeyChangedDecision =
        ask { Prompt.HostKeyChanged(host, request, saved, it, via) }

    suspend fun pinnedKeyRefused(host: Host, request: HostKeyRequest, pinned: KnownHostKey, via: HopRole? = null) {
        ask { Prompt.PinnedKeyRefused(host, request, pinned, it, via) }
    }

    suspend fun password(host: Host, serverPrompt: String? = null, instruction: String? = null): CharArray? =
        ask { Prompt.Password(host, serverPrompt, instruction, it) }

    suspend fun passphrase(host: Host, identityName: String): CharArray? =
        ask { Prompt.Passphrase(host, identityName, it) }

    /** Shows the unlock sheet for as long as [work] (the system prompt) runs; the sheet's Cancel reaches [work] through the prompt. */
    suspend fun <T> unlockKey(host: Host, identityName: String, work: suspend (Prompt.UnlockKey) -> T): T = gate.withLock {
        val prompt = Prompt.UnlockKey(host, identityName, CompletableDeferred())
        _current.value = prompt
        try {
            work(prompt)
        } finally {
            _current.value = null
        }
    }

    /** True when the user chose to regenerate the key. */
    suspend fun keyInvalidated(host: Host, identity: Identity): Boolean =
        ask { Prompt.KeyInvalidated(host, identity, it) }
}

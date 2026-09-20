package app.berth.android.session

import app.berth.domain.model.Host
import app.berth.domain.model.KnownHostKey
import app.berth.domain.repository.KnownHostRepository
import app.berth.ssh.HostKeyPolicy
import app.berth.ssh.HostKeyRequest
import app.berth.ssh.TrustedHostKey
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Trust-on-first-use against the known hosts table, asking the user through [PromptCenter] when
 * something is new or different. sshj calls this on its transport thread during key exchange, so
 * blocking here is expected and holds the handshake until the user decides.
 *
 * A pinned key removes the decision: while an endpoint has a pinned key, any key that is not
 * one of its saved keys is refused without a choice, whether it is a new type or a changed one.
 *
 * The policy made for a jump host knows it is one ([via]), and every prompt it raises carries
 * that, so the sheet names the hop's role and the target rather than reading as the target's.
 *
 * [linkFingerprint] is the fingerprint the link that opened this login carried (`;fingerprint=`),
 * when one did: the first-connection sheet shows how it compares with the key the server presents,
 * the changed-key sheet with that key and with the saved one ([LinkFingerprint]). It changes what
 * the sheets say, never what the policy does.
 */
class KnownHostsPolicy(
    private val host: Host,
    private val knownHosts: KnownHostRepository,
    private val prompts: PromptCenter,
    private val now: () -> Long = System::currentTimeMillis,
    private val via: HopRole? = null,
    private val linkFingerprint: String? = null,
) : HostKeyPolicy {
    override fun trustedKeys(host: String, port: Int): List<TrustedHostKey> = runBlocking {
        knownHosts.find(host, port).map { TrustedHostKey(it.keyType, it.publicKeyBase64, it.fingerprintSha256) }
    }

    override fun onUnknownHost(request: HostKeyRequest): Boolean = runBlocking {
        val others = knownHosts.find(request.host, request.port)
        others.firstOrNull { it.pinned }?.let { pinned ->
            prompts.pinnedKeyRefused(host, request, pinned, via)
            return@runBlocking false
        }
        val accepted = prompts.trustHostKey(host, request, others, via, LinkFingerprint.of(linkFingerprint, request.publicKey))
        if (accepted) save(request)
        accepted
    }

    override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean = runBlocking {
        val all = knownHosts.find(request.host, request.port)
        val saved = all.firstOrNull { it.keyType == request.keyType }
            ?: return@runBlocking onUnknownHost(request)
        (all.firstOrNull { it.pinned && it.keyType == request.keyType } ?: all.firstOrNull { it.pinned })?.let { pinned ->
            prompts.pinnedKeyRefused(host, request, pinned, via)
            return@runBlocking false
        }
        when (prompts.hostKeyChanged(host, request, saved, via, LinkFingerprint.of(linkFingerprint, request.publicKey, saved))) {
            HostKeyChangedDecision.DISCONNECT -> false
            HostKeyChangedDecision.TRUST_ONCE -> true
            HostKeyChangedDecision.REPLACE_SAVED -> {
                knownHosts.delete(saved.id)
                save(request)
                true
            }
        }
    }

    override fun onKnownHostSeen(request: HostKeyRequest) {
        runBlocking {
            knownHosts.find(request.host, request.port)
                .firstOrNull { it.publicKeyBase64 == request.publicKeyBase64 }
                ?.let { knownHosts.upsert(it.copy(lastSeenAt = now())) }
        }
    }

    private suspend fun save(request: HostKeyRequest) {
        val t = now()
        knownHosts.upsert(
            KnownHostKey(
                id = UUID.randomUUID().toString(),
                host = request.host,
                port = request.port,
                keyType = request.keyType,
                publicKeyBase64 = request.publicKeyBase64,
                fingerprintSha256 = request.fingerprintSha256,
                firstSeenAt = t,
                lastSeenAt = t,
            ),
        )
    }
}

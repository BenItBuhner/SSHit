package app.berth.ssh

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/** A host key the app already trusts for a host:port. */
data class TrustedHostKey(val keyType: String, val publicKeyBase64: String, val fingerprintSha256: String)

/** What a server presented during key exchange. */
data class HostKeyRequest(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKey: PublicKey,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
)

/**
 * Trust decisions for host keys. sshj calls these synchronously on its transport thread while key
 * exchange waits, so an implementation may block on a user prompt.
 */
interface HostKeyPolicy {
    /** Keys already trusted for this endpoint, any type. */
    fun trustedKeys(host: String, port: Int): List<TrustedHostKey>

    /** First contact with an endpoint (trust on first use). Return true to accept. */
    fun onUnknownHost(request: HostKeyRequest): Boolean

    /**
     * The endpoint presented a key of a type we have a different key for. This is the
     * "key changed" flow; [known] lists what was saved. Return true to accept this connection.
     */
    fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean

    /** A saved key matched; lets the store refresh its last-seen timestamp. */
    fun onKnownHostSeen(request: HostKeyRequest) = Unit
}

/**
 * Adapts a [HostKeyPolicy] to sshj's verifier, preferring algorithms we already trust.
 *
 * Known hosts are keyed by the address the user configured ([host]:[port]), not by whatever
 * name sshj derives from the socket: connecting by [java.net.InetAddress] makes it reverse-resolve
 * (127.0.0.1 becomes "localhost"), which would scatter one server across several entries.
 */
internal class PolicyHostKeyVerifier(
    private val policy: HostKeyPolicy,
    private val host: String,
    private val port: Int,
) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val request = HostKeyRequest(
            host = host,
            port = this.port,
            keyType = SshKeys.keyTypeName(key),
            publicKey = key,
            publicKeyBase64 = SshKeys.publicKeyBase64(key),
            fingerprintSha256 = SshKeys.fingerprintSha256(key),
        )
        val known = policy.trustedKeys(host, this.port)
        if (known.isEmpty()) return policy.onUnknownHost(request)
        if (known.any { it.publicKeyBase64 == request.publicKeyBase64 }) {
            policy.onKnownHostSeen(request)
            return true
        }
        val sameType = known.filter { it.keyType == request.keyType }
        // A new key of a type we have never seen for this host is still a first use for that type.
        return if (sameType.isEmpty()) policy.onUnknownHost(request) else policy.onChangedHostKey(request, known)
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        policy.trustedKeys(host, this.port).map { it.keyType }.distinct()
}

/** Accepts everything; for tests and for explicit "trust once" reconnects. */
object AcceptAllHostKeys : HostKeyPolicy {
    override fun trustedKeys(host: String, port: Int): List<TrustedHostKey> = emptyList()
    override fun onUnknownHost(request: HostKeyRequest): Boolean = true
    override fun onChangedHostKey(request: HostKeyRequest, known: List<TrustedHostKey>): Boolean = true
}

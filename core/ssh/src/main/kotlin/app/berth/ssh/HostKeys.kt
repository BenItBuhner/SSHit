package app.berth.ssh

import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

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

/** How a fingerprint someone wrote down compared with the key a server presented. */
enum class FingerprintCheck {
    /** The fingerprint is this key's. */
    MATCH,

    /** The fingerprint is readable and is some other key's. */
    MISMATCH,

    /** Not a fingerprint in any form Berth reads; nothing was compared. */
    UNREADABLE,
}

/**
 * Fingerprints as people and links write them, checked against a key. An `ssh://` link may carry
 * one as its `;fingerprint=` connection parameter, an administrator hands one over in a message,
 * and either can be in any of the forms that have been printed over the years:
 *
 * - `SHA256:` and 43 characters of base64, as `ssh-keygen -l` prints today; padding optional, the
 *   URL alphabet (`-_` for `+/`) accepted, since `/` cannot stand unencoded in a link's user part
 *   and a link may spell it that way instead.
 * - `MD5:` and sixteen hex pairs, as `ssh-keygen -l -E md5` prints; or the bare pairs (RFC 4716
 *   section 4); joined by `:` or by `-`.
 * - The ssh URI draft's own `host-key-alg-fingerprint`: the key algorithm's name, a dash, and the
 *   pairs joined by dashes, since its parameter value allows letters, digits and `-` alone
 *   (`ssh-dss-c1-b1-30-29-d7-b8-de-6c-97-77-10-d7-46-41-63-87`, its example). The algorithm is not
 *   compared: the key's type is on the sheet beside the fingerprint.
 * - Thirty-two hex pairs, `:` or `-` joined, with or without the algorithm: a SHA-256 written in
 *   hex, as some tools print it.
 *
 * Case and whitespace do not matter. Anything else is [FingerprintCheck.UNREADABLE], so a mangled
 * value is never mistaken for a mismatch or a match.
 */
object HostKeyFingerprints {
    private val BASE64 = Regex("""^[A-Za-z0-9+/]{43}$""")
    private val HEX_PAIR = Regex("""^[0-9a-f]{2}$""")

    fun check(expected: String, key: PublicKey): FingerprintCheck {
        val normalized = normalize(expected) ?: return FingerprintCheck.UNREADABLE
        val actual = if (normalized.startsWith("MD5:")) md5(key) else SshKeys.fingerprintSha256(key)
        return if (normalized == actual) FingerprintCheck.MATCH else FingerprintCheck.MISMATCH
    }

    /**
     * The fingerprint in the form Berth shows it (`SHA256:` and unpadded base64, or `MD5:` and
     * lower-case colon-joined hex pairs), or null when [text] is not a fingerprint in any form read here.
     */
    fun normalize(text: String): String? {
        val compact = text.filterNot { it.isWhitespace() }
        val lower = compact.lowercase()
        return when {
            lower.startsWith("sha256:") -> {
                val body = compact.substring("sha256:".length)
                sha256Body(body) ?: hexPairs(body.lowercase().split(':', '-'))?.takeIf { it.startsWith("SHA256:") }
            }
            lower.startsWith("md5:") -> hexPairs(lower.substring("md5:".length).split(':', '-'))?.takeIf { it.startsWith("MD5:") }
            else -> hexPairs(lower.split(':', '-')) ?: draftForm(lower) ?: sha256Body(compact)
        }
    }

    private fun sha256Body(text: String): String? {
        val body = text.trimEnd('=').replace('-', '+').replace('_', '/')
        return if (BASE64.matches(body)) "SHA256:$body" else null
    }

    /** Sixteen hex pairs are an MD5, thirty-two a SHA-256, each written as Berth shows it; anything else is not read. */
    private fun hexPairs(parts: List<String>): String? {
        if (parts.any { !HEX_PAIR.matches(it) }) return null
        return when (parts.size) {
            16 -> "MD5:" + parts.joinToString(":")
            32 -> "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { parts[it].toInt(16).toByte() })
            else -> null
        }
    }

    /**
     * The draft's `host-key-alg-fingerprint`, dashes throughout: the pairs are the last sixteen or
     * thirty-two dash-joined segments and the algorithm's name is whatever comes before them
     * (`ssh-rsa`, `ecdsa-sha2-nistp256`, `sk-ssh-ed25519@openssh.com`). The segment before the
     * pairs must not read as a pair itself, so where the algorithm ends is never a guess: a value
     * with one pair too many is not read as the wrong thirty-two, and no algorithm name ends in one.
     */
    private fun draftForm(lower: String): String? {
        if (':' in lower) return null
        val parts = lower.split('-')
        for (count in intArrayOf(32, 16)) {
            if (parts.size <= count || HEX_PAIR.matches(parts[parts.size - count - 1])) continue
            hexPairs(parts.takeLast(count))?.let { return it }
        }
        return null
    }

    /** `MD5:` and the colon-separated hex of the key blob's MD5, as `ssh-keygen -l -E md5` prints. */
    fun md5(key: PublicKey): String {
        val digest = MessageDigest.getInstance("MD5").digest(SshKeys.publicKeyBlob(key))
        return "MD5:" + digest.joinToString(":") { "%02x".format(it) }
    }
}

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

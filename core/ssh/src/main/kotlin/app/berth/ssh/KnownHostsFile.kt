package app.berth.ssh

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OpenSSH's `known_hosts`, read the way `sshd(8)` describes it so a file pasted or picked from a
 * laptop lands as Berth known hosts:
 *
 *     [@marker] hostpatterns keytype base64 [comment]
 *
 * Host patterns are comma-separated; each is a name or address, `[name]:port` when the port is not
 * 22, a wildcard with `*` or `?`, or a negation with `!`. A line may instead carry one hashed name,
 * `|1|salt|hmac`, whose host is unrecoverable; such a line is read for a host Berth already has
 * by hashing each saved address the way `ssh-keygen -H` did and comparing. Berth trusts keys per
 * exact host and port, so a wildcard or negated pattern is not a host it can hold and is
 * skipped, as are `@cert-authority` and `@revoked` lines, SSH-1 keys and anything that does not
 * decode. Every skip names its line and reason, so the import sheet can say what was left out.
 */
object KnownHostsFile {
    data class Entry(
        /** The name or address as Berth keys it, without brackets. */
        val host: String,
        val port: Int,
        /** The wire name, e.g. `ssh-ed25519`, as read from the decoded key. */
        val keyType: String,
        val publicKeyBase64: String,
        val fingerprintSha256: String,
        /** True when the line was hashed and [host] was found among the addresses given. */
        val hashed: Boolean,
        /** One-based line in the text, for saying where a key came from. */
        val line: Int,
    ) {
        /** `host:port` as the rows show it, the port only when it is not 22. */
        val address: String get() = if (port == 22) host else "$host:$port"
    }

    data class Skipped(val line: Int, val reason: String)

    data class Parsed(
        val entries: List<Entry>,
        val skipped: List<Skipped>,
        /** Hashed lines whose host none of the given addresses matched; a count the sheet can explain in one line. */
        val hashedUnresolved: Int,
    ) {
        val isEmpty: Boolean get() = entries.isEmpty()
    }

    /** The most lines read from one text; a pasted file past that is refused rather than parsed forever. */
    const val MAX_LINES = 20_000

    private val KEY_TYPE = Regex("""^(ssh-|ecdsa-sha2-|sk-)[A-Za-z0-9@.-]+$""")
    private val PORT = Regex("""^\[(.+)]:(\d{1,5})$""")

    /**
     * Reads [text]; [knownAddresses] are `host to port` pairs (saved hosts, known hosts) that hashed
     * lines are tried against. Never throws: a line that is not a host key is a [Skipped].
     */
    fun parse(text: String, knownAddresses: Collection<Pair<String, Int>> = emptyList()): Parsed {
        val entries = LinkedHashMap<Triple<String, Int, String>, Entry>()
        val skipped = ArrayList<Skipped>()
        var hashedUnresolved = 0
        val candidates = knownAddresses.map { (host, port) -> Triple(host, port, patternFor(host, port)) }.distinct()
        text.lineSequence().take(MAX_LINES).forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val fields = line.split(Regex("\\s+")).toMutableList()
            if (fields.first().startsWith("@")) {
                val marker = fields.removeAt(0)
                skipped += Skipped(
                    lineNumber,
                    when (marker.lowercase()) {
                        "@cert-authority" -> "a certificate authority, not a host key"
                        "@revoked" -> "a revoked key"
                        else -> "an unknown marker ${clip(marker)}"
                    },
                )
                return@forEachIndexed
            }
            if (fields.size < 3) {
                skipped += Skipped(lineNumber, "not a host key line")
                return@forEachIndexed
            }
            val (patterns, typeField, base64) = fields
            if (typeField.all { it.isDigit() }) {
                skipped += Skipped(lineNumber, "an SSH-1 key, which no server offers any more")
                return@forEachIndexed
            }
            if (!KEY_TYPE.matches(typeField)) {
                skipped += Skipped(lineNumber, "not a host key line")
                return@forEachIndexed
            }
            val key = runCatching { SshKeys.parsePublicKeyBlob(base64) }.getOrNull()
            if (key == null) {
                skipped += Skipped(lineNumber, "the key doesn't decode")
                return@forEachIndexed
            }
            val keyType = SshKeys.keyTypeName(key)
            val fingerprint = SshKeys.fingerprintSha256(key)
            val blob = SshKeys.publicKeyBase64(key)

            val addresses = ArrayList<Pair<String, Int>>()
            var hashed = false
            if (patterns.startsWith("|1|")) {
                hashed = true
                val match = resolveHashed(patterns, candidates)
                if (match == null) {
                    hashedUnresolved++
                    skipped += Skipped(lineNumber, "a hashed host name that matches no saved host")
                    return@forEachIndexed
                }
                addresses += match
            } else {
                for (pattern in patterns.split(',')) {
                    when {
                        pattern.isEmpty() -> continue
                        pattern.startsWith("!") -> skipped += Skipped(lineNumber, "a negated pattern ${clip(pattern)}")
                        '*' in pattern || '?' in pattern -> skipped += Skipped(lineNumber, "a wildcard pattern ${clip(pattern)}")
                        else -> {
                            val address = address(pattern)
                            if (address == null) skipped += Skipped(lineNumber, "${clip(pattern)} isn't a host and port") else addresses += address
                        }
                    }
                }
            }
            for ((host, port) in addresses) {
                entries.putIfAbsent(Triple(host, port, blob), Entry(host, port, keyType, blob, fingerprint, hashed, lineNumber))
            }
        }
        return Parsed(entries.values.toList(), skipped, hashedUnresolved)
    }

    /** `[name]:port` or a bare name; null for an empty name or a port out of range. */
    private fun address(pattern: String): Pair<String, Int>? {
        val bracketed = PORT.matchEntire(pattern)
        if (bracketed != null) {
            val host = bracketed.groupValues[1]
            val port = bracketed.groupValues[2].toIntOrNull() ?: return null
            return if (host.isBlank() || port !in 1..65535) null else host to port
        }
        if (pattern.startsWith("[") && pattern.endsWith("]")) {
            // `[name]` with no port is how an IPv6 address on port 22 may be written by hand.
            return pattern.substring(1, pattern.length - 1).takeIf { it.isNotBlank() }?.let { it to 22 }
        }
        return pattern.takeIf { it.isNotBlank() }?.let { it to 22 }
    }

    /** The pattern OpenSSH writes and hashes for an address: the name alone on port 22, `[name]:port` otherwise. */
    fun patternFor(host: String, port: Int): String = if (port == 22) host else "[$host]:$port"

    /** `|1|salt|hmac` against the candidates' patterns; HMAC-SHA1 keyed by the salt, as `ssh-keygen -H` hashes. */
    private fun resolveHashed(field: String, candidates: List<Triple<String, Int, String>>): Pair<String, Int>? {
        val parts = field.split('|')
        if (parts.size != 4) return null
        val salt = runCatching { Base64.getDecoder().decode(parts[2]) }.getOrNull() ?: return null
        val expected = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull() ?: return null
        val mac = runCatching { Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) } }.getOrNull() ?: return null
        for ((host, port, pattern) in candidates) {
            if (mac.doFinal(pattern.toByteArray(Charsets.UTF_8)).contentEquals(expected)) return host to port
            // A name saved with capitals was hashed as typed; OpenSSH lowercases before hashing.
            val lower = pattern.lowercase()
            if (lower != pattern && mac.doFinal(lower.toByteArray(Charsets.UTF_8)).contentEquals(expected)) return host to port
        }
        return null
    }

    private fun clip(part: String): String {
        val clean = part.filterNot { it.isISOControl() }
        return "\u201C" + (if (clean.length > 40) clean.take(40) + "\u2026" else clean) + "\u201D"
    }
}

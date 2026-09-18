package app.berth.domain.model

import kotlinx.serialization.Serializable

/** The 12 host swatch colours from the design system, as 0xRRGGBB. */
enum class SwatchColor(val rgb: Int) {
    COPPER(0xB8683A), VERDIGRIS(0x2F8F7E), SLATE(0x4E6E9E), MOSS(0x5F7F3E),
    PLUM(0x7A4E7C), TERRACOTTA(0xA8503F), OCHRE(0xA8823A), TEAL(0x2E7F8C),
    INDIGO(0x4A4F8F), OLIVE(0x6E7A3A), RUST(0x8F4B2E), GRAPHITE(0x55534F);

    companion object {
        /** Stable colour for a name so hosts created from the same name look the same. */
        fun forName(name: String): SwatchColor = entries[(name.hashCode() and 0x7FFFFFFF) % entries.size]
    }
}

/** How to authenticate to a host. Secrets are referenced, never embedded. */
@Serializable
sealed interface AuthMethod {
    @Serializable
    data class Key(val identityId: String) : AuthMethod

    /** [secretId] refers to an encrypted secret in the data layer; null means ask each time. */
    @Serializable
    data class Password(val secretId: String? = null) : AuthMethod

    @Serializable
    data object AskEachTime : AuthMethod
}

enum class TmuxMode { OFF, ATTACH_OR_CREATE, ATTACH_ONLY }

enum class Transport { SSH }

enum class AddressFamily { AUTO, IPV4, IPV6 }

/** Session persistence knobs (L1 in-app reconnect and L2 tmux helper). */
@Serializable
data class PersistencePolicy(
    val keepaliveSeconds: Int = 15,
    /** Total time to keep retrying before giving up; 0 means forever. */
    val reconnectMinutes: Int = 15,
    val tmux: TmuxMode = TmuxMode.OFF,
    /** Defaults to `berth-<host name>` when null. */
    val tmuxSessionName: String? = null,
    /** tmux prefix in tmux notation, drives the Deck's tmux layer. */
    val tmuxPrefix: String = "C-b",
    val transport: Transport = Transport.SSH,
)

/** Per-host appearance overrides; null fields inherit the app default. */
@Serializable
data class AppearanceOverride(
    val terminalThemeId: String? = null,
    val fontSizeSp: Int? = null,
    val fontFamily: String? = null,
)

@Serializable
data class Host(
    val id: String,
    val name: String,
    val color: SwatchColor,
    /** Two characters; derived from [name] unless edited. */
    val monogram: String,
    val address: String,
    val port: Int = 22,
    val user: String,
    val auth: AuthMethod = AuthMethod.AskEachTime,
    /** ProxyJump chain, first hop first. */
    val jumpHostIds: List<String> = emptyList(),
    val persistence: PersistencePolicy = PersistencePolicy(),
    val startupCommand: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val terminalType: String = "xterm-256color",
    val agentForwarding: Boolean = false,
    val compression: Boolean = false,
    val addressFamily: AddressFamily = AddressFamily.AUTO,
    val appearance: AppearanceOverride = AppearanceOverride(),
    val tags: List<String> = emptyList(),
    val muteBell: Boolean = false,
    val lastConnectedAt: Long? = null,
    val createdAt: Long,
) {
    val userAtHost: String get() = "$user@$address"

    companion object {
        fun monogramFor(name: String): String {
            val words = name.trim().split(Regex("[\\s_\\-.]+")).filter { it.isNotEmpty() }
            val raw = when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                words.size == 1 -> words[0].take(2)
                else -> "??"
            }
            return raw.uppercase().filter { it.isLetterOrDigit() }.padEnd(2, '?').take(2)
        }
    }
}

/** A host key the user has accepted. Multiple keys per host:port are allowed for rotation. */
@Serializable
data class KnownHostKey(
    val id: String,
    val host: String,
    val port: Int,
    /** e.g. `ssh-ed25519`. */
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprintSha256: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
)

enum class TunnelType { LOCAL, REMOTE, DYNAMIC }

@Serializable
data class Tunnel(
    val id: String,
    val hostId: String,
    val type: TunnelType,
    val bindAddress: String = "127.0.0.1",
    val bindPort: Int,
    /** Unused for [TunnelType.DYNAMIC]. */
    val destinationHost: String = "localhost",
    val destinationPort: Int = 0,
    val enabled: Boolean = true,
)

enum class SnippetAction { RUN, PASTE }

@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val body: String,
    /** Null scope is global. */
    val hostId: String? = null,
    val tags: List<String> = emptyList(),
    val defaultAction: SnippetAction = SnippetAction.RUN,
    val runOnConnect: Boolean = false,
    val pinnedToDeck: Boolean = false,
) {
    /** `{{name}}` and `{{name:default}}` placeholders, in order of first appearance. */
    fun placeholders(): List<Pair<String, String?>> =
        PLACEHOLDER.findAll(body).map { it.groupValues[1] to it.groupValues.getOrNull(2)?.ifEmpty { null } }
            .distinctBy { it.first }.toList()

    companion object {
        val PLACEHOLDER = Regex("\\{\\{([A-Za-z0-9_]+)(?::([^}]*))?}}")
    }
}

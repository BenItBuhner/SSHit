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

/**
 * Session persistence knobs (L1 in-app reconnect and L2 tmux helper). Keepalive and Reconnect are
 * the host's own only when set: null inherits Settings › Connection's defaults (spec C20,
 * [ConnectionSettings]), read when the login uses them, so a change there reaches every host that
 * set neither.
 */
@Serializable
data class PersistencePolicy(
    /** Seconds between keepalives; 0 is off. */
    val keepaliveSeconds: Int? = null,
    /** Total time to keep retrying before giving up; 0 means forever. */
    val reconnectMinutes: Int? = null,
    val tmux: TmuxMode = TmuxMode.OFF,
    /** Defaults to `berth-<host name>` when null. */
    val tmuxSessionName: String? = null,
    /** tmux prefix in tmux notation, drives the Deck's tmux layer. */
    val tmuxPrefix: String = "C-b",
    val transport: Transport = Transport.SSH,
) {
    fun effectiveKeepaliveSeconds(defaults: ConnectionSettings): Int = keepaliveSeconds ?: defaults.keepaliveSeconds

    fun effectiveReconnectMinutes(defaults: ConnectionSettings): Int = reconnectMinutes ?: defaults.reconnectMinutes

    /**
     * This policy as written before hosts could inherit: every build until then stored the host
     * editor's starting values (15 s, 15 min) on a host that never changed them, so those read
     * as inherit, and anything else as the host's own. The database's version 7 and a format 1
     * bundle both come through here; Settings › Connection starts at the same values, so the
     * host behaves as it did until the defaults move.
     */
    fun foldLegacyDefaults(): PersistencePolicy = copy(
        keepaliveSeconds = keepaliveSeconds.takeUnless { it == LEGACY_KEEPALIVE_SECONDS },
        reconnectMinutes = reconnectMinutes.takeUnless { it == LEGACY_RECONNECT_MINUTES },
    )

    companion object {
        const val LEGACY_KEEPALIVE_SECONDS = 15
        const val LEGACY_RECONNECT_MINUTES = 15
    }
}

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
    /**
     * Connect opens the host's port forwards with no shell (a [TabKind.Tunnels] tab) instead of a
     * terminal: for a bastion whose only job is to carry tunnels. Terminal and Files stay a menu away.
     */
    val tunnelsOnly: Boolean = false,
    val lastConnectedAt: Long? = null,
    val createdAt: Long,
) {
    val userAtHost: String get() = "$user@$address"

    /**
     * A Quick connect login (spec C11): its id carries [QUICK_ID_PREFIX]. Unsaved as it opens; the
     * Session sheet's Save as host keeps the tab's id, so a saved host can carry the prefix too.
     */
    val isQuickConnect: Boolean get() = id.startsWith(QUICK_ID_PREFIX)

    /**
     * The key this host's commands are kept under (spec C16, History per host): a saved host's id,
     * so every tab on it shares one history; for a quick connect the login itself
     * ([quickCommandHistoryKey]), so a later quick connect to the same box finds the commands the
     * first one ran, and a host saved from its tab, under the tab's id, goes on with them. The
     * Quick connect sheet's Save as host gives its host an id of its own and moves the login's
     * history under it.
     */
    val commandHistoryKey: String get() = if (isQuickConnect) quickCommandHistoryKey(user, address, port) else id

    companion object {
        const val QUICK_ID_PREFIX = "quick-"
        const val QUICK_HISTORY_PREFIX = "quick:"

        /** The history key of a quick connect login: `quick:user@host`, the port after it when it is not 22. */
        fun quickCommandHistoryKey(user: String, address: String, port: Int): String =
            QUICK_HISTORY_PREFIX + "$user@$address" + if (port == 22) "" else ":$port"

        /** The login a quick connect's [commandHistoryKey] names (`user@host[:port]`), or null for a saved host's key. */
        fun quickConnectLabel(commandHistoryKey: String): String? =
            commandHistoryKey.removePrefix(QUICK_HISTORY_PREFIX).takeIf { commandHistoryKey.startsWith(QUICK_HISTORY_PREFIX) }

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

/**
 * A host key the user has accepted. Multiple keys per host:port are allowed for rotation. [host]
 * is kept the way OpenSSH keeps a `known_hosts` name, lowercased ([canonicalHost]): DNS reads a
 * name case-blind, so `Prod-API.example.com` and `prod-api.example.com` are one endpoint to the
 * trust check whichever way the address was typed into the editor. The store folds the name on
 * write and matches it case-blind on read; a key made from a request or a file may spell it any way.
 */
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
    /**
     * A pinned key is the only acceptable key of its type for this endpoint: a different key is
     * refused outright instead of raising the "key changed" sheet, and no new key types are
     * accepted on first use.
     */
    val pinned: Boolean = false,
) {
    val endpoint: String get() = if (port == 22) host else "$host:$port"

    /** `ED25519`, `RSA`, `ECDSA P-256`: the algorithm the way the UI names it. */
    val algorithmLabel: String get() = algorithmLabelFor(keyType)

    companion object {
        /**
         * [host] as the store keys it: the ASCII letters lowercased, as `ssh` lowercases a name
         * before it writes `known_hosts` and as SQLite's NOCASE compares one; the rest as typed (a
         * literal, a punycode label, a letter outside ASCII, which neither of those folds either).
         */
        fun canonicalHost(host: String): String {
            if (host.none { it in 'A'..'Z' }) return host
            return buildString(host.length) { for (ch in host) append(if (ch in 'A'..'Z') ch.lowercaseChar() else ch) }
        }

        fun algorithmLabelFor(keyType: String): String = when {
            keyType == "ssh-ed25519" -> "ED25519"
            keyType == "ssh-rsa" || keyType.startsWith("rsa-sha2") -> "RSA"
            keyType == "ssh-dss" -> "DSA"
            keyType.startsWith("ecdsa-sha2-nistp") -> "ECDSA P-" + keyType.removePrefix("ecdsa-sha2-nistp")
            keyType.startsWith("sk-") -> "FIDO " + algorithmLabelFor(keyType.removePrefix("sk-").substringBefore('@'))
            else -> keyType
        }
    }
}

enum class TunnelType(val label: String) { LOCAL("Local"), REMOTE("Remote"), DYNAMIC("Dynamic") }

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
) {
    /** The row's Mono line: `127.0.0.1:8080 → localhost:80`, `remote:9000 → 127.0.0.1:3000`, `SOCKS5 on 127.0.0.1:1080`. */
    val spec: String
        get() = when (type) {
            TunnelType.LOCAL -> "${listenLabel()} \u2192 $destinationHost:$destinationPort"
            TunnelType.REMOTE -> "remote:${if (bindAddress.isDefaultBind()) "" else "$bindAddress:"}$bindPort \u2192 $destinationHost:$destinationPort"
            TunnelType.DYNAMIC -> "SOCKS5 on ${listenLabel()}"
        }

    /** True when the listener accepts connections from other devices. */
    val exposed: Boolean get() = type != TunnelType.REMOTE && (bindAddress == "0.0.0.0" || bindAddress == "::" || bindAddress == "*")

    /** A browser URL for local forwards whose destination looks like a web port; null otherwise. */
    val openUrl: String?
        get() {
            if (type != TunnelType.LOCAL) return null
            val scheme = when (destinationPort) {
                443, 8443 -> "https"
                80, 8080, 8000, 8008, 8888, 3000, 4200, 5000, 5173, 8081, 9000, 9090 -> "http"
                else -> return null
            }
            return "$scheme://${if (exposed) "127.0.0.1" else bindAddress}:$bindPort/"
        }

    private fun listenLabel(): String = "${if (exposed) "0.0.0.0" else bindAddress}:$bindPort"

    /**
     * A reason this tunnel cannot be saved, or null. [others] are the host's other tunnels plus
     * every enabled tunnel elsewhere, so listeners on the same device port are caught too.
     */
    fun validate(others: List<Tunnel>): String? {
        if (bindPort !in 1..65535) return "Port must be between 1 and 65535."
        if (bindAddress.isBlank()) return "Bind address is needed."
        if (type != TunnelType.DYNAMIC) {
            if (destinationHost.isBlank()) return "Destination host is needed."
            if (destinationPort !in 1..65535) return "Destination port must be between 1 and 65535."
        }
        val clash = others.firstOrNull { other ->
            other.id != id && other.enabled && other.bindPort == bindPort && other.listensLikelySame(this)
        }
        if (clash != null) {
            return if (clash.hostId == hostId) "Port $bindPort is already used by another tunnel on this host."
            else "Port $bindPort is already used by a tunnel on another host."
        }
        return null
    }

    private fun listensLikelySame(other: Tunnel): Boolean {
        val local = type != TunnelType.REMOTE
        val otherLocal = other.type != TunnelType.REMOTE
        if (local != otherLocal) return false
        // Remote listeners live on the server, so they only clash with the same host's tunnels.
        if (!local) return hostId == other.hostId && bindAddress == other.bindAddress
        return exposed || other.exposed || bindAddress == other.bindAddress
    }

    private fun String.isDefaultBind() = this == "127.0.0.1" || this == "localhost" || this.isEmpty()
}

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
    /** Workspace this snippet belongs to; null means it shows everywhere. */
    val workspaceId: String? = null,
) {
    /** `{{name}}` and `{{name:default}}` placeholders, in order of first appearance. */
    fun placeholders(): List<Pair<String, String?>> =
        PLACEHOLDER.findAll(body).map { it.groupValues[1] to it.groupValues.getOrNull(2)?.ifEmpty { null } }
            .distinctBy { it.first }.toList()

    val hasPlaceholders: Boolean get() = PLACEHOLDER.containsMatchIn(body)

    /** The body with placeholders filled from [values], falling back to each placeholder's default. */
    fun render(values: Map<String, String> = emptyMap()): String = PLACEHOLDER.replace(body) { match ->
        val name = match.groupValues[1]
        values[name] ?: match.groupValues.getOrNull(2)?.ifEmpty { null } ?: ""
    }

    /** First line of the body, for list rows. */
    val preview: String get() = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""

    /** True when this snippet should be offered for [hostId] in [workspaceId]. */
    fun visibleFor(hostId: String?, workspaceId: String?): Boolean =
        (this.hostId == null || this.hostId == hostId) && (this.workspaceId == null || this.workspaceId == workspaceId)

    companion object {
        val PLACEHOLDER = Regex("\\{\\{([A-Za-z0-9_]+)(?::([^}]*))?}}")
    }
}

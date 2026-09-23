package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * How long Berth may sit in the background before the lock screen covers it again. A cold start
 * always locks; this only shapes what happens when a live process comes back to the front.
 */
@Serializable
enum class LockTimeout(val millis: Long) {
    IMMEDIATELY(0L),
    ONE_MINUTE(60_000L),
    FIVE_MINUTES(5 * 60_000L),

    /** Only a launch locks; leaving and returning never does. */
    NEVER(Long.MAX_VALUE),
}

/** How long a copy Berth made stays on the clipboard before Berth clears it again. */
@Serializable
enum class ClipboardClear(val millis: Long?) {
    OFF(null),
    THIRTY_SECONDS(30_000L),
    SIXTY_SECONDS(60_000L),
}

/** One host's answer to clipboard writes from the remote (OSC 52), over the app-wide switch. */
@Serializable
enum class RemoteClipboardPolicy { INHERIT, ALLOW, DENY }

/** Settings › Security (spec C20). One document, so a change to any field lands atomically. */
@Serializable
data class SecuritySettings(
    val appLock: Boolean = false,
    /**
     * A minute by default: a terminal invites leaving for a password manager or a 2FA app and coming
     * straight back, and a lock on every return would ask each time. Immediately stays on the menu.
     */
    val lockTimeout: LockTimeout = LockTimeout.ONE_MINUTE,
    /** FLAG_SECURE: no screenshots, no recents preview, for every window the app opens. */
    val blockScreenshots: Boolean = false,
    val clipboardClear: ClipboardClear = ClipboardClear.OFF,
    /** Whether programs on a remote may write this phone's clipboard (OSC 52) unless a host says otherwise. */
    val remoteClipboard: Boolean = false,
    val remoteClipboardByHost: Map<String, RemoteClipboardPolicy> = emptyMap(),
    /** Hosts that have already had the one-time notice about a blocked clipboard write. */
    val remoteClipboardNoticed: Set<String> = emptySet(),
    /**
     * Hosts whose forwarded agent signs without asking each time. Every other host with agent
     * forwarding on asks the user before each signature; a key under biometric protection still
     * takes its prompt either way.
     */
    val agentSignsSilently: Set<String> = emptySet(),
) {
    fun remoteClipboardPolicy(hostId: String): RemoteClipboardPolicy = remoteClipboardByHost[hostId] ?: RemoteClipboardPolicy.INHERIT

    /** Whether a clipboard write from [hostId] goes through, after the host's override and the app-wide switch. */
    fun allowsRemoteClipboard(hostId: String): Boolean = when (remoteClipboardPolicy(hostId)) {
        RemoteClipboardPolicy.ALLOW -> true
        RemoteClipboardPolicy.DENY -> false
        RemoteClipboardPolicy.INHERIT -> remoteClipboard
    }

    fun withHostRemoteClipboard(hostId: String, policy: RemoteClipboardPolicy): SecuritySettings = copy(
        remoteClipboardByHost = if (policy == RemoteClipboardPolicy.INHERIT) remoteClipboardByHost - hostId else remoteClipboardByHost + (hostId to policy),
    )

    fun signsAgentSilently(hostId: String): Boolean = hostId in agentSignsSilently

    fun withHostAgentSilent(hostId: String, silent: Boolean): SecuritySettings = copy(
        agentSignsSilently = if (silent) agentSignsSilently + hostId else agentSignsSilently - hostId,
    )

    /** The document without anything kept for [hostId], for a host that was deleted. */
    fun withoutHost(hostId: String): SecuritySettings = copy(
        remoteClipboardByHost = remoteClipboardByHost - hostId,
        remoteClipboardNoticed = remoteClipboardNoticed - hostId,
        agentSignsSilently = agentSignsSilently - hostId,
    )
}

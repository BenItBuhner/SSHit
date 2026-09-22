package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * A named, coloured group of sessions. With tabs (UX spec C3) a workspace is a tab group: a
 * contiguous run of tabs in the strip, headed by its chip once a second group exists.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val color: SwatchColor,
    val monogram: String,
    /** Optional interface accent for this workspace as 0xRRGGBB; null inherits. */
    val accentRgb: Int? = null,
    /** Terminal theme for sessions in this workspace; a host's own override wins, null inherits the app default. */
    val terminalThemeId: String? = null,
    val sortOrder: Int = 0,
    /** Reconnect Live sessions at launch instead of restoring frames only. */
    val reconnectAtLaunch: Boolean = false,
    val createdAt: Long,
    /** The group's run is folded into its chip in the tab strip (spec C3, Groups). */
    val collapsed: Boolean = false,
) {
    companion object {
        const val DEFAULT_ID = "workspace-default"
        const val DEFAULT_NAME = "Home"
    }
}

/**
 * Session lifecycle from the product vision, section 4.2.
 *
 * ```
 * IDLE -> CONNECTING -> LIVE <-> RECONNECTING -> DETACHED -> CLOSED
 *              \-> FAILED
 * ```
 */
enum class SessionState {
    IDLE, CONNECTING, LIVE, RECONNECTING, DETACHED, FAILED, CLOSED;

    val isActive: Boolean get() = this == CONNECTING || this == LIVE || this == RECONNECTING
    val keepsService: Boolean get() = isActive
}

/** Which persistence layer currently holds a session (product vision, section 4.3). */
enum class PersistenceLayer {
    /** Only the saved frame and scrollback exist; nothing is connected. */
    LOCAL_FRAME,

    /** The foreground service holds a live socket. */
    IN_APP,

    /** A tmux session on the server holds the shell. */
    TMUX,
}

/**
 * Persisted metadata for a session, which is one tab (UX spec C3). The live connection and
 * terminal are runtime objects owned by the session manager; this is what survives process death
 * so the strip comes back in the same order with the same active tab.
 */
@Serializable
data class SessionRecord(
    val id: String,
    /** The tab's group; [sortOrder] is its position within the group. */
    val workspaceId: String,
    /** Null for unsaved quick-connect sessions; [hostSnapshot] carries what is needed to reconnect. */
    val hostId: String?,
    val hostSnapshot: Host,
    val state: SessionState,
    val layer: PersistenceLayer = PersistenceLayer.LOCAL_FRAME,
    val title: String = "",
    /** OSC 7 working directory, path portion only. */
    val cwd: String? = null,
    /** Last command reported by OSC 133 or captured from input. */
    val lastCommand: String? = null,
    val needsAttention: Boolean = false,
    /** Set when the terminal is not on stage; drives the "needs you" pill. */
    val attentionReason: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Long,
    /** Last moment the session was Live, for "Detached 4 min ago". */
    val lastLiveAt: Long? = null,
    /** Frozen frame stored by the data layer, referenced by key. */
    val frameKey: String? = null,
    /** What this tab runs; every session today is [TabKind.Ssh]. */
    val kind: TabKind = TabKind.Ssh,
    /** A title the user set from the tab's menu; null shows the automatic one ([displayTitle]). */
    val customTitle: String? = null,
) {
    /** The tab's title: the rename when set, then the OSC or tmux title, then the host name. */
    val displayTitle: String get() = customTitle?.takeIf { it.isNotBlank() } ?: title.ifBlank { hostSnapshot.name }
}

/** Reconnect schedule: 1, 2, 4, 8, 15, 30 s then every 60 s until the policy's limit. */
object ReconnectBackoff {
    private val STEPS_SECONDS = intArrayOf(1, 2, 4, 8, 15, 30)

    fun delaySeconds(attempt: Int): Int = if (attempt < STEPS_SECONDS.size) STEPS_SECONDS[attempt] else 60

    /** Whether another attempt is allowed given elapsed time and the host's effective window in minutes (0 = forever). */
    fun shouldRetry(elapsedMillis: Long, reconnectMinutes: Int): Boolean =
        reconnectMinutes <= 0 || elapsedMillis < reconnectMinutes * 60_000L
}

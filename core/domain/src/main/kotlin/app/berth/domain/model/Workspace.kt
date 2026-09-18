package app.berth.domain.model

import kotlinx.serialization.Serializable

/** A named, coloured group of sessions; the unit the rail navigates. */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val color: SwatchColor,
    val monogram: String,
    /** Optional interface accent for this workspace as 0xRRGGBB; null inherits. */
    val accentRgb: Int? = null,
    val sortOrder: Int = 0,
    /** Reconnect Live sessions at launch instead of restoring frames only. */
    val reconnectAtLaunch: Boolean = false,
    val createdAt: Long,
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
 * Persisted metadata for a session. The live connection and terminal are runtime objects owned
 * by the session manager; this is what survives process death so the rail can be restored.
 */
@Serializable
data class SessionRecord(
    val id: String,
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
)

/** Reconnect schedule: 1, 2, 4, 8, 15, 30 s then every 60 s until the policy's limit. */
object ReconnectBackoff {
    private val STEPS_SECONDS = intArrayOf(1, 2, 4, 8, 15, 30)

    fun delaySeconds(attempt: Int): Int = if (attempt < STEPS_SECONDS.size) STEPS_SECONDS[attempt] else 60

    /** Whether another attempt is allowed given elapsed time and the host's policy (0 = forever). */
    fun shouldRetry(elapsedMillis: Long, policy: PersistencePolicy): Boolean =
        policy.reconnectMinutes <= 0 || elapsedMillis < policy.reconnectMinutes * 60_000L
}

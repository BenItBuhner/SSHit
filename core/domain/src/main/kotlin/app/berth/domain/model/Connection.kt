package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * How long a terminal may sit idle, nothing typed into it and nothing arriving from it, before it
 * detaches itself and keeps its frame (spec C20 Connection, vision §4.4). Never by default: a
 * session the user left open is one the user meant to keep. A login carrying tunnels is exempt
 * for as long as it carries them, since the forwards are its work; a Tunnels tab always is.
 */
@Serializable
enum class IdleDetach(val millis: Long?) {
    NEVER(null),
    FIFTEEN_MINUTES(15 * 60_000L),
    ONE_HOUR(60 * 60_000L),
    FOUR_HOURS(4 * 60 * 60_000L),
}

/** Settings › Connection (spec C20). One document, so a change to any field lands atomically. */
@Serializable
data class ConnectionSettings(
    /** Keepalive default: seconds between keepalives for a host that sets none of its own ([PersistencePolicy.keepaliveSeconds]); 0 is off. */
    val keepaliveSeconds: Int = DEFAULT_KEEPALIVE_SECONDS,
    /** Reconnect default: minutes a dropped login keeps retrying for a host that sets none of its own; 0 is forever. */
    val reconnectMinutes: Int = DEFAULT_RECONNECT_MINUTES,
    val idleDetach: IdleDetach = IdleDetach.NEVER,
    /**
     * Whether Back has already said, once, that sessions keep running in the background (spec
     * Part B): the first Back that would leave the app with a login up shows the line instead of
     * leaving, and never again after.
     */
    val backgroundNoticeShown: Boolean = false,
    /**
     * Whether the battery-optimisation explainer has been raised once on its own, on the first
     * connection lost while the app was away (vision §4.4). Settings › Connection › Background
     * opens it any time; this only stops the app from raising it again unasked.
     */
    val batteryExplained: Boolean = false,
) {
    companion object {
        const val DEFAULT_KEEPALIVE_SECONDS = 15
        const val DEFAULT_RECONNECT_MINUTES = 15

        /** The keepalive spans Settings and the host editor offer, in picker order. */
        val KEEPALIVE_CHOICES = listOf(0, 15, 30, 60)

        /** The reconnect windows Settings and the host editor offer, in picker order; 0 (forever) last. */
        val RECONNECT_CHOICES = listOf(5, 15, 60, 0)
    }
}

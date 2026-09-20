package app.berth.android.security

/** Time for timeouts; tests hand in a fake so the lock and clipboard timers can be stepped exactly. */
fun interface MonotonicClock {
    /** Milliseconds since boot, unaffected by the wall clock being set. */
    fun elapsed(): Long
}

object SystemMonotonicClock : MonotonicClock {
    override fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()
}

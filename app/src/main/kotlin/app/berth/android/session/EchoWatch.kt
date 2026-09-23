package app.berth.android.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Whether the remote has stopped echoing what is typed (spec C2's edge case: a password prompt,
 * `read -s`), which the grip shows as a dot, since what is typed then is kept out of the command
 * history. There is no telling it from the protocol, so it is read off what comes back: text
 * [typed] on the primary screen that nothing at all answers within [windowMs] was not echoed.
 * Any [output] clears it, and so does the end of the line ([ended]: Enter, Ctrl+C, Ctrl+D, the
 * shell gone). A full-screen program is left out by the caller, since a key there need not draw.
 */
internal class EchoWatch(private val scope: CoroutineScope, private val windowMs: Long = ECHO_OFF_AFTER_MS) {
    private val _off = MutableStateFlow(false)
    val off: StateFlow<Boolean> = _off.asStateFlow()

    /** Bumps on every arrival, so a check can tell whether anything came since the key it follows. */
    @Volatile private var arrivals = 0L
    private var pending: Job? = null

    fun typed() {
        val since = arrivals
        synchronized(this) {
            pending?.cancel()
            pending = scope.launch {
                delay(windowMs)
                if (arrivals == since) _off.value = true
            }
        }
    }

    fun output() {
        arrivals++
        if (_off.value) _off.value = false
    }

    fun ended() {
        synchronized(this) { pending?.cancel() }
        _off.value = false
    }

    companion object {
        /** Past a slow link's echo, short of a user waiting to see whether the prompt took the key. */
        const val ECHO_OFF_AFTER_MS = 750L
    }
}

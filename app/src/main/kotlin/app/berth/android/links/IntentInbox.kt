package app.berth.android.links

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What another app or the launcher handed the activity (spec Part B: Deep links, App shortcuts;
 * C24, the share sheet's file drop), read off the intent in `MainActivity.onResume` and acted on
 * by the view model once the lock allows.
 */
sealed interface Arrival {
    /** An `ssh://` or `sftp://` link (the VIEW filter). */
    data class Link(val raw: String) : Arrival

    /** A launcher shortcut for the saved host [hostId]: connect to it as the host list would. */
    data class OpenHost(val hostId: String) : Arrival

    /** The launcher's Quick connect shortcut: the sheet, its field empty. */
    data object QuickConnect : Arrival

    /** Files from the share sheet (SEND, SEND_MULTIPLE): each lands in the active session's `/tmp`, and its path is pasted. */
    data class Files(val uris: List<Uri>) : Arrival

    /** Text from the share sheet with no file behind it: pasted into the active session as typed text would be. */
    data class Text(val text: String) : Arrival

    companion object {
        /**
         * The arrival [intent] carries, or null when it is not one (a notification's tap, the
         * launcher's plain open): a VIEW of an `ssh://` or `sftp://` link, a SEND or SEND_MULTIPLE
         * with its streams (else its text; one with neither is nothing to act on), or one of the
         * shortcuts' actions ([LauncherShortcuts]), a host shortcut without its host id counting for nothing.
         */
        fun of(intent: Intent): Arrival? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(::Link)
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> shared(intent)
            LauncherShortcuts.ACTION_QUICK_CONNECT -> QuickConnect
            LauncherShortcuts.ACTION_OPEN_HOST -> intent.getStringExtra(LauncherShortcuts.EXTRA_HOST_ID)?.let(::OpenHost)
            else -> null
        }

        private fun shared(intent: Intent): Arrival? {
            val uris = when (intent.action) {
                Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.filterNotNull().orEmpty()
            }
            if (uris.isNotEmpty()) return Files(uris)
            return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotEmpty() }?.let(::Text)
        }
    }
}

/**
 * Arrivals held until the view model is up to act on them. Each is delivered once: one that
 * arrives before anyone listens waits, a listener that comes back after a recreation does not see
 * it again.
 */
@Singleton
class IntentInbox @Inject constructor() {
    private val channel = Channel<Arrival>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Arrivals in order, each to one collector. */
    val arrivals: Flow<Arrival> = channel.receiveAsFlow()

    fun offer(arrival: Arrival) {
        channel.trySend(arrival)
    }

    /** An `ssh://` or `sftp://` link, as [Arrival.Link]. */
    fun offer(link: String) = offer(Arrival.Link(link))
}

package app.berth.android.links

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Links another app handed to Berth (`ssh://`, `sftp://`), read off the activity's intent and held
 * here until the view model is up to act on them. Each link is delivered once: a link that arrives
 * before anyone listens waits, a listener that comes back after a recreation does not see it again.
 */
@Singleton
class LinkInbox @Inject constructor() {
    private val channel = Channel<String>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Links in arrival order, each to one collector. */
    val links: Flow<String> = channel.receiveAsFlow()

    fun offer(link: String) {
        channel.trySend(link)
    }
}

package app.berth.android.ui.a11y

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * What a screen reader is told without any control taking focus: the words of a polite live
 * region, spoken once the reader has finished what it was saying (spec A11, "announces new output
 * and bells politely"). One announcer per surface that speaks; a [LiveRegion] composed anywhere on
 * that surface carries its words.
 */
class Announcer {
    /** The words the region carries now; a change is what the framework reports and the reader speaks. */
    var message: String by mutableStateOf("")
        private set

    fun announce(text: String) {
        // The same words twice are one value to the framework, so said once; a zero-width space,
        // silent, makes the second time a change and so a second announcement.
        message = if (text == message) text + ZERO_WIDTH_SPACE else text
    }

    /** [message] as said: the silent mark that tells two announcements apart is not part of it. */
    val spoken: String get() = message.removeSuffix(ZERO_WIDTH_SPACE)

    private companion object {
        const val ZERO_WIDTH_SPACE = "\u200B"
    }
}

/**
 * The node that carries an [Announcer]'s words to the screen reader: a polite live region one dp
 * square (the framework drops a node with no size from the accessibility tree, and a region needs
 * no more than that), drawing nothing, placed by the caller where nothing else is. With nothing
 * to say it carries no description at all rather than an empty one: the region property alone
 * keeps the node in the tree so the first words arrive as a change to it, and a node with nothing
 * to say is not one a reader should be able to land on (the audit's SpeakableTextPresentCheck).
 */
@Composable
fun LiveRegion(announcer: Announcer, modifier: Modifier = Modifier) {
    val message = announcer.message
    Box(
        modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (message.isNotEmpty()) contentDescription = message
            },
    )
}

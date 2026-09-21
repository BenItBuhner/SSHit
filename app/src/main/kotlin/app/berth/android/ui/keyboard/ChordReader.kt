package app.berth.android.ui.keyboard

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F1
import android.view.KeyEvent.KEYCODE_F12
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_INSERT
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_MOVE_END
import android.view.KeyEvent.KEYCODE_MOVE_HOME
import android.view.KeyEvent.KEYCODE_NUMPAD_0
import android.view.KeyEvent.KEYCODE_NUMPAD_9
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_PAGE_DOWN
import android.view.KeyEvent.KEYCODE_PAGE_UP
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.KEYCODE_Z
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_RIGHT_ON
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import app.berth.domain.model.ChordKey
import app.berth.domain.model.LeaderKey

/**
 * The name a [ChordKey] gives the key behind [keyCode] (spec C22): a letter or digit as itself, the
 * keypad's digits as the digits, `F1` to `F12`, and the punctuation and editing keys by the names in
 * [ChordKey.NAMED]. The keypad's plus and minus, and a keyboard's own plus key, are `EQUALS` and
 * `MINUS`: a US layout types `+` as Shift and `=`, and one chord should mean the font either way
 * ([ChordReader] tries the Shift form first). Null for a modifier, a media key or anything else a
 * chord is not built on.
 */
fun chordName(keyCode: Int): String? = when (keyCode) {
    in KEYCODE_A..KEYCODE_Z -> ('A' + (keyCode - KEYCODE_A)).toString()
    in KEYCODE_0..KEYCODE_9 -> ('0' + (keyCode - KEYCODE_0)).toString()
    in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9 -> ('0' + (keyCode - KEYCODE_NUMPAD_0)).toString()
    in KEYCODE_F1..KEYCODE_F12 -> "F${keyCode - KEYCODE_F1 + 1}"
    else -> NAMED_KEYS[keyCode]
}

private val NAMED_KEYS: Map<Int, String> = mapOf(
    KEYCODE_SLASH to "SLASH", KEYCODE_EQUALS to "EQUALS", KEYCODE_PLUS to "EQUALS", KEYCODE_NUMPAD_ADD to "EQUALS",
    KEYCODE_MINUS to "MINUS", KEYCODE_NUMPAD_SUBTRACT to "MINUS", KEYCODE_GRAVE to "GRAVE", KEYCODE_BACKSLASH to "BACKSLASH",
    KEYCODE_PERIOD to "PERIOD", KEYCODE_COMMA to "COMMA", KEYCODE_SEMICOLON to "SEMICOLON", KEYCODE_APOSTROPHE to "APOSTROPHE",
    KEYCODE_LEFT_BRACKET to "LEFT_BRACKET", KEYCODE_RIGHT_BRACKET to "RIGHT_BRACKET",
    KEYCODE_SPACE to "SPACE", KEYCODE_TAB to "TAB", KEYCODE_ENTER to "ENTER", KEYCODE_NUMPAD_ENTER to "ENTER",
    KEYCODE_ESCAPE to "ESCAPE", KEYCODE_DEL to "BACKSPACE", KEYCODE_FORWARD_DEL to "DELETE", KEYCODE_INSERT to "INSERT",
    KEYCODE_MOVE_HOME to "HOME", KEYCODE_MOVE_END to "END", KEYCODE_PAGE_UP to "PAGE_UP", KEYCODE_PAGE_DOWN to "PAGE_DOWN",
    KEYCODE_DPAD_UP to "UP", KEYCODE_DPAD_DOWN to "DOWN", KEYCODE_DPAD_LEFT to "LEFT", KEYCODE_DPAD_RIGHT to "RIGHT",
)

/** The keys whose chord is tried with Shift first: `+` is Shift and `=` on the layout the defaults assume. */
private val SHIFTED_ALIASES: Set<Int> = setOf(KEYCODE_PLUS, KEYCODE_NUMPAD_ADD, KEYCODE_NUMPAD_SUBTRACT)

/** The key that is the Leader. */
val LeaderKey.composeKey: Key
    get() = when (this) {
        LeaderKey.RIGHT_ALT -> Key.AltRight
        LeaderKey.RIGHT_CTRL -> Key.CtrlRight
    }

/** The bit every key event's meta state carries while the Leader is down. */
private val LeaderKey.metaBit: Int
    get() = when (this) {
        LeaderKey.RIGHT_ALT -> META_ALT_RIGHT_ON
        LeaderKey.RIGHT_CTRL -> META_CTRL_RIGHT_ON
    }

fun LeaderKey.label(): String = when (this) {
    LeaderKey.RIGHT_ALT -> "Right Alt"
    LeaderKey.RIGHT_CTRL -> "Right Ctrl"
}

/** What [ChordReader] made of one key event. */
sealed interface ChordRead {
    /** The Leader's own press or release, or Escape letting go of a tapped Leader: the app's, and nothing to dispatch. */
    data object Consumed : ChordRead

    /** A release, a modifier pressed alone, or a key no chord is built on: not a chord, and not the app's to keep from the terminal. */
    data object Ignored : ChordRead

    /**
     * A key pressed with its modifiers: the chords it may be, in the order to try them. One for
     * most keys; a plus key is Shift+= first and = second, so the default font chord and a remap
     * to the bare key both find it.
     */
    data class Chord(val candidates: List<ChordKey>) : ChordRead {
        val chord: ChordKey get() = candidates.first()
        val leader: Boolean get() = chord.leader
    }
}

/**
 * Reads hardware key events as chords (spec C22), carrying the Leader between them. Under
 * [ChordPrefix.LEADER][app.berth.domain.model.ChordPrefix.LEADER] the Leader is a modifier when held
 * with a key and a prefix when tapped alone: the tap arms it, the next key is a Leader chord, and a
 * second tap or a plain Escape lets it go; there is no clock on it, the way a multiplexer's prefix
 * waits. While the Leader is held its own modifier bit is not the chord's: Right Alt held with F is
 * `Leader F`, and only the left Alt joined as well would make it `Leader Alt+F`. Without a Leader
 * ([leaderKey] null) the right-hand keys are the modifiers they are. One reader per dispatcher, and
 * one per sheet capturing a remap; nothing here decides what a chord does. A Leader chord that
 * opens a sheet moves the keys to the sheet's window, and the Leader's release lands there, never
 * here: so the hold is checked against the Leader's own bit in each key's meta state, and a key
 * that arrives without it is read as the key it is.
 */
class ChordReader {
    private var leaderHeld = false
    private var leaderUsed = false

    /** Whether a tapped Leader waits for its key. */
    var armed: Boolean = false
        private set

    fun read(event: KeyEvent, leaderKey: LeaderKey?): ChordRead {
        val native = event.nativeKeyEvent
        if (leaderKey == null) {
            leaderHeld = false
            armed = false
        } else if (event.key == leaderKey.composeKey) {
            when (event.type) {
                KeyEventType.KeyDown -> if (native.repeatCount == 0) {
                    leaderHeld = true
                    leaderUsed = false
                }
                KeyEventType.KeyUp -> {
                    // A tap arms, a second tap disarms; a release the reader never saw pressed (the
                    // chord switched tabs and a new reader stands) is nobody's tap.
                    if (leaderHeld && !leaderUsed) armed = !armed
                    leaderHeld = false
                }
            }
            return ChordRead.Consumed
        }
        if (event.type != KeyEventType.KeyDown) return ChordRead.Ignored
        val name = chordName(event.key.nativeKeyCode) ?: return ChordRead.Ignored
        if (leaderHeld && leaderKey != null && native.metaState and leaderKey.metaBit == 0) leaderHeld = false
        val withLeader = leaderHeld || armed
        if (leaderHeld) leaderUsed = true
        val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed
        if (armed) {
            armed = false
            if (name == "ESCAPE" && plain) return ChordRead.Consumed
        }
        var ctrl = event.isCtrlPressed
        var alt = event.isAltPressed
        if (withLeader) {
            when (leaderKey) {
                LeaderKey.RIGHT_ALT -> alt = native.metaState and META_ALT_LEFT_ON != 0
                LeaderKey.RIGHT_CTRL -> ctrl = native.metaState and META_CTRL_LEFT_ON != 0
                null -> Unit
            }
        }
        val chord = ChordKey(name, ctrl = ctrl, shift = event.isShiftPressed, alt = alt, meta = event.isMetaPressed, leader = withLeader)
        val candidates = if (event.key.nativeKeyCode in SHIFTED_ALIASES) listOf(chord.copy(shift = true), chord).distinct() else listOf(chord)
        return ChordRead.Chord(candidates)
    }
}

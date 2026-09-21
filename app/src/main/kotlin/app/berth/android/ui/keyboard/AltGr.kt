package app.berth.android.ui.keyboard

import android.view.InputDevice
import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_Z
import android.view.KeyEvent.META_ALT_RIGHT_ON
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import app.berth.android.ui.terminal.thirdLevelCharacter

/**
 * Whether a keyboard layout types with its Right Alt (spec C22, the Leader key). On a German,
 * French, Nordic, Spanish or Polish layout Right Alt is AltGr, and `@ { } [ ] \ | ~ €` are its
 * chords: the layout's key character map lists them under `ralt`, and a Leader on that key would
 * read every one of them as half a chord. [character] is the map's own lookup, the character a key
 * produces under a meta state (`KeyCharacterMap.get`): a layout with any letter, digit or
 * punctuation key that has a third level, a character under Right Alt and none under Left Alt
 * ([thirdLevelCharacter], the rule the terminal types by), types with it. A US layout has none:
 * Generic.kcm's ç on C, ß on S and its accents on E I N U are under either Alt, a Mac's Option
 * keys, and reach a terminal as Meta chords whichever Alt is held, so Right Alt costs nothing there.
 */
fun layoutTypesWithRightAlt(character: (keyCode: Int, metaState: Int) -> Int): Boolean =
    ALTGR_PROBED_KEYS.any { thirdLevelCharacter(character, it, META_ALT_RIGHT_ON) != 0 }

/** The keys an AltGr layout puts its third level on: the letters, the digits, and the punctuation a chord is named by. */
private val ALTGR_PROBED_KEYS: List<Int> = (KEYCODE_A..KEYCODE_Z) + (KEYCODE_0..KEYCODE_9) + listOf(
    KEYCODE_MINUS, KEYCODE_EQUALS, KEYCODE_LEFT_BRACKET, KEYCODE_RIGHT_BRACKET, KEYCODE_SEMICOLON, KEYCODE_APOSTROPHE,
    KEYCODE_GRAVE, KEYCODE_BACKSLASH, KEYCODE_COMMA, KEYCODE_PERIOD, KEYCODE_SLASH,
)

/**
 * Whether this keyboard's layout, as the system has it set for the device, types with Right Alt;
 * false for anything that is not a full keyboard (a game pad's buttons, the virtual keyboard).
 */
fun InputDevice.typesWithRightAlt(): Boolean =
    !isVirtual && keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC && layoutTypesWithRightAlt(keyCharacterMap::get)

/** Whether any keyboard attached now types with Right Alt. */
fun attachedKeyboardTypesWithRightAlt(): Boolean =
    InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.typesWithRightAlt() == true }

/**
 * How Settings › Hardware keyboard learns whether the attached keyboard types with Right Alt: the
 * attached keyboards' own maps, or what a test provides in their place.
 */
val LocalRightAltTypes = compositionLocalOf<() -> Boolean> { ::attachedKeyboardTypesWithRightAlt }

/**
 * Whether a keyboard attached now types with Right Alt, read again when a keyboard is attached or
 * removed: the configuration's keyboard fields change with it, the same fields
 * [rememberHardwareKeyboardAttached] reads.
 */
@Composable
fun rememberRightAltTypes(): Boolean {
    val probe = LocalRightAltTypes.current
    val configuration = LocalConfiguration.current
    return remember(probe, configuration.keyboard, configuration.hardKeyboardHidden) { probe() }
}

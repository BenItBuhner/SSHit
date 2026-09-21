package app.berth.android.ui.keyboard

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_3
import android.view.KeyEvent.KEYCODE_4
import android.view.KeyEvent.KEYCODE_5
import android.view.KeyEvent.KEYCODE_6
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_8
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_APOSTROPHE
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_I
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_M
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_U
import android.view.KeyEvent.KEYCODE_Z
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CAPS_LOCK_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_CTRL_RIGHT_ON
import android.view.KeyEvent.META_META_LEFT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_META_RIGHT_ON
import android.view.KeyEvent.META_SHIFT_ON
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/**
 * A keyboard layout as the platform's key character map answers for it (`KeyCharacterMap.get`): a
 * key's rows as its `.kcm` file writes them, matched the way the native map matches a row against
 * the modifiers held. Every modifier a row names must be held; of Ctrl, Alt and Meta nothing else
 * may be, where `alt` is either Alt key and `ralt` the right one alone; the rows are tried from the
 * file's last to its first, so `shift+capslock` wins over `shift` and `shift+ralt` over `ralt`. So a
 * German layout's `ralt: '@'` on Q answers Right Alt and not Left, Generic.kcm's `alt: 'ç'` on C
 * answers either, and Q on that US layout answers nothing to Alt at all. Robolectric's own map knows
 * Shift and nothing else, and answers the base letter to any other modifier; [ShadowKeyLayout] puts
 * one of these in its place.
 */
class KeyLayout(val name: String, private val rows: Map<Int, List<Row>>) {
    /** A row of a key: the modifiers it names, as `KeyEvent` meta bits, and its character. */
    data class Row(val metaState: Int, val character: Char)

    /** The character [keyCode] produces under [metaState], the way `KeyCharacterMap.get` answers it; 0 for none. */
    fun get(keyCode: Int, metaState: Int): Int {
        val held = KeyEvent.normalizeMetaState(metaState)
        return rows[keyCode]?.lastOrNull { matches(held, it.metaState) }?.character?.code ?: 0
    }

    override fun toString(): String = name

    companion object {
        /**
         * keyboard_layout_german.kcm, the keys a test presses: the letters, the digits and the
         * punctuation, with the third level its `ralt` rows put under Right Alt (`@` on Q, `€` on E,
         * `µ` on M, `² ³` on 2 3, `{ [ ] }` on 7 8 9 0, `\` on ß and `ẞ` with Shift, `~` on the `+`
         * key, `|` on the `<` key beside the left Shift). ß is the file's `SLASH`, the `<` key its `PLUS`.
         */
        val GERMAN: KeyLayout = KeyLayout(
            "German",
            letters() + digits('!', '"', '\u00A7', '$', '%', '&', '/', '(', ')', '=') + listOf(
                key(KEYCODE_Q, 'q', 'Q', ralt = '@'),
                key(KEYCODE_E, 'e', 'E', ralt = '\u20AC'),
                key(KEYCODE_M, 'm', 'M', ralt = '\u00B5'),
                key(KEYCODE_2, '2', '"', ralt = '\u00B2'),
                key(KEYCODE_3, '3', '\u00A7', ralt = '\u00B3'),
                key(KEYCODE_7, '7', '/', ralt = '{'),
                key(KEYCODE_8, '8', '(', ralt = '['),
                key(KEYCODE_9, '9', ')', ralt = ']'),
                key(KEYCODE_0, '0', '=', ralt = '}'),
                key(KEYCODE_SLASH, '\u00DF', '?', ralt = '\\', shiftRalt = '\u1E9E'),
                key(KEYCODE_LEFT_BRACKET, '\u00FC', '\u00DC'),
                key(KEYCODE_RIGHT_BRACKET, '+', '*', ralt = '~'),
                key(KEYCODE_SEMICOLON, '\u00F6', '\u00D6'),
                key(KEYCODE_APOSTROPHE, '\u00E4', '\u00C4'),
                key(KEYCODE_BACKSLASH, '#', '\''),
                key(KEYCODE_PLUS, '<', '>', ralt = '|'),
                key(KEYCODE_COMMA, ',', ';'),
                key(KEYCODE_PERIOD, '.', ':'),
                key(KEYCODE_MINUS, '-', '_'),
                key(KEYCODE_SPACE, ' '),
            ),
        )

        /**
         * Generic.kcm, the US layout Android gives a keyboard with no other: the letters, the digits
         * and the punctuation, and its `alt` rows, which either Alt reaches, as a Mac's Option does:
         * `ç` on C (`Ç` with Shift), `ß` on S, and the dead accents on E I N U and the grave key.
         * Nothing is under Right Alt alone.
         */
        val US: KeyLayout = KeyLayout(
            "US",
            letters() + digits('!', '@', '#', '$', '%', '^', '&', '*', '(', ')') + listOf(
                key(KEYCODE_C, 'c', 'C', alt = '\u00E7', shiftAlt = '\u00C7'),
                key(KEYCODE_S, 's', 'S', alt = '\u00DF'),
                key(KEYCODE_E, 'e', 'E', alt = '\u0301'),
                key(KEYCODE_I, 'i', 'I', alt = '\u0302'),
                key(KEYCODE_N, 'n', 'N', alt = '\u0303'),
                key(KEYCODE_U, 'u', 'U', alt = '\u0308'),
                key(KEYCODE_GRAVE, '`', '~', alt = '\u0300', shiftAlt = '\u0303'),
                key(KEYCODE_MINUS, '-', '_'),
                key(KEYCODE_EQUALS, '=', '+'),
                key(KEYCODE_LEFT_BRACKET, '[', '{'),
                key(KEYCODE_RIGHT_BRACKET, ']', '}'),
                key(KEYCODE_BACKSLASH, '\\', '|'),
                key(KEYCODE_SEMICOLON, ';', ':'),
                key(KEYCODE_APOSTROPHE, '\'', '"'),
                key(KEYCODE_COMMA, ',', '<'),
                key(KEYCODE_PERIOD, '.', '>'),
                key(KEYCODE_SLASH, '/', '?'),
                key(KEYCODE_SPACE, ' '),
            ),
        )

        /** The letters as every layout writes them: the lowercase, `shift, capslock` the uppercase, `shift+capslock` the lowercase again. */
        private fun letters(): Map<Int, List<Row>> = ('a'..'z').associate { c -> key(KEYCODE_A + (c - 'a'), c, c.uppercaseChar()) }

        /** The digits 1 to 9 then 0 with their Shift characters, `shift, capslock` rows the way both files write them. */
        private fun digits(vararg shift: Char): Map<Int, List<Row>> =
            (listOf(KEYCODE_1, KEYCODE_2, KEYCODE_3, KEYCODE_4, KEYCODE_5, KEYCODE_6, KEYCODE_7, KEYCODE_8, KEYCODE_9, KEYCODE_0) zip shift.toList())
                .associate { (code, s) -> key(code, ('0' + (code - KEYCODE_0)), s) }

        /**
         * A key's rows in a `.kcm` file's order: `base`; `shift, capslock` and `shift+capslock` for a
         * key with a Shift character; `alt`, then `shift+alt, capslock+alt` and `shift+capslock+alt`
         * with it, as Generic.kcm writes its C; `ralt`, then `shift+ralt`, as the German file writes its ß.
         */
        private fun key(code: Int, base: Char, shift: Char? = null, alt: Char? = null, shiftAlt: Char? = null, ralt: Char? = null, shiftRalt: Char? = null): Pair<Int, List<Row>> {
            val rows = ArrayList<Row>()
            rows += Row(0, base)
            if (shift != null) {
                rows += Row(META_SHIFT_ON, shift)
                rows += Row(META_CAPS_LOCK_ON, shift)
                rows += Row(META_SHIFT_ON or META_CAPS_LOCK_ON, base)
            }
            if (alt != null) {
                rows += Row(META_ALT_ON, alt)
                if (shiftAlt != null) {
                    rows += Row(META_SHIFT_ON or META_ALT_ON, shiftAlt)
                    rows += Row(META_CAPS_LOCK_ON or META_ALT_ON, shiftAlt)
                    rows += Row(META_SHIFT_ON or META_CAPS_LOCK_ON or META_ALT_ON, alt)
                }
            }
            if (ralt != null) {
                rows += Row(META_ALT_RIGHT_ON, ralt)
                if (shiftRalt != null) rows += Row(META_SHIFT_ON or META_ALT_RIGHT_ON, shiftRalt)
            }
            return code to rows
        }

        private const val EXACT = META_CTRL_ON or META_CTRL_LEFT_ON or META_CTRL_RIGHT_ON or
            META_ALT_ON or META_ALT_LEFT_ON or META_ALT_RIGHT_ON or
            META_META_ON or META_META_LEFT_ON or META_META_RIGHT_ON

        /**
         * The native map's `matchesMetaState`: a row's modifiers must all be held, and of Ctrl, Alt and
         * Meta nothing beyond them, where a row naming a pair (`alt`) takes either side and a row
         * naming a side (`ralt`) takes the pair's own bit with it.
         */
        private fun matches(held: Int, row: Int): Boolean {
            if (held and row != row) return false
            var unmatched = held and row.inv() and EXACT
            unmatched = pair(unmatched, row, META_CTRL_ON, META_CTRL_LEFT_ON or META_CTRL_RIGHT_ON)
            unmatched = pair(unmatched, row, META_ALT_ON, META_ALT_LEFT_ON or META_ALT_RIGHT_ON)
            unmatched = pair(unmatched, row, META_META_ON, META_META_LEFT_ON or META_META_RIGHT_ON)
            return unmatched == 0
        }

        private fun pair(unmatched: Int, row: Int, pair: Int, sides: Int): Int = when {
            row and pair != 0 -> unmatched and sides.inv()
            row and sides != 0 -> unmatched and pair.inv()
            else -> unmatched
        }
    }
}

/**
 * The platform's `KeyCharacterMap` answering from a [KeyLayout] in place of Robolectric's own, for a
 * test annotated `@Config(shadows = [ShadowKeyLayout::class])`: `KeyEvent.getUnicodeChar`, Compose's
 * `utf16CodePoint` and `InputDevice.getKeyCharacterMap().get` then answer as the device does under
 * [layout], `@` to Right Alt+Q on the German one. A map loads the way Robolectric's does, as a bare
 * object, since there is no device to load it for; the Java half of the class (`get`, the dead keys'
 * accents) runs as written.
 */
@Implements(KeyCharacterMap::class)
class ShadowKeyLayout {
    companion object {
        /** The layout every map answers from: the US one unless a test sets another, and a test that does sets it back. */
        @JvmStatic
        var layout: KeyLayout = KeyLayout.US

        @JvmStatic
        @Implementation
        fun load(deviceId: Int): KeyCharacterMap = ReflectionHelpers.callConstructor(KeyCharacterMap::class.java)

        @JvmStatic
        @Implementation
        fun nativeGetCharacter(ptr: Long, keyCode: Int, metaState: Int): Char = layout.get(keyCode, metaState).toChar()
    }
}

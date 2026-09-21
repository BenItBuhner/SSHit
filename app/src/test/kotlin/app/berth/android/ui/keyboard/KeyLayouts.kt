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
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_COMMA
import android.view.KeyEvent.KEYCODE_D
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_G
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_H
import android.view.KeyEvent.KEYCODE_I
import android.view.KeyEvent.KEYCODE_K
import android.view.KeyEvent.KEYCODE_LEFT_BRACKET
import android.view.KeyEvent.KEYCODE_M
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_N
import android.view.KeyEvent.KEYCODE_O
import android.view.KeyEvent.KEYCODE_PERIOD
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_S
import android.view.KeyEvent.KEYCODE_SEMICOLON
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_T
import android.view.KeyEvent.KEYCODE_U
import android.view.KeyEvent.KEYCODE_V
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
 * German layout's `ralt: '@'` on Q answers Right Alt and not Left, a Nordic one's on 2 does, Generic.kcm's
 * `alt: 'ç'` on C answers either, and Q on that US layout answers nothing to Alt at all. Robolectric's
 * own map knows Shift and nothing else, and answers the base letter to any other modifier;
 * [ShadowKeyLayout] puts one of these in its place.
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
         * key, `|` on the `<` key beside the left Shift, and the dead keys: `´` with a dead grave under
         * Shift on the `=` key, `^` and `°` on the grave key). ß is the file's `SLASH`, the `<` key its
         * `PLUS`. Caps Lock is a Shift Lock on this layout: the file gives the `shift, capslock` pair to
         * the digits and the punctuation too, all but the `-` and `<` keys and the two dead ones.
         */
        val GERMAN: KeyLayout = KeyLayout(
            "German",
            letters() + digits('!', '"', '\u00A7', '$', '%', '&', '/', '(', ')', '=', capsLock = true) + listOf(
                key(KEYCODE_Q, 'q', 'Q', ralt = '@', capsLock = true),
                key(KEYCODE_E, 'e', 'E', ralt = '\u20AC', capsLock = true),
                key(KEYCODE_M, 'm', 'M', ralt = '\u00B5', capsLock = true),
                key(KEYCODE_2, '2', '"', ralt = '\u00B2', capsLock = true),
                key(KEYCODE_3, '3', '\u00A7', ralt = '\u00B3', capsLock = true),
                key(KEYCODE_7, '7', '/', ralt = '{', capsLock = true),
                key(KEYCODE_8, '8', '(', ralt = '[', capsLock = true),
                key(KEYCODE_9, '9', ')', ralt = ']', capsLock = true),
                key(KEYCODE_0, '0', '=', ralt = '}', capsLock = true),
                key(KEYCODE_SLASH, '\u00DF', '?', ralt = '\\', shiftRalt = '\u1E9E', capsLock = true),
                key(KEYCODE_LEFT_BRACKET, '\u00FC', '\u00DC', capsLock = true),
                key(KEYCODE_RIGHT_BRACKET, '+', '*', ralt = '~', capsLock = true),
                key(KEYCODE_SEMICOLON, '\u00F6', '\u00D6', capsLock = true),
                key(KEYCODE_APOSTROPHE, '\u00E4', '\u00C4', capsLock = true),
                key(KEYCODE_BACKSLASH, '#', '\'', capsLock = true),
                key(KEYCODE_PLUS, '<', '>', ralt = '|'),
                key(KEYCODE_COMMA, ',', ';', capsLock = true),
                key(KEYCODE_PERIOD, '.', ':', capsLock = true),
                key(KEYCODE_MINUS, '-', '_'),
                key(KEYCODE_EQUALS, '\u0301', '\u0300'),
                key(KEYCODE_GRAVE, '\u0302', '\u00B0'),
                key(KEYCODE_SPACE, ' '),
            ),
        )

        /**
         * keyboard_layout_swedish.kcm, which the Finnish file matches row for row, and whose third level
         * the Norwegian and Danish files share on the digits, E and M (they move `\` and `|` and the
         * letters beside Enter): the letters, the digits and the punctuation, with `@ £ $ €` on 2 3 4 5,
         * `{ [ ] }` on 7 8 9 0, `\` on the `+` key (the file's `MINUS`), `|` on the `<` key beside the
         * left Shift (its `PLUS`), `€` on E, `µ` on M, ø and æ on the ö and ä keys, the Sami letters on
         * Q T I O A S D F G H K Z C V B N (â on Q, á on A, š on S), and a dead tilde on the `¨` key
         * (its `RIGHT_BRACKET`), whose base and Shift are dead too. So `@` is on 2 here, where the
         * German layout has it on Q, and Right Alt+Q is â. The `shift, capslock` pair is the letters'
         * and å ö ä's; the digits and the punctuation have `shift` alone.
         */
        val NORDIC: KeyLayout = KeyLayout(
            "Nordic",
            letters() + digits('!', '"', '#', '\u00A4', '%', '&', '/', '(', ')', '=') + listOf(
                key(KEYCODE_2, '2', '"', ralt = '@'),
                key(KEYCODE_3, '3', '#', ralt = '\u00A3'),
                key(KEYCODE_4, '4', '\u00A4', ralt = '$'),
                key(KEYCODE_5, '5', '%', ralt = '\u20AC'),
                key(KEYCODE_7, '7', '/', ralt = '{'),
                key(KEYCODE_8, '8', '(', ralt = '['),
                key(KEYCODE_9, '9', ')', ralt = ']'),
                key(KEYCODE_0, '0', '=', ralt = '}'),
                key(KEYCODE_MINUS, '+', '?', ralt = '\\'),
                key(KEYCODE_EQUALS, '\u0301', '\u0300'),
                letter(KEYCODE_Q, 'q', 'Q', '\u00E2', '\u00C2'),
                key(KEYCODE_E, 'e', 'E', ralt = '\u20AC', capsLock = true),
                letter(KEYCODE_T, 't', 'T', '\u0167', '\u0166'),
                letter(KEYCODE_I, 'i', 'I', '\u00EF', '\u00CF'),
                letter(KEYCODE_O, 'o', 'O', '\u00F5', '\u00D5'),
                key(KEYCODE_LEFT_BRACKET, '\u00E5', '\u00C5', capsLock = true),
                key(KEYCODE_RIGHT_BRACKET, '\u0308', '\u0302', ralt = '\u0303'),
                letter(KEYCODE_A, 'a', 'A', '\u00E1', '\u00C1'),
                letter(KEYCODE_S, 's', 'S', '\u0161', '\u0160'),
                letter(KEYCODE_D, 'd', 'D', '\u0111', '\u0110'),
                letter(KEYCODE_F, 'f', 'F', '\u01E5', '\u01E4'),
                letter(KEYCODE_G, 'g', 'G', '\u01E7', '\u01E6'),
                letter(KEYCODE_H, 'h', 'H', '\u021F', '\u021E'),
                letter(KEYCODE_K, 'k', 'K', '\u01E9', '\u01E8'),
                letter(KEYCODE_SEMICOLON, '\u00F6', '\u00D6', '\u00F8', '\u00D8'),
                letter(KEYCODE_APOSTROPHE, '\u00E4', '\u00C4', '\u00E6', '\u00C6'),
                key(KEYCODE_BACKSLASH, '\'', '*'),
                key(KEYCODE_PLUS, '<', '>', ralt = '|'),
                letter(KEYCODE_Z, 'z', 'Z', '\u017E', '\u017D'),
                letter(KEYCODE_C, 'c', 'C', '\u010D', '\u010C'),
                letter(KEYCODE_V, 'v', 'V', '\u01EF', '\u01EE'),
                letter(KEYCODE_B, 'b', 'B', '\u0292', '\u01B7'),
                letter(KEYCODE_N, 'n', 'N', '\u014B', '\u014A'),
                key(KEYCODE_M, 'm', 'M', ralt = '\u00B5', capsLock = true),
                key(KEYCODE_COMMA, ',', ';'),
                key(KEYCODE_PERIOD, '.', ':'),
                key(KEYCODE_SLASH, '-', '_'),
                key(KEYCODE_GRAVE, '\u00A7', '\u00BD'),
                key(KEYCODE_SPACE, ' '),
            ),
        )

        /**
         * Generic.kcm, the US layout Android gives a keyboard with no other: the letters, the digits
         * and the punctuation, and its `alt` rows, which either Alt reaches, as a Mac's Option does:
         * `ç` on C (`Ç` with Shift, with the Caps Lock rows a letter has), `ß` on S, and the dead
         * accents on E I N U and the grave key (`alt+shift` alone there, no Caps Lock row). Nothing
         * is under Right Alt alone; the `shift, capslock` pair is the letters' only.
         */
        val US: KeyLayout = KeyLayout(
            "US",
            letters() + digits('!', '@', '#', '$', '%', '^', '&', '*', '(', ')') + listOf(
                key(KEYCODE_6, '6', '^', shiftAlt = '\u0302'),
                key(KEYCODE_C, 'c', 'C', alt = '\u00E7', shiftAlt = '\u00C7', capsLock = true),
                key(KEYCODE_S, 's', 'S', alt = '\u00DF', capsLock = true),
                key(KEYCODE_E, 'e', 'E', alt = '\u0301', capsLock = true),
                key(KEYCODE_I, 'i', 'I', alt = '\u0302', capsLock = true),
                key(KEYCODE_N, 'n', 'N', alt = '\u0303', capsLock = true),
                key(KEYCODE_U, 'u', 'U', alt = '\u0308', capsLock = true),
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
        private fun letters(): Map<Int, List<Row>> = ('a'..'z').associate { c -> key(KEYCODE_A + (c - 'a'), c, c.uppercaseChar(), capsLock = true) }

        /**
         * The digits 1 to 9 then 0 with their Shift characters: the `shift, capslock` pair where the
         * file gives its digits one ([capsLock], the German file, where Caps Lock is a Shift Lock),
         * `shift` alone where it does not (the Swedish file, Generic.kcm).
         */
        private fun digits(vararg shift: Char, capsLock: Boolean = false): Map<Int, List<Row>> =
            (listOf(KEYCODE_1, KEYCODE_2, KEYCODE_3, KEYCODE_4, KEYCODE_5, KEYCODE_6, KEYCODE_7, KEYCODE_8, KEYCODE_9, KEYCODE_0) zip shift.toList())
                .associate { (code, s) -> key(code, ('0' + (code - KEYCODE_0)), s, capsLock = capsLock) }

        /**
         * A key's rows in a `.kcm` file's order: `base`; then for a key with a Shift character either
         * `shift` alone or, where the file locks the key with Caps Lock ([capsLock]: every letter, and
         * on the German layout the digits and most punctuation), `shift, capslock` and `shift+capslock`;
         * `alt`, then `shift+alt` with it, as Generic.kcm writes its grave key (or `alt+shift` alone, as
         * it writes its 6), or `shift+alt, capslock+alt` and `shift+capslock+alt` where the key is Caps
         * Lock's, as it writes its C; `ralt`, then `shift+ralt`, as the German file writes its ß.
         */
        private fun key(code: Int, base: Char, shift: Char? = null, alt: Char? = null, shiftAlt: Char? = null, ralt: Char? = null, shiftRalt: Char? = null, capsLock: Boolean = false): Pair<Int, List<Row>> {
            val rows = ArrayList<Row>()
            rows += Row(0, base)
            if (shift != null) {
                rows += Row(META_SHIFT_ON, shift)
                if (capsLock) {
                    rows += Row(META_CAPS_LOCK_ON, shift)
                    rows += Row(META_SHIFT_ON or META_CAPS_LOCK_ON, base)
                }
            }
            if (alt != null) rows += Row(META_ALT_ON, alt)
            if (shiftAlt != null) {
                rows += Row(META_SHIFT_ON or META_ALT_ON, shiftAlt)
                if (capsLock && alt != null) {
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

        /**
         * A letter with a third level, as the Nordic file writes one: the letter's rows, then `ralt`,
         * `shift+ralt, capslock+ralt` its capital and `shift+capslock+ralt` the small one again.
         */
        private fun letter(code: Int, base: Char, upper: Char, ralt: Char, raltUpper: Char): Pair<Int, List<Row>> {
            val (c, rows) = key(code, base, upper, ralt = ralt, shiftRalt = raltUpper, capsLock = true)
            return c to rows + Row(META_CAPS_LOCK_ON or META_ALT_RIGHT_ON, raltUpper) + Row(META_SHIFT_ON or META_CAPS_LOCK_ON or META_ALT_RIGHT_ON, ralt)
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

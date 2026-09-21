package app.berth.android.ui.keyboard

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_7
import android.view.KeyEvent.KEYCODE_8
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_E
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_Z
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_SHIFT_ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowInputDevice

/**
 * Whether a keyboard's layout types with Right Alt (spec C22, the Leader key), read from the
 * layout's own map the way `KeyCharacterMap.get` answers it: a US layout has nothing under Right
 * Alt, an AltGr layout its third level. The maps here are layouts' `ralt` rows as Android's `.kcm`
 * files write them, not Robolectric's own map, which knows Shift and nothing else and answers the
 * base letter to any other modifier; and with no keyboard attached nothing types with Right Alt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AltGrTest {
    /** A layout as `KeyCharacterMap.get` answers for it: the letters and digits, their Shift, and its `ralt` row, or nothing. */
    private fun layout(altGr: Map<Int, Char>): (Int, Int) -> Int = { keyCode, metaState ->
        when {
            metaState and META_ALT_RIGHT_ON != 0 -> altGr[keyCode]?.code ?: 0
            metaState and META_SHIFT_ON != 0 -> base(keyCode)?.uppercaseChar()?.code ?: 0
            else -> base(keyCode)?.code ?: 0
        }
    }

    private fun base(keyCode: Int): Char? = when (keyCode) {
        in KEYCODE_A..KEYCODE_Z -> 'a' + (keyCode - KEYCODE_A)
        in KEYCODE_0..KEYCODE_9 -> '0' + (keyCode - KEYCODE_0)
        KEYCODE_SPACE -> ' '
        else -> null
    }

    private val us = layout(emptyMap())

    /** German: `@` on Q, `{ [ ] }` on 7 8 9 0, `\` on ß (the US minus key), `~` on + (the US right bracket), `€` on E. */
    private val german = layout(
        mapOf(KEYCODE_Q to '@', KEYCODE_7 to '{', KEYCODE_8 to '[', KEYCODE_9 to ']', KEYCODE_0 to '}', KEYCODE_MINUS to '\\', KEYCODE_RIGHT_BRACKET to '~', KEYCODE_E to '\u20AC'),
    )

    @Test
    fun `a US layout has nothing under Right Alt, so it does not type with it`() {
        assertEquals('q'.code, us(KEYCODE_Q, 0))
        assertEquals('Q'.code, us(KEYCODE_Q, META_SHIFT_ON))
        assertEquals(0, us(KEYCODE_Q, META_ALT_RIGHT_ON))
        assertFalse(layoutTypesWithRightAlt(us))
    }

    @Test
    fun `a German layout puts its third level under Right Alt and types with it, and one such key is enough`() {
        assertEquals('@'.code, german(KEYCODE_Q, META_ALT_RIGHT_ON))
        assertTrue(layoutTypesWithRightAlt(german))
        // A map with the one Nordic `@` on 2 and nothing else under Right Alt types with it too.
        assertTrue(layoutTypesWithRightAlt(layout(mapOf(KEYCODE_2 to '@'))))
    }

    @Test
    fun `the probe asks the map for Right Alt alone, after the letters, the digits and the punctuation a chord is named by`() {
        // `KeyCharacterMap.get` matches a `ralt` row when Right Alt is the modifier asked for (it sets
        // the plain Alt bit itself); a row asked for with plain Alt would match nothing.
        val asked = ArrayList<Pair<Int, Int>>()
        assertFalse(layoutTypesWithRightAlt { keyCode, metaState -> asked += keyCode to metaState; 0 })
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { it.second == META_ALT_RIGHT_ON })
        val keys = asked.map { it.first }.toSet()
        for (key in (KEYCODE_A..KEYCODE_Z) + (KEYCODE_0..KEYCODE_9) + listOf(KEYCODE_MINUS, KEYCODE_RIGHT_BRACKET, KEYCODE_BACKSLASH, KEYCODE_GRAVE, KEYCODE_SLASH)) {
            assertTrue("key $key is probed", key in keys)
        }
        assertFalse("the space bar, which no chord is named by, is not", KEYCODE_SPACE in keys)
    }

    @Test
    fun `with no keyboard attached nothing types with Right Alt, and a device that is not a full keyboard never does`() {
        assertFalse(attachedKeyboardTypesWithRightAlt())
        // A game pad's buttons: no alphabetic keyboard, so its map is never asked.
        assertFalse(ShadowInputDevice.makeInputDeviceNamed("pad").typesWithRightAlt())
    }
}

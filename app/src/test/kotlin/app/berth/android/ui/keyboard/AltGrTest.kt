package app.berth.android.ui.keyboard

import android.view.KeyEvent.KEYCODE_0
import android.view.KeyEvent.KEYCODE_2
import android.view.KeyEvent.KEYCODE_9
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_BACKSLASH
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_GRAVE
import android.view.KeyEvent.KEYCODE_MINUS
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_Q
import android.view.KeyEvent.KEYCODE_RIGHT_BRACKET
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_Z
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CAPS_LOCK_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_ON
import app.berth.android.ui.terminal.thirdLevelCharacter
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
 * Alt alone, a German or a Nordic layout its third level. The maps are [KeyLayout]s, Android's own
 * `.kcm` rows matched as the platform matches them, not Robolectric's own map, which knows Shift and
 * nothing else and answers the base letter to any other modifier; and with no keyboard attached
 * nothing types with Right Alt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AltGrTest {
    private val us = KeyLayout.US
    private val german = KeyLayout.GERMAN
    private val nordic = KeyLayout.NORDIC

    @Test
    fun `the layouts answer as the platform's map does, a row's modifiers held, Ctrl Alt and Meta exact, the file's last row first`() {
        // ß: base, Shift, Shift with Caps Lock back to the base, the third level, and its own Shift.
        assertEquals('\u00DF'.code, german.get(KEYCODE_SLASH, 0))
        assertEquals('?'.code, german.get(KEYCODE_SLASH, META_SHIFT_ON))
        assertEquals('\u00DF'.code, german.get(KEYCODE_SLASH, META_SHIFT_ON or META_CAPS_LOCK_ON))
        assertEquals('\\'.code, german.get(KEYCODE_SLASH, META_ALT_RIGHT_ON))
        assertEquals('\u1E9E'.code, german.get(KEYCODE_SLASH, META_SHIFT_ON or META_ALT_RIGHT_ON))
        // A `ralt` row is Right Alt's alone: Left Alt, or the plain Alt bit, answers nothing; and a Ctrl held besides is unmatched.
        assertEquals(0, german.get(KEYCODE_SLASH, META_ALT_LEFT_ON))
        assertEquals(0, german.get(KEYCODE_SLASH, META_ALT_ON))
        assertEquals(0, german.get(KEYCODE_SLASH, META_CTRL_ON or META_CTRL_LEFT_ON or META_ALT_RIGHT_ON))
        // Shift is not exact: Shift with Right Alt on a key with no `shift+ralt` row is the `ralt` row still, as on the device.
        assertEquals('@'.code, german.get(KEYCODE_Q, META_SHIFT_ON or META_ALT_RIGHT_ON))
        // Generic.kcm's `alt` row on C is either Alt's, `shift+alt` its Shift; Q has no Alt row at all.
        assertEquals('\u00E7'.code, us.get(KEYCODE_C, META_ALT_LEFT_ON))
        assertEquals('\u00E7'.code, us.get(KEYCODE_C, META_ALT_RIGHT_ON))
        assertEquals('\u00C7'.code, us.get(KEYCODE_C, META_SHIFT_ON or META_ALT_RIGHT_ON))
        assertEquals(0, us.get(KEYCODE_Q, META_ALT_RIGHT_ON))
    }

    @Test
    fun `a US layout has nothing under Right Alt alone, so it does not type with it, its ç and ß under either Alt included`() {
        assertEquals('q'.code, us.get(KEYCODE_Q, 0))
        assertEquals('Q'.code, us.get(KEYCODE_Q, META_SHIFT_ON))
        assertEquals(0, us.get(KEYCODE_Q, META_ALT_RIGHT_ON))
        // ç on C is under either Alt, a Mac's Option and a terminal's Meta+C: not a third level, so no cost to a Leader on Right Alt.
        assertEquals('\u00E7'.code, us.get(KEYCODE_C, META_ALT_RIGHT_ON))
        assertEquals(0, thirdLevelCharacter(us::get, KEYCODE_C, META_ALT_RIGHT_ON))
        assertFalse(layoutTypesWithRightAlt(us::get))
    }

    @Test
    fun `a German layout puts its third level under Right Alt alone and types with it, and one such key is enough`() {
        assertEquals('@'.code, german.get(KEYCODE_Q, META_ALT_RIGHT_ON))
        assertEquals(0, german.get(KEYCODE_Q, META_ALT_LEFT_ON))
        assertEquals('@'.code, thirdLevelCharacter(german::get, KEYCODE_Q, META_ALT_RIGHT_ON))
        assertTrue(layoutTypesWithRightAlt(german::get))
        // A map with that one `@` and nothing else under Right Alt types with it too.
        val oneKey = KeyLayout("one key", mapOf(KEYCODE_Q to listOf(KeyLayout.Row(0, 'q'), KeyLayout.Row(META_ALT_RIGHT_ON, '@'))))
        assertTrue(layoutTypesWithRightAlt(oneKey::get))
    }

    @Test
    fun `a Nordic layout, the Swedish and Finnish file, has its @ on 2 and a letter's third level on Q, under Right Alt alone, and types with it`() {
        assertEquals('@'.code, nordic.get(KEYCODE_2, META_ALT_RIGHT_ON))
        assertEquals('"'.code, nordic.get(KEYCODE_2, META_SHIFT_ON))
        assertEquals(0, nordic.get(KEYCODE_2, META_ALT_LEFT_ON))
        // Q is â here, Â with Shift and with Caps Lock, â again with both, as the file writes a letter's third level.
        assertEquals('\u00E2'.code, nordic.get(KEYCODE_Q, META_ALT_RIGHT_ON))
        assertEquals('\u00C2'.code, nordic.get(KEYCODE_Q, META_SHIFT_ON or META_ALT_RIGHT_ON))
        assertEquals('\u00C2'.code, nordic.get(KEYCODE_Q, META_CAPS_LOCK_ON or META_ALT_RIGHT_ON))
        assertEquals('\u00E2'.code, nordic.get(KEYCODE_Q, META_SHIFT_ON or META_CAPS_LOCK_ON or META_ALT_RIGHT_ON))
        assertEquals(0, nordic.get(KEYCODE_Q, META_ALT_LEFT_ON))
        // `|` on the `<` key and `\` on the `+` key, the two a shell wants most.
        assertEquals('|'.code, nordic.get(KEYCODE_PLUS, META_ALT_RIGHT_ON))
        assertEquals('\\'.code, nordic.get(KEYCODE_MINUS, META_ALT_RIGHT_ON))
        assertEquals('@'.code, thirdLevelCharacter(nordic::get, KEYCODE_2, META_ALT_RIGHT_ON))
        assertEquals('\u00E2'.code, thirdLevelCharacter(nordic::get, KEYCODE_Q, META_ALT_RIGHT_ON))
        assertTrue(layoutTypesWithRightAlt(nordic::get))
    }

    @Test
    fun `the probe asks the map for Right Alt alone and then Left Alt alone, for the letters, the digits and the punctuation a chord is named by`() {
        // `KeyCharacterMap.get` matches a `ralt` row when Right Alt is the modifier asked for (it sets
        // the plain Alt bit itself); Left Alt tells a third level from a row either Alt reaches, so a
        // map that answers every key under either Alt types with neither.
        val asked = ArrayList<Pair<Int, Int>>()
        assertFalse(layoutTypesWithRightAlt { keyCode, metaState -> asked += keyCode to metaState; 'x'.code })
        assertTrue(asked.isNotEmpty())
        assertTrue(asked.all { it.second == META_ALT_RIGHT_ON || it.second == META_ALT_LEFT_ON })
        val keys = asked.filter { it.second == META_ALT_RIGHT_ON }.map { it.first }.toSet()
        for (key in (KEYCODE_A..KEYCODE_Z) + (KEYCODE_0..KEYCODE_9) + listOf(KEYCODE_MINUS, KEYCODE_RIGHT_BRACKET, KEYCODE_BACKSLASH, KEYCODE_GRAVE, KEYCODE_SLASH)) {
            assertTrue("key $key is probed", key in keys)
        }
        assertFalse("the space bar, which no chord is named by, is not", KEYCODE_SPACE in keys)
        // A map with nothing under Right Alt is never asked about Left Alt.
        asked.clear()
        assertFalse(layoutTypesWithRightAlt { keyCode, metaState -> asked += keyCode to metaState; 0 })
        assertTrue(asked.all { it.second == META_ALT_RIGHT_ON })
    }

    @Test
    fun `with no keyboard attached nothing types with Right Alt, and a device that is not a full keyboard never does`() {
        assertFalse(attachedKeyboardTypesWithRightAlt())
        // A game pad's buttons: no alphabetic keyboard, so its map is never asked.
        assertFalse(ShadowInputDevice.makeInputDeviceNamed("pad").typesWithRightAlt())
    }
}

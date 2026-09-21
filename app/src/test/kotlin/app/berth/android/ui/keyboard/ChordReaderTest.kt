package app.berth.android.ui.keyboard

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_ALT_LEFT
import android.view.KeyEvent.KEYCODE_ALT_RIGHT
import android.view.KeyEvent.KEYCODE_CTRL_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_EQUALS
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_F5
import android.view.KeyEvent.KEYCODE_NUMPAD_7
import android.view.KeyEvent.KEYCODE_NUMPAD_ADD
import android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT
import android.view.KeyEvent.KEYCODE_PLUS
import android.view.KeyEvent.KEYCODE_SHIFT_LEFT
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_ALT_RIGHT_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_CTRL_RIGHT_ON
import android.view.KeyEvent.META_META_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import app.berth.domain.model.ChordKey
import app.berth.domain.model.LeaderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Key events read as chords (spec C22): the names, the modifiers, the keypad's aliases, and the Leader held or tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChordReaderTest {
    private val reader = ChordReader()

    private fun event(code: Int, meta: Int = 0, action: Int = ACTION_DOWN, repeat: Int = 0) = KeyEvent(android.view.KeyEvent(0L, 0L, action, code, repeat, meta))

    private fun chord(code: Int, meta: Int = 0, leader: LeaderKey? = null): ChordKey =
        (reader.read(event(code, meta), leader) as ChordRead.Chord).chord

    @Test
    fun `a key with its modifiers is one chord, and a release, a modifier alone or a key no chord is built on is ignored`() {
        assertEquals(ChordKey("F", ctrl = true, shift = true), chord(KEYCODE_F, META_CTRL_ON or META_SHIFT_ON))
        assertEquals(ChordKey("F"), chord(KEYCODE_F))
        assertEquals(ChordKey("SLASH", meta = true), chord(KEYCODE_SLASH, META_META_ON))
        assertEquals(ChordKey("UP", alt = true, shift = true), chord(KEYCODE_DPAD_UP, META_ALT_ON or META_SHIFT_ON))
        assertEquals(ChordKey("F5"), chord(KEYCODE_F5))
        assertEquals(ChordRead.Ignored, reader.read(event(KEYCODE_F, META_CTRL_ON, ACTION_UP), null))
        assertEquals(ChordRead.Ignored, reader.read(event(KEYCODE_SHIFT_LEFT, META_SHIFT_ON), null))
        assertEquals(ChordRead.Ignored, reader.read(event(KEYCODE_ALT_LEFT, META_ALT_ON), null))
        assertEquals(ChordRead.Ignored, reader.read(event(KEYCODE_VOLUME_UP), null))
    }

    @Test
    fun `the keypad's digits are the digits, and its plus and minus are the = and - keys, Shift first for the plus`() {
        assertEquals(ChordKey("7", ctrl = true), chord(KEYCODE_NUMPAD_7, META_CTRL_ON))
        val plus = reader.read(event(KEYCODE_NUMPAD_ADD, META_CTRL_ON), null) as ChordRead.Chord
        assertEquals(listOf(ChordKey("EQUALS", ctrl = true, shift = true), ChordKey("EQUALS", ctrl = true)), plus.candidates)
        val keyboardPlus = reader.read(event(KEYCODE_PLUS, META_CTRL_ON), null) as ChordRead.Chord
        assertEquals(plus.candidates, keyboardPlus.candidates)
        // Already Shifted, the two forms are one and the list holds it once.
        val shifted = reader.read(event(KEYCODE_NUMPAD_ADD, META_CTRL_ON or META_SHIFT_ON), null) as ChordRead.Chord
        assertEquals(listOf(ChordKey("EQUALS", ctrl = true, shift = true)), shifted.candidates)
        val minus = reader.read(event(KEYCODE_NUMPAD_SUBTRACT, META_CTRL_ON), null) as ChordRead.Chord
        assertEquals(listOf(ChordKey("MINUS", ctrl = true, shift = true), ChordKey("MINUS", ctrl = true)), minus.candidates)
        // The = key itself is one chord: its Shift form is the user's to press.
        assertEquals(listOf(ChordKey("EQUALS", ctrl = true)), (reader.read(event(KEYCODE_EQUALS, META_CTRL_ON), null) as ChordRead.Chord).candidates)
    }

    @Test
    fun `the names a chord is built on, and the ones it is not`() {
        assertEquals("F", chordName(KEYCODE_F))
        assertEquals("7", chordName(KEYCODE_NUMPAD_7))
        assertEquals("F5", chordName(KEYCODE_F5))
        assertEquals("SLASH", chordName(KEYCODE_SLASH))
        assertEquals("EQUALS", chordName(KEYCODE_PLUS))
        assertEquals("ESCAPE", chordName(KEYCODE_ESCAPE))
        assertNull(chordName(KEYCODE_SHIFT_LEFT))
        assertNull(chordName(KEYCODE_ALT_RIGHT))
        assertNull(chordName(KEYCODE_VOLUME_UP))
        // Every name the reader makes is one a chord accepts.
        for (code in 0..300) chordName(code)?.let { assertTrue("$it is a chord's key", ChordKey.isKey(it)) }
    }

    @Test
    fun `without a Leader the right-hand keys are the modifiers they are`() {
        assertEquals(ChordRead.Ignored, reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON), null))
        assertEquals(ChordKey("F", alt = true), chord(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON))
        assertEquals(ChordKey("F", ctrl = true), chord(KEYCODE_F, META_CTRL_ON or META_CTRL_RIGHT_ON))
        assertFalse(reader.armed)
    }

    @Test
    fun `the Leader held with a key is the chord's Leader and not its Alt, unless the left Alt is held too`() {
        val leader = LeaderKey.RIGHT_ALT
        assertEquals(ChordRead.Consumed, reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON), leader))
        assertEquals(ChordKey("F", leader = true), chord(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON, leader))
        assertEquals(ChordKey("F", ctrl = true, leader = true), chord(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON or META_CTRL_ON, leader))
        assertEquals(ChordKey("F", alt = true, leader = true), chord(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON or META_ALT_LEFT_ON, leader))
        // The key's repeats while held keep the hold; the release after a chord arms nothing.
        assertEquals(ChordRead.Consumed, reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON, repeat = 3), leader))
        assertEquals(ChordRead.Consumed, reader.read(event(KEYCODE_ALT_RIGHT, 0, ACTION_UP), leader))
        assertFalse(reader.armed)
        assertEquals(ChordKey("F"), chord(KEYCODE_F, 0, leader))
    }

    @Test
    fun `a tapped Leader arms the next key, and a second tap or a plain Escape lets it go`() {
        val leader = LeaderKey.RIGHT_CTRL
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, 0, ACTION_UP), leader)
        assertTrue(reader.armed)
        assertEquals(ChordKey("F", leader = true), chord(KEYCODE_F, 0, leader))
        assertFalse(reader.armed)
        assertEquals(ChordKey("F"), chord(KEYCODE_F, 0, leader))
        // A second tap disarms.
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, 0, ACTION_UP), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, 0, ACTION_UP), leader)
        assertFalse(reader.armed)
        // Escape lets an armed tap go and is consumed for it; Escape with a modifier is a chord like any.
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, 0, ACTION_UP), leader)
        assertTrue(reader.armed)
        assertEquals(ChordRead.Consumed, reader.read(event(KEYCODE_ESCAPE), leader))
        assertFalse(reader.armed)
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        reader.read(event(KEYCODE_CTRL_RIGHT, 0, ACTION_UP), leader)
        assertEquals(ChordKey("ESCAPE", shift = true, leader = true), chord(KEYCODE_ESCAPE, META_SHIFT_ON, leader))
        // Under a Right Ctrl Leader the left Ctrl still joins a chord as Ctrl.
        reader.read(event(KEYCODE_CTRL_RIGHT, META_CTRL_ON or META_CTRL_RIGHT_ON), leader)
        assertEquals(ChordKey("F", ctrl = true, leader = true), chord(KEYCODE_F, META_CTRL_ON or META_CTRL_RIGHT_ON or META_CTRL_LEFT_ON, leader))
        assertEquals(ChordKey("F", leader = true), chord(KEYCODE_F, META_CTRL_ON or META_CTRL_RIGHT_ON, leader))
    }

    @Test
    fun `a release the reader never saw pressed is nobody's tap, and losing the Leader drops an arm`() {
        val leader = LeaderKey.RIGHT_ALT
        // A new reader (the chord switched tabs) sees the release alone: consumed, nothing armed.
        assertEquals(ChordRead.Consumed, reader.read(event(KEYCODE_ALT_RIGHT, 0, ACTION_UP), leader))
        assertFalse(reader.armed)
        reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON), leader)
        reader.read(event(KEYCODE_ALT_RIGHT, 0, ACTION_UP), leader)
        assertTrue(reader.armed)
        // The prefix changed under an armed tap: the next key is the key it is.
        assertEquals(ChordKey("F"), chord(KEYCODE_F, 0, null))
        assertFalse(reader.armed)
    }

    @Test
    fun `a Leader whose release landed in the window its chord opened is not held on the next key`() {
        val leader = LeaderKey.RIGHT_ALT
        reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON), leader)
        assertEquals(ChordKey("SLASH", leader = true), chord(KEYCODE_SLASH, META_ALT_ON or META_ALT_RIGHT_ON, leader))
        // The sheet took the release; the next key here comes with no Right Alt in its state and is its own, not swallowed as half a Leader chord.
        assertEquals(ChordKey("F"), chord(KEYCODE_F, 0, leader))
        assertFalse(reader.armed)
        // A Right Alt still down on the next key is the Leader still.
        reader.read(event(KEYCODE_ALT_RIGHT, META_ALT_ON or META_ALT_RIGHT_ON), leader)
        assertEquals(ChordKey("F", leader = true), chord(KEYCODE_F, META_ALT_ON or META_ALT_RIGHT_ON, leader))
    }
}

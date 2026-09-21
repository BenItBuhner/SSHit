package app.berth.android.ui.keyboard

import android.view.KeyEvent
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_F
import android.view.KeyEvent.KEYCODE_VOLUME_DOWN
import android.view.KeyEvent.KEYCODE_VOLUME_UP
import app.berth.domain.model.VolumeButtons
import app.berth.terminal.TerminalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The volume buttons as the activity routes them (spec A43): taken only while the Stage has a handler, and what each setting sends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VolumeKeysTest {
    private val keys = VolumeKeys()
    private val presses = ArrayList<Boolean>()

    private fun event(code: Int, action: Int = ACTION_DOWN, repeat: Int = 0) = KeyEvent(0L, 0L, action, code, repeat)

    @Test
    fun `with no handler every key is the window's and the system's`() {
        assertFalse(keys.dispatch(event(KEYCODE_VOLUME_UP)))
        assertFalse(keys.dispatch(event(KEYCODE_VOLUME_DOWN)))
        assertFalse(keys.dispatch(event(KEYCODE_F)))
    }

    @Test
    fun `with a handler a volume press is taken on the way down, its release swallowed, and a held button repeats`() {
        keys.handler = { presses += it }
        assertTrue(keys.dispatch(event(KEYCODE_VOLUME_UP)))
        assertTrue(keys.dispatch(event(KEYCODE_VOLUME_UP, ACTION_UP)))
        assertTrue(keys.dispatch(event(KEYCODE_VOLUME_DOWN)))
        assertTrue(keys.dispatch(event(KEYCODE_VOLUME_DOWN, repeat = 1)))
        assertTrue(keys.dispatch(event(KEYCODE_VOLUME_DOWN, ACTION_UP)))
        assertEquals(listOf(true, false, false), presses)
        // Any other key is untouched.
        assertFalse(keys.dispatch(event(KEYCODE_F)))
        // The handler gone, the buttons are the volume's again.
        keys.handler = null
        assertFalse(keys.dispatch(event(KEYCODE_VOLUME_UP)))
        assertEquals(3, presses.size)
    }

    @Test
    fun `each setting sends its pair, up then down, and Off sends nothing`() {
        assertNull(volumeButtonAction(VolumeButtons.OFF, up = true))
        assertNull(volumeButtonAction(VolumeButtons.OFF, up = false))
        assertEquals(VolumeAction.Key(TerminalKey.UP), volumeButtonAction(VolumeButtons.ARROWS, up = true))
        assertEquals(VolumeAction.Key(TerminalKey.DOWN), volumeButtonAction(VolumeButtons.ARROWS, up = false))
        assertEquals(VolumeAction.Key(TerminalKey.PAGE_UP), volumeButtonAction(VolumeButtons.PAGES, up = true))
        assertEquals(VolumeAction.Key(TerminalKey.PAGE_DOWN), volumeButtonAction(VolumeButtons.PAGES, up = false))
        assertEquals(VolumeAction.FontStep(1), volumeButtonAction(VolumeButtons.FONT_SIZE, up = true))
        assertEquals(VolumeAction.FontStep(-1), volumeButtonAction(VolumeButtons.FONT_SIZE, up = false))
        assertEquals(VolumeAction.Control('C'), volumeButtonAction(VolumeButtons.INTERRUPT_AND_ENTER, up = true))
        assertEquals(VolumeAction.Key(TerminalKey.ENTER), volumeButtonAction(VolumeButtons.INTERRUPT_AND_ENTER, up = false))
    }
}

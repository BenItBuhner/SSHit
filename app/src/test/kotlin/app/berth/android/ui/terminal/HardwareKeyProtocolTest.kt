package app.berth.android.ui.terminal

import android.app.Application
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.KEYCODE_1
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_C
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.KEYCODE_SPACE
import android.view.KeyEvent.KEYCODE_TAB
import android.view.KeyEvent.META_ALT_LEFT_ON
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON
import androidx.compose.ui.input.key.KeyEvent
import app.berth.android.screenshots.StageFixture
import app.berth.android.session.TerminalSession
import app.berth.android.ui.keyboard.KeyLayout
import app.berth.android.ui.keyboard.ShadowKeyLayout
import app.berth.android.ui.stage.ModifierLatch
import app.berth.android.ui.stage.StageInput
import app.berth.domain.model.DeckAction
import app.berth.domain.model.DeckModifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A hardware keyboard's chords from the key event to the bytes a live tab sends, through the Stage's
 * own sink and the session's encoder, on the US map as the device reads it ([ShadowKeyLayout]).
 * Until a program asks, every chord is the legacy byte it always was: Ctrl+Shift+A and Ctrl+A are
 * both ^A, Ctrl+Space NUL. A program that pushed the kitty protocol's disambiguate flag hears them
 * apart as CSI u named by the unshifted key (Ctrl+Shift+1 is 1, with its ! beside it once it asks
 * for alternate keys), the Deck's latches and combos the same as the keyboard's; one that set
 * modifyOtherKeys 2 hears xterm's `CSI 27 ; m ; code ~`; and a reset puts the legacy bytes back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowKeyLayout::class])
class HardwareKeyProtocolTest {
    private val sent = ArrayList<String>()
    private lateinit var session: TerminalSession
    private lateinit var input: StageInput

    private val ctrl = META_CTRL_ON or META_CTRL_LEFT_ON
    private val shift = META_SHIFT_ON or META_SHIFT_LEFT_ON
    private val alt = META_ALT_ON or META_ALT_LEFT_ON

    @Before
    fun live() {
        ShadowKeyLayout.layout = KeyLayout.US
        session = StageFixture.liveHomelab()
        session.sendObserver = { sent += String(it, Charsets.UTF_8) }
        input = StageInput(session = { session }, latch = ModifierLatch(), onAppAction = {})
    }

    @After
    fun close() {
        session.close()
    }

    private fun press(code: Int, meta: Int = 0) {
        handleComposeKeyEvent(KeyEvent(android.view.KeyEvent(0L, 0L, ACTION_DOWN, code, 0, meta)), input)
    }

    /** What the presses in [block] sent, one entry a press, ESC and the controls spelled out. */
    private fun heard(block: () -> Unit): List<String> {
        sent.clear()
        block()
        return sent.map { bytes ->
            bytes.map { c ->
                when {
                    c == '\u001b' -> "ESC"
                    c < ' ' || c == '\u007f' -> "0x%02x".format(c.code)
                    else -> c.toString()
                }
            }.joinToString("")
        }
    }

    @Test
    fun `until a program asks, the chords are the legacy bytes, so Ctrl+Shift+A is Ctrl+A and Ctrl+Space is NUL`() {
        val legacy = heard {
            press(KEYCODE_A, ctrl or shift)
            press(KEYCODE_A, ctrl)
            press(KEYCODE_SPACE, ctrl)
            press(KEYCODE_SPACE, shift)
            press(KEYCODE_SLASH, ctrl)
            press(KEYCODE_ESCAPE)
            press(KEYCODE_ENTER, shift)
            press(KEYCODE_TAB, shift)
            press(KEYCODE_A, alt)
        }
        assertEquals(listOf("0x01", "0x01", "0x00", " ", "0x1f", "ESC", "0x0d", "ESC[Z", "ESCa"), legacy)
    }

    @Test
    fun `under kitty's disambiguate flag each chord is CSI u named by its unshifted key, and alternate keys add the shifted one`() {
        session.emulator.write("\u001b[>1u")
        val disambiguated = heard {
            press(KEYCODE_A, ctrl or shift)
            press(KEYCODE_A, ctrl)
            press(KEYCODE_C, ctrl)
            press(KEYCODE_SPACE, ctrl)
            press(KEYCODE_1, ctrl or shift)
            press(KEYCODE_SLASH, ctrl or shift)
            press(KEYCODE_A, alt)
            press(KEYCODE_ESCAPE)
            press(KEYCODE_ENTER, shift)
            press(KEYCODE_TAB, shift)
            // Text stays text: Shift alone types the glyph, Shift+Space a space.
            press(KEYCODE_A, shift)
            press(KEYCODE_SPACE, shift)
            press(KEYCODE_ENTER)
        }
        assertEquals(
            listOf(
                "ESC[97;6u", "ESC[97;5u", "ESC[99;5u", "ESC[32;5u", "ESC[49;6u", "ESC[47;6u", "ESC[97;3u",
                "ESC[27u", "ESC[13;2u", "ESC[9;2u", "A", " ", "0x0d",
            ),
            disambiguated,
        )

        session.emulator.write("\u001b[=5u")
        val alternates = heard {
            press(KEYCODE_1, ctrl or shift)
            press(KEYCODE_A, ctrl or shift)
            press(KEYCODE_A, ctrl)
        }
        assertEquals(listOf("ESC[49:33;6u", "ESC[97:65;6u", "ESC[97;5u"), alternates)
    }

    @Test
    fun `the Deck's latches and combos are the keyboard's chords, so a latched Ctrl+Shift and a CTRL SHIFT a combo send what Ctrl+Shift+A does`() {
        session.emulator.write("\u001b[>1u")
        val deck = heard {
            input.latch.tap(DeckModifier.CTRL)
            input.latch.tap(DeckModifier.SHIFT)
            input.onText("a")
            input.dispatch(DeckAction.Combo("CTRL SHIFT a"), null)
            input.dispatch(DeckAction.Combo("CTRL c"), null)
            // Shift latched alone is the glyph's: the uppercase letter, no chord.
            input.latch.tap(DeckModifier.SHIFT)
            input.onText("a")
        }
        assertEquals(listOf("ESC[97;6u", "ESC[97;6u", "ESC[99;5u", "A"), deck)
    }

    @Test
    fun `under modifyOtherKeys 2 the chords are xterm's CSI 27, Shift+Space among them, and a reset puts the legacy bytes back`() {
        session.emulator.write("\u001b[>4;2m")
        val xterm = heard {
            press(KEYCODE_A, ctrl or shift)
            press(KEYCODE_SPACE, ctrl)
            press(KEYCODE_SPACE, shift)
            press(KEYCODE_TAB, shift)
            press(KEYCODE_A, shift)
        }
        assertEquals(listOf("ESC[27;6;65~", "ESC[27;5;32~", "ESC[27;2;32~", "ESC[27;2;9~", "A"), xterm)

        session.emulator.write("\u001bc")
        val reset = heard {
            press(KEYCODE_A, ctrl or shift)
            press(KEYCODE_SPACE, ctrl)
        }
        assertEquals(listOf("0x01", "0x00"), reset)
    }
}

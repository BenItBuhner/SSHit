package app.berth.domain.model

import kotlinx.serialization.Serializable

/**
 * Settings › Terminal beyond the font (spec C20, A65, D1). One document, so a change to any field
 * lands atomically, the way [HardwareKeyboardSettings] does.
 */
@Serializable
data class TerminalSettings(
    /** Lines of history a terminal keeps above its screen (spec, "Very long output": default 10,000, 1,000 to 100,000). */
    val scrollbackLines: Int = DEFAULT_SCROLLBACK,
    /** A one-finger horizontal drag on the terminal sends Left and Right arrows, one per cell of travel (spec D1); off by default. */
    val horizontalDragArrows: Boolean = false,
    /** A mouse's or trackpad's right click on the terminal pastes, as the two-finger tap does, unless the program has the mouse. */
    val rightClickPaste: Boolean = true,
    /** What a one-finger tap on the terminal does (spec D1). */
    val tap: TapAction = TapAction.SHOW_KEYBOARD,
    /** What a second tap within the double-tap window does (spec D1). */
    val doubleTap: DoubleTapAction = DoubleTapAction.SELECT_WORD,
    /** What two fingers tapped together do (spec D1). */
    val twoFingerTap: TwoFingerTapAction = TwoFingerTapAction.PASTE,
    /** What three fingers tapped together do (spec D1). */
    val threeFingerTap: ThreeFingerTapAction = ThreeFingerTapAction.TOGGLE_DECK,
    /** What a pinch on the terminal does (spec D1). */
    val pinch: PinchAction = PinchAction.FONT_SIZE,
) {
    companion object {
        const val DEFAULT_SCROLLBACK = 10_000
        const val MIN_SCROLLBACK = 1_000
        const val MAX_SCROLLBACK = 100_000

        /** The sizes the Settings row cycles through, the spec's bounds at either end. */
        val SCROLLBACK_CHOICES: List<Int> = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

        /** The history a terminal keeps: the host's own cap when it has one ([Host.scrollbackLines]), else [appLines], within the spec's bounds. */
        fun scrollbackFor(hostLines: Int?, appLines: Int): Int = (hostLines ?: appLines).coerceIn(MIN_SCROLLBACK, MAX_SCROLLBACK)
    }
}

/** A one-finger tap on the terminal (spec D1). */
@Serializable
enum class TapAction {
    /** The keyboard comes up, and a program that has the mouse hears a click; the default. */
    SHOW_KEYBOARD,

    /** No keyboard and no click; the terminal still takes the focus, so a hardware keyboard types there. */
    NOTHING,
}

/** The second tap of a double tap on the terminal (spec D1). */
@Serializable
enum class DoubleTapAction {
    /** The word under the finger is selected, and a double tap that drags selects whole lines; the default. */
    SELECT_WORD,

    /** Tab, through the same input as the Deck's key. */
    SEND_TAB,

    /** The second tap is a tap like the first. */
    NOTHING,
}

/** Two fingers tapped together on the terminal (spec D1). */
@Serializable
enum class TwoFingerTapAction {
    /** The clipboard, through the paste preview; the default. */
    PASTE,

    /** The New tab sheet, as the strip's + and Ctrl+T open it. */
    NEW_TAB,

    NOTHING,
}

/** Three fingers tapped together on the terminal (spec D1). */
@Serializable
enum class ThreeFingerTapAction {
    /** The Deck shown or hidden; the default. */
    TOGGLE_DECK,

    /** The rows in view to the share sheet, as the Overflow's Share screen text (D1's "Screenshot text to share"). */
    SHARE_SCREEN_TEXT,

    NOTHING,
}

/** A pinch on the terminal (spec D1). */
@Serializable
enum class PinchAction {
    /** The font size steps with the fingers' span; the default. */
    FONT_SIZE,

    NOTHING,
}

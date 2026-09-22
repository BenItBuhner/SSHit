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

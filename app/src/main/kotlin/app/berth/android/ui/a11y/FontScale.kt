package app.berth.android.ui.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.berth.domain.model.TerminalFont

/**
 * Interface text follows the system font size up to 1.3× (spec A11): past that the rows, chips and
 * keys built for a phone's width have no room left, so the theme caps the scale its text is set
 * with. The terminal is not text set by the theme; its size is its own (a fixed grid is the point
 * of a terminal) and follows the system, uncapped, only when the font asks to.
 */
const val MAX_INTERFACE_FONT_SCALE = 1.3f

/** The system's font scale as set, before the interface cap, for the terminal's Follow option. */
val LocalSystemFontScale = staticCompositionLocalOf { 1f }

/**
 * Provides the interface's density: the system's own up to the cap, and at the cap beyond it;
 * the uncapped scale stays readable through [LocalSystemFontScale].
 */
@Composable
fun CappedFontScale(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    val capped = remember(system) {
        if (system.fontScale > MAX_INTERFACE_FONT_SCALE) Density(system.density, MAX_INTERFACE_FONT_SCALE) else system
    }
    CompositionLocalProvider(
        LocalSystemFontScale provides system.fontScale,
        LocalDensity provides capped,
        content = content,
    )
}

/** The scale the terminal's size takes from the system: the system's, uncapped, for a font that follows it; 1 otherwise. */
@Composable
fun terminalFontScale(font: TerminalFont): Float = if (font.followSystemScale) LocalSystemFontScale.current else 1f

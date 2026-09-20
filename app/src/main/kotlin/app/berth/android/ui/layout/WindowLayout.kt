package app.berth.android.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** The window's width class (spec A12): compact under 600 dp, medium to 840, expanded past it. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/** The window's height class: compact under 480 dp (a phone on its side), medium to 900, expanded past it. */
enum class HeightClass { COMPACT, MEDIUM, EXPANDED }

/**
 * What the window's size allows (spec A12, C23), decided once from its width and height in dp so
 * every surface that adapts reads the same answer: whether two panes fit, whether the drawer
 * stands as a rail, whether a sheet is better a dialog, and whether the chrome should give height
 * back to the terminal. A phone in portrait answers no to all four and sees the layouts it always had.
 */
@Immutable
data class WindowLayout(val widthDp: Int, val heightDp: Int) {
    val width: WidthClass = when {
        widthDp < 600 -> WidthClass.COMPACT
        widthDp < 840 -> WidthClass.MEDIUM
        else -> WidthClass.EXPANDED
    }
    val height: HeightClass = when {
        heightDp < 480 -> HeightClass.COMPACT
        heightDp < 900 -> HeightClass.MEDIUM
        else -> HeightClass.EXPANDED
    }

    /** Two tabs side by side fit (spec C23): any width past compact, a phone on its side included. */
    val panes: Boolean get() = width != WidthClass.COMPACT

    /** The drawer stands as a permanent rail (spec C7, A12): expanded width, with the height to hold its rows. */
    val rail: Boolean get() = width == WidthClass.EXPANDED && height != HeightClass.COMPACT

    /** A sheet would be a strip across the bottom of a large window: it opens as a dialog instead (spec C23). */
    val dialogs: Boolean get() = width != WidthClass.COMPACT && height != HeightClass.COMPACT

    /** Wide but short, a phone lying on its side: the strip and the Deck lose height so the terminal keeps its rows (spec C23). */
    val shortLandscape: Boolean get() = height == HeightClass.COMPACT && widthDp > heightDp
}

/** The layout in force, provided once by the shell; null reads the window's own configuration. */
val LocalWindowLayout = compositionLocalOf<WindowLayout?> { null }

/** The [WindowLayout] the shell provided, or the one this window's configuration gives. */
@Composable
fun windowLayout(): WindowLayout {
    LocalWindowLayout.current?.let { return it }
    val configuration = LocalConfiguration.current
    val w = configuration.screenWidthDp
    val h = configuration.screenHeightDp
    return remember(w, h) { WindowLayout(w, h) }
}

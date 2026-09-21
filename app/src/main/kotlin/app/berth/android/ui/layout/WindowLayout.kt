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
 * How the drawer stands in the window (spec C7, A12): a [SHEET] over the Stage on a phone; on a
 * medium width the persistent [NARROW] column, 72 dp of group swatches and library glyphs; on an
 * expanded width the [WIDE] one, the 280 dp rail with names. The two that stand need the height
 * for their rows: a phone on its side keeps the sheet.
 */
enum class DrawerForm { SHEET, NARROW, WIDE }

/**
 * What the window's size allows (spec A12, C23), decided once from its width and height in dp so
 * every surface that adapts reads the same answer: whether two panes fit, how the drawer stands,
 * whether a sheet is better a dialog, whether the Deck has the room for its second row, and whether
 * the chrome should give height back to the terminal. A phone in portrait answers no to all of them
 * and sees the layouts it always had.
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

    /**
     * How the drawer stands (spec C7, A12): the 280 dp rail at an expanded width, the 72 dp column of
     * swatches and glyphs at a medium one, each with the height to hold its rows; a sheet otherwise.
     */
    val drawer: DrawerForm get() = when {
        height == HeightClass.COMPACT -> DrawerForm.SHEET
        width == WidthClass.EXPANDED -> DrawerForm.WIDE
        width == WidthClass.MEDIUM -> DrawerForm.NARROW
        else -> DrawerForm.SHEET
    }

    /** The drawer stands in place beside the Stage, narrow or wide, and there is nothing to open. */
    val rail: Boolean get() = drawer != DrawerForm.SHEET

    /** A sheet would be a strip across the bottom of a large window: it opens as a dialog instead (spec C23). */
    val dialogs: Boolean get() = width != WidthClass.COMPACT && height != HeightClass.COMPACT

    /**
     * The Deck's two-row mode is the default (spec C4: "default on tablets"): a window past compact
     * both ways, which is a tablet either way up or a fold open, where a second row of 44 dp keys
     * takes from the terminal what it has to spare. A phone keeps its one row, on its side above all.
     */
    val twoRowDeck: Boolean get() = width != WidthClass.COMPACT && height != HeightClass.COMPACT

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

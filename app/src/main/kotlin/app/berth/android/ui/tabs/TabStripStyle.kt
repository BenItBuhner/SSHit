package app.berth.android.ui.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius

/** How the active tab is told apart from the rest. */
enum class ActiveTabMark {
    /** A raised block on `surface.3` (spec C3, Tab anatomy). */
    FILL,

    /** A 2 dp accent underline, browser style; no fill. */
    UNDERLINE,

    /** Title colour alone. */
    TEXT,
}

/** Whether the strip is a flat toolbar on `surface.1` or a floating island inset from the edges. */
enum class StripChrome { FLAT, ISLAND }

/**
 * Every visual decision of the tab strip, in one place, so the chrome around the behaviour can be
 * swapped without touching [TabStrip]: tab shape, the active treatment, whether inactive tabs
 * carry titles or only their monogram, and whether the strip is a toolbar or an island. Colours
 * that are null fall back to the interface theme's tokens at draw time, so a style stays themeable.
 */
@Immutable
data class TabStripStyle(
    /** Header row height; the terminal starts beneath it, so it must not grow past the ribbon it replaces. */
    val height: Dp = 40.dp,
    val chrome: StripChrome = StripChrome.FLAT,
    /** Island only: inset from the header's edges and the island's own radius. */
    val islandInset: Dp = 6.dp,
    val islandRadius: Dp? = null,
    /** Inset at both ends of the scrolling strip and the gap between tabs. */
    val edgeInset: Dp = 8.dp,
    val gap: Dp = 4.dp,
    val tabHeight: Dp = 32.dp,
    /** Null follows `BerthRadius.key` (10 dp at the default scale). */
    val tabRadius: Dp? = null,
    val tabPadding: Dp = 6.dp,
    val tabMinWidth: Dp = 88.dp,
    val tabMaxWidth: Dp = 160.dp,
    val swatchSize: Dp = 20.dp,
    /** Null follows `BerthRadius.indicator` (4 dp), concentric with the tab. */
    val swatchRadius: Dp? = null,
    /** Titles on inactive tabs; off shows only their monogram, the active tab keeps its title. */
    val inactiveTitles: Boolean = true,
    val activeMark: ActiveTabMark = ActiveTabMark.FILL,
    /** Fill behind the active tab (FILL mark) and behind any pressed tab; null is `surface.3` / `surface.4`. */
    val activeFill: Color? = null,
    val pressedFill: Color? = null,
    /** Fill behind idle tabs; the default is none so a full strip reads as text and swatches, not grey boxes. */
    val idleFill: Color? = null,
    /** How strongly the group colour tints the active tab's fill while more than one group exists (spec C3, Groups). */
    val groupTintOnActive: Float = 0.12f,
    /** The close glyph on the active tab. */
    val closeOnActive: Boolean = true,
    /** Group chip height; full radius. */
    val chipHeight: Dp = 22.dp,
    /** Count tile size and radius (spec C3, Switcher). */
    val countTileSize: Dp = 24.dp,
    val countTileRadius: Dp? = null,
) {
    companion object {
        /** The spec's own treatment: raised active block, titled tabs everywhere, flat toolbar. */
        val Default = TabStripStyle()
    }
}

/** The strip style in force; the Stage provides it once, so a direction change is a single edit. */
val LocalTabStripStyle = staticCompositionLocalOf { TabStripStyle.Default }

/** Resolves the nullable colours and radii of [TabStripStyle] against the current theme. */
@Immutable
internal data class ResolvedTabStyle(
    val style: TabStripStyle,
    val tabRadius: Dp,
    val swatchRadius: Dp,
    val islandRadius: Dp,
    val countTileRadius: Dp,
    val activeFill: Color,
    val pressedFill: Color,
)

@Composable
internal fun rememberResolvedTabStyle(style: TabStripStyle = LocalTabStripStyle.current): ResolvedTabStyle {
    val c = Berth.colors
    val key = BerthRadius.key
    val indicator = BerthRadius.indicator
    val row = BerthRadius.row
    val swatchSmall = BerthRadius.swatchSmall
    return ResolvedTabStyle(
        style = style,
        tabRadius = style.tabRadius ?: key,
        swatchRadius = style.swatchRadius ?: indicator,
        islandRadius = style.islandRadius ?: row,
        countTileRadius = style.countTileRadius ?: swatchSmall,
        activeFill = style.activeFill ?: c.surface3,
        pressedFill = style.pressedFill ?: c.surface4,
    )
}

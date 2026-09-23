package app.berth.android.ui.tabs

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.berth.android.ui.theme.Berth
import app.berth.android.ui.theme.BerthRadius
import app.berth.android.ui.theme.BerthType
import app.berth.android.ui.theme.DensityTokens

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
 * carry titles or only their monogram, whether the strip is a toolbar or an island, the surface
 * it sits on, the group chip's whole treatment and the type. Colours, radii and text styles that
 * are null fall back to the interface theme's tokens at draw time, so a style stays themeable;
 * a direction is one `TabStripStyle(...)` literal provided at the Stage.
 */
@Immutable
data class TabStripStyle(
    /** Header row height; the terminal starts beneath it, so it must not grow past the ribbon it replaces. */
    val height: Dp = DensityTokens.Comfortable.header,
    val chrome: StripChrome = StripChrome.FLAT,
    /** Island only: inset from the header's edges and the island's own radius. */
    val islandInset: Dp = 6.dp,
    val islandRadius: Dp? = null,
    /**
     * The header's own surface: the toolbar's background for [StripChrome.FLAT], the ground the
     * island floats on for [StripChrome.ISLAND]. Null is `surface.1` for the toolbar and nothing for
     * the island's ground, so an island sits over whatever is beneath the header.
     */
    val headerFill: Color? = null,
    /** Island only: the island's fill; null is `surface.1`. */
    val islandFill: Color? = null,
    /** Inset at both ends of the scrolling strip and the gap between tabs. */
    val edgeInset: Dp = 8.dp,
    val gap: Dp = 4.dp,
    /**
     * Gap before a group chip that heads a run after the first, so the run boundary reads as
     * spacing (spec C3, Groups forbids bars); at least [gap], which every neighbour gets.
     */
    val groupGap: Dp = 12.dp,
    /**
     * Width of the fade cut into either end of the strip while it can scroll that way, so a title
     * cut by the edge reads as continuing rather than as a rendering fault; zero draws none.
     */
    val edgeFade: Dp = 16.dp,
    /** Gutter between the strip's end and the header's fixed slots (the count tile and Overflow). */
    val trailingGap: Dp = 8.dp,
    /**
     * How far a tab's touch target reaches up into the status-bar inset above a flat toolbar, so a
     * 40 dp row gives a 48 dp target (spec C3, Header row, at the accessibility pass's 48 dp floor
     * rather than the spec's 44); the visual does not move. The header's fixed slots reach with it.
     */
    val topReach: Dp = 8.dp,
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
    /** The [ActiveTabMark.UNDERLINE] colour; null is the accent. */
    val underlineColor: Color? = null,
    /** Tab titles; null is Label. */
    val titleStyle: TextStyle? = null,
    /** The active (and pressed) tab's title colour, which is all the [ActiveTabMark.TEXT] mark has; null is `text.1`. */
    val activeTitleColor: Color? = null,
    /** The monogram on the swatch; null is Label at 9 sp in white at 92 %. */
    val monogramStyle: TextStyle? = null,
    val monogramColor: Color? = null,
    /** Group chip height, corner radius (null is a full pill) and the padding either side of its label. */
    val chipHeight: Dp = 22.dp,
    val chipRadius: Dp? = null,
    val chipPadding: Dp = 8.dp,
    /** The group colour's alpha over the surface behind the chip, at rest and pressed. */
    val chipFillAlpha: Float = 0.2f,
    val chipPressedAlpha: Float = 0.32f,
    /** Whether the chip's label is set in capitals; its text style, null is Caption. */
    val chipUppercase: Boolean = true,
    val chipTextStyle: TextStyle? = null,
    /** Count tile size, radius and fill (spec C3, Switcher); null radius follows `BerthRadius.swatchSmall`, null fill is `surface.2`. */
    val countTileSize: Dp = 24.dp,
    val countTileRadius: Dp? = null,
    val countTileFill: Color? = null,
) {
    companion object {
        /** The spec's own treatment: raised active block, titled tabs everywhere, flat toolbar. */
        val Default = TabStripStyle()
    }
}

/** The strip style in force; the Stage provides it once, so a direction change is a single edit. */
val LocalTabStripStyle = staticCompositionLocalOf { TabStripStyle.Default }

/** Resolves the nullable colours, radii, shapes and text styles of [TabStripStyle] against the current theme. */
@Immutable
internal data class ResolvedTabStyle(
    val style: TabStripStyle,
    val tabRadius: Dp,
    val swatchRadius: Dp,
    val islandRadius: Dp,
    val countTileRadius: Dp,
    val activeFill: Color,
    val pressedFill: Color,
    val headerFill: Color,
    val islandFill: Color,
    val underlineColor: Color,
    val titleStyle: TextStyle,
    val activeTitleColor: Color,
    val monogramStyle: TextStyle,
    val monogramColor: Color,
    val chipShape: Shape,
    val chipTextStyle: TextStyle,
    val countTileFill: Color,
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
        headerFill = style.headerFill ?: if (style.chrome == StripChrome.FLAT) c.surface1 else Color.Transparent,
        islandFill = style.islandFill ?: c.surface1,
        underlineColor = style.underlineColor ?: c.accent,
        titleStyle = style.titleStyle ?: BerthType.label,
        activeTitleColor = style.activeTitleColor ?: c.text1,
        monogramStyle = style.monogramStyle ?: BerthType.label.copy(fontSize = 9.sp, lineHeight = 12.sp),
        monogramColor = style.monogramColor ?: Color.White.copy(alpha = 0.92f),
        chipShape = style.chipRadius?.let { RoundedCornerShape(it) } ?: CircleShape,
        chipTextStyle = style.chipTextStyle ?: BerthType.caption,
        countTileFill = style.countTileFill ?: c.surface2,
    )
}

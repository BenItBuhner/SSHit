package app.berth.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.berth.android.ui.a11y.CappedFontScale
import app.berth.android.ui.a11y.LocalReducedMotion
import app.berth.android.ui.a11y.rememberReducedMotion
import app.berth.domain.model.InterfaceContrast
import app.berth.domain.model.InterfaceTheme
import app.berth.domain.model.InterfaceVariant

/**
 * Interface tokens from the UX spec (A2). Surfaces are tonal steps, text has three levels, and
 * the state colours are shared by dots, rings and pills. No token here is ever used as a stroke.
 */
@Immutable
data class BerthColors(
    val surface0: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val surface4: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val onAccent: Color,
    val live: Color,
    val pending: Color,
    val detached: Color,
    val attention: Color,
    val danger: Color,
    val onDanger: Color,
    val scrim: Color,
    val isDark: Boolean,
) {
    /** The tonal step above [step], used for pressed and focused states. */
    fun surface(step: Int): Color = when (step.coerceIn(0, 4)) {
        0 -> surface0
        1 -> surface1
        2 -> surface2
        3 -> surface3
        else -> surface4
    }

    /** The tonal step [color] is, or null when it is not one of the five surfaces. */
    fun stepOf(color: Color): Int? = when (color) {
        surface0 -> 0
        surface1 -> 1
        surface2 -> 2
        surface3 -> 3
        surface4 -> 4
        else -> null
    }
}

/** Multiplier applied to every radius; set by [BerthTheme] from [InterfaceTheme.radiusScale]. */
val LocalRadiusScale = staticCompositionLocalOf { 1f }

/**
 * Radius scale (A5); each component class has its own entry. Every value is multiplied by the
 * interface theme's radius scale, so nested corners stay concentric at any setting.
 */
object BerthRadius {
    val sheet: Dp @Composable get() = 28.dp * LocalRadiusScale.current
    val panel: Dp @Composable get() = 20.dp * LocalRadiusScale.current
    val row: Dp @Composable get() = 12.dp * LocalRadiusScale.current
    val key: Dp @Composable get() = 10.dp * LocalRadiusScale.current
    val swatch: Dp @Composable get() = 8.dp * LocalRadiusScale.current
    val swatchSmall: Dp @Composable get() = 6.dp * LocalRadiusScale.current
    val indicator: Dp @Composable get() = 4.dp * LocalRadiusScale.current
}

/** Spacing scale (A4). */
object BerthSpace {
    val screenMargin: Dp = 20.dp
    val panelPadding: Dp = 16.dp
    val rowGap: Dp = 6.dp
    val panelGap: Dp = 12.dp
    val sectionGap: Dp = 24.dp
}

private val WarmDark = BerthColors(
    surface0 = Color(0xFF121110), surface1 = Color(0xFF1A1917), surface2 = Color(0xFF232220),
    surface3 = Color(0xFF2D2B28), surface4 = Color(0xFF37342F),
    text1 = Color(0xFFECE8E1), text2 = Color(0xFFA8A39B), text3 = Color(0xFF6E6A64),
    accent = Color(0xFFE0A458), onAccent = Color(0xFF1A1408),
    live = Color(0xFF8FB573), pending = Color(0xFFE0A458), detached = Color(0xFF6E6A64),
    attention = Color(0xFF7AD3C6), danger = Color(0xFFD9776B), onDanger = Color(0xFF1A0E0C),
    scrim = Color(0x52000000), isDark = true,
)

private val CoolDark = WarmDark.copy(
    surface0 = Color(0xFF101214), surface1 = Color(0xFF17191D), surface2 = Color(0xFF1F2226),
    surface3 = Color(0xFF292C31), surface4 = Color(0xFF33373D),
    text1 = Color(0xFFE6E8EC), text2 = Color(0xFFA3A8B0), text3 = Color(0xFF6A6F77),
)

private val WarmTrueBlack = WarmDark.copy(
    surface0 = Color(0xFF000000), surface1 = Color(0xFF0E0D0C), surface2 = Color(0xFF181715),
    surface3 = Color(0xFF232220), surface4 = Color(0xFF2D2B28),
)

private val CoolTrueBlack = CoolDark.copy(
    surface0 = Color(0xFF000000), surface1 = Color(0xFF0C0D0F), surface2 = Color(0xFF161719),
    surface3 = Color(0xFF202225), surface4 = Color(0xFF2A2C30),
)

// text.2 sits four steps under the spec's #5B5751 (and the cool set's #565B63): at the spec's
// value it reads 4.3:1 on surface.4, the pressed and selected step, and the spec promises 4.5 on
// every surface (A11). The step is below what the eye separates; the ratio is 4.55.
private val WarmLight = BerthColors(
    surface0 = Color(0xFFF5F2EC), surface1 = Color(0xFFECE8E1), surface2 = Color(0xFFE2DDD4),
    surface3 = Color(0xFFD8D2C8), surface4 = Color(0xFFCEC7BC),
    text1 = Color(0xFF1E1C19), text2 = Color(0xFF57534D), text3 = Color(0xFF8B867E),
    accent = Color(0xFFB5782E), onAccent = Color(0xFFFFF8EE),
    live = Color(0xFF4F7F3A), pending = Color(0xFFB5782E), detached = Color(0xFF8B867E),
    attention = Color(0xFF1F8C7E), danger = Color(0xFFB4483A), onDanger = Color(0xFFFFF8EE),
    scrim = Color(0x52000000), isDark = false,
)

private val CoolLight = WarmLight.copy(
    surface0 = Color(0xFFF1F3F6), surface1 = Color(0xFFE7EAEE), surface2 = Color(0xFFDCE0E6),
    surface3 = Color(0xFFD1D6DD), surface4 = Color(0xFFC6CCD4),
    text1 = Color(0xFF1B1D21), text2 = Color(0xFF51565E), text3 = Color(0xFF858B94),
)

private fun lerp(a: BerthColors, b: BerthColors, t: Float): BerthColors = BerthColors(
    surface0 = lerp(a.surface0, b.surface0, t), surface1 = lerp(a.surface1, b.surface1, t),
    surface2 = lerp(a.surface2, b.surface2, t), surface3 = lerp(a.surface3, b.surface3, t),
    surface4 = lerp(a.surface4, b.surface4, t),
    text1 = lerp(a.text1, b.text1, t), text2 = lerp(a.text2, b.text2, t), text3 = lerp(a.text3, b.text3, t),
    accent = a.accent, onAccent = a.onAccent, live = a.live, pending = a.pending, detached = a.detached,
    attention = a.attention, danger = a.danger, onDanger = a.onDanger, scrim = a.scrim, isDark = a.isDark,
)

/** The two inks the spec puts on an accent (A2): warm near-black, warm near-white. */
private val InkOnLightAccent = Color(0xFF1A1408)
private val InkOnDarkAccent = Color(0xFFFFF8EE)

/**
 * WCAG 2 contrast ratio of [this] over [background], 1 to 21; 4.5 is the floor for text, 3 for
 * large text and for a control's own colour against what it sits on.
 */
fun Color.contrastAgainst(background: Color): Float {
    val a = luminance() + 0.05f
    val b = background.luminance() + 0.05f
    return maxOf(a, b) / minOf(a, b)
}

/**
 * Text colour that reads on [accent]: whichever of the spec's two inks contrasts more with it. The
 * inks cross at a luminance near 0.19, so the mid-tone presets (Copper, Verdigris, Slate, Moss,
 * Rose, Mauve) all take the near-black ink at 6:1 and better; a threshold that sent them the
 * near-white one left a one-shot Deck key's label at 2:1 on the default accent.
 */
fun onAccentFor(accent: Color): Color =
    if (InkOnLightAccent.contrastAgainst(accent) >= InkOnDarkAccent.contrastAgainst(accent)) InkOnLightAccent else InkOnDarkAccent

fun Int.toColor(): Color = Color(0xFF000000.toInt() or (this and 0xFFFFFF))

/** Whether [theme] is a dark interface when the system is in [systemDark]. */
fun InterfaceTheme.isDark(systemDark: Boolean): Boolean = when (variant) {
    InterfaceVariant.DARK, InterfaceVariant.TRUE_BLACK -> true
    InterfaceVariant.LIGHT -> false
    InterfaceVariant.SYSTEM -> systemDark
}

/**
 * The tokens for [theme] with [accent] (its own, or the system's under Material You), for a
 * [dark] or light interface: the warm and cool sets of the variant blended by tone, the accent
 * and its ink over them, and the high-contrast lift of `text.3` and `surface.2` (A11). Pure, so
 * the contrast of every pairing the spec promises can be measured off the composition.
 */
fun berthColors(theme: InterfaceTheme, dark: Boolean, accent: Color = theme.accent.toColor()): BerthColors {
    val trueBlack = theme.variant == InterfaceVariant.TRUE_BLACK
    val warm = if (dark) (if (trueBlack) WarmTrueBlack else WarmDark) else WarmLight
    val cool = if (dark) (if (trueBlack) CoolTrueBlack else CoolDark) else CoolLight
    var colors = lerp(warm, cool, theme.tone.coerceIn(0f, 1f))
    colors = colors.copy(accent = accent, onAccent = onAccentFor(accent), pending = accent)
    if (theme.contrast == InterfaceContrast.HIGH) {
        colors = if (dark) colors.copy(text3 = Color(0xFF8B867E), surface2 = Color(0xFF2A2825)) else colors.copy(text3 = Color(0xFF6A665F))
    }
    return colors
}

@Composable
fun rememberBerthColors(theme: InterfaceTheme, systemDark: Boolean = isSystemInDarkTheme()): BerthColors {
    val dark = theme.isDark(systemDark)
    val system = materialYouAccent(dark)
    val accent = if (theme.materialYou && system != null) system else theme.accent.toColor()
    return berthColors(theme, dark, accent)
}

/** Whether the system has a wallpaper accent to offer (Material You, Android 12 and later). */
val MaterialYouAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** The system's Material You accent for a [dark] or light interface, or null before Android 12. */
@Composable
fun materialYouAccent(dark: Boolean): Color? {
    if (!MaterialYouAvailable) return null
    val context = LocalContext.current
    return (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
}

val LocalBerthColors = staticCompositionLocalOf { WarmDark }

/** Shortcut for the current tokens: `Berth.colors.surface2`. */
object Berth {
    val colors: BerthColors
        @Composable get() = LocalBerthColors.current
}

private fun BerthColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface3,
        onPrimaryContainer = text1,
        inversePrimary = accent,
        secondary = text2,
        onSecondary = surface0,
        secondaryContainer = surface3,
        onSecondaryContainer = text1,
        tertiary = attention,
        onTertiary = surface0,
        tertiaryContainer = surface3,
        onTertiaryContainer = text1,
        background = surface0,
        onBackground = text1,
        surface = surface0,
        onSurface = text1,
        surfaceVariant = surface2,
        onSurfaceVariant = text2,
        surfaceTint = Color.Transparent,
        inverseSurface = text1,
        inverseOnSurface = surface0,
        error = danger,
        onError = onDanger,
        errorContainer = surface3,
        onErrorContainer = danger,
        outline = surface4,
        outlineVariant = surface3,
        scrim = Color.Black,
        surfaceBright = surface4,
        surfaceDim = surface0,
        surfaceContainer = surface2,
        surfaceContainerHigh = surface3,
        surfaceContainerHighest = surface4,
        surfaceContainerLow = surface1,
        surfaceContainerLowest = surface0,
    )
}

@Composable
fun berthShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(BerthRadius.swatch),
    small = RoundedCornerShape(BerthRadius.row),
    medium = RoundedCornerShape(BerthRadius.panel),
    large = RoundedCornerShape(BerthRadius.sheet),
    extraLarge = RoundedCornerShape(BerthRadius.sheet),
)

/**
 * The app's theme. [reducedMotion] is the system's reduced-motion setting by default (spec A7), so
 * every motion under the theme answers it through [LocalReducedMotion]; a test passes its own.
 * Interface text under the theme follows the system font size up to 1.3× (spec A11).
 */
@Composable
fun BerthTheme(
    theme: InterfaceTheme = InterfaceTheme.DEFAULT,
    reducedMotion: Boolean = rememberReducedMotion(),
    content: @Composable () -> Unit,
) {
    val colors = rememberBerthColors(theme)
    CompositionLocalProvider(
        LocalBerthColors provides colors,
        LocalRadiusScale provides theme.radiusScale.coerceIn(InterfaceTheme.MIN_RADIUS_SCALE, InterfaceTheme.MAX_RADIUS_SCALE),
        LocalReducedMotion provides reducedMotion,
    ) {
        CappedFontScale {
            MaterialTheme(
                colorScheme = colors.toMaterial(),
                typography = berthTypography(useSystemFont = theme.useSystemFont),
                shapes = berthShapes(),
                content = content,
            )
        }
    }
}

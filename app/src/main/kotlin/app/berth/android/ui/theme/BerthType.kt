package app.berth.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.berth.android.R

/** IBM Plex Sans, bundled (OFL). The interface font per UX spec A3. */
val PlexSans: FontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** JetBrains Mono, bundled (OFL). The default terminal and code font. */
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

private val Trim = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = if (tracking == 0f) 0.sp else tracking.em,
    lineHeightStyle = Trim,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Type roles from UX spec A3, addressable by name rather than by M3 slot. */
class BerthTypeRoles(family: FontFamily) {
    val display = style(family, 28, 34, FontWeight.Medium)
    val title = style(family, 20, 26, FontWeight.Medium)
    val headline = style(family, 17, 24, FontWeight.Medium)
    val body = style(family, 15, 22, FontWeight.Normal)
    val bodyMedium = style(family, 15, 22, FontWeight.Medium)
    val label = style(family, 13, 18, FontWeight.Medium)
    val caption = style(family, 11, 16, FontWeight.Medium, tracking = 0.08f)
    val mono = style(JetBrainsMono, 13, 18, FontWeight.Normal)
    val monoLarge = style(JetBrainsMono, 17, 24, FontWeight.Normal)
}

val BerthType = BerthTypeRoles(PlexSans)
val BerthTypeSystem = BerthTypeRoles(FontFamily.SansSerif)

fun berthTypography(useSystemFont: Boolean): Typography {
    val t = if (useSystemFont) BerthTypeSystem else BerthType
    return Typography(
        displaySmall = t.display,
        headlineMedium = t.display,
        headlineSmall = t.title,
        titleLarge = t.title,
        titleMedium = t.headline,
        titleSmall = t.bodyMedium,
        bodyLarge = t.body,
        bodyMedium = style(if (useSystemFont) FontFamily.SansSerif else PlexSans, 13, 18, FontWeight.Normal),
        bodySmall = t.caption,
        labelLarge = t.label,
        labelMedium = t.label,
        labelSmall = t.caption,
    )
}

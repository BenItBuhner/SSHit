package dev.sshclient.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF16483A),
    onPrimaryContainer = Color(0xFFA6F2D0),
    secondary = Color(0xFFB4C9D9),
    onSecondary = Color(0xFF1E3340),
    secondaryContainer = Color(0xFF2B3D4A),
    onSecondaryContainer = Color(0xFFD1E5F5),
    tertiary = Color(0xFFE2B8FF),
    background = Color(0xFF0F1216),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A2028),
    onSurfaceVariant = Color(0xFFB7C0CA),
    surfaceContainerLowest = Color(0xFF0A0D10),
    surfaceContainerLow = Color(0xFF13171C),
    surfaceContainer = Color(0xFF171C22),
    surfaceContainerHigh = Color(0xFF1D232B),
    surfaceContainerHighest = Color(0xFF242B34),
    outline = Color(0xFF3A434E),
    outlineVariant = Color(0xFF262D36),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3E0000),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF176B53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D0),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A6272),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E5F5),
    onSecondaryContainer = Color(0xFF071E2B),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF171C21),
    surface = Color(0xFFF7F9FB),
    onSurface = Color(0xFF171C21),
    surfaceVariant = Color(0xFFE3E8ED),
    onSurfaceVariant = Color(0xFF444C55),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFEBEFF3),
    surfaceContainerHigh = Color(0xFFE5EAEE),
    surfaceContainerHighest = Color(0xFFDFE4E9),
    outline = Color(0xFF74808B),
    outlineVariant = Color(0xFFC4CCD4),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

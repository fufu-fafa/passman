package app.passman.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val Light = lightColorScheme(
    primary = Color(0xFF1F5B4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE8DC),
    onPrimaryContainer = Color(0xFF0B2B22),
    secondary = Color(0xFF5E6B63),
    secondaryContainer = Color(0xFFE2E8E2),
    onSecondaryContainer = Color(0xFF1B231E),
    tertiary = Color(0xFF8A5A12),
    tertiaryContainer = Color(0xFFFBE3BC),
    onTertiaryContainer = Color(0xFF2C1A00),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE4E3DC),
    onSurfaceVariant = Color(0xFF47483F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F0EA),
    surfaceContainer = Color(0xFFEDEBE4),
    surfaceContainerHigh = Color(0xFFE7E5DE),
    surfaceContainerHighest = Color(0xFFE1DFD8),
    outline = Color(0xFF787970),
    outlineVariant = Color(0xFFC8C7BE),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FD3BC),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF1F5B4C),
    onPrimaryContainer = Color(0xFFCDE8DC),
    secondary = Color(0xFFBCC9C0),
    secondaryContainer = Color(0xFF3A443E),
    onSecondaryContainer = Color(0xFFDDE6DE),
    tertiary = Color(0xFFF0BF6E),
    tertiaryContainer = Color(0xFF5F3F00),
    onTertiaryContainer = Color(0xFFFFDDB0),
    background = Color(0xFF121412),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF121412),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF45483F),
    onSurfaceVariant = Color(0xFFC6C7BE),
    surfaceContainerLowest = Color(0xFF0D0F0D),
    surfaceContainerLow = Color(0xFF1A1C1A),
    surfaceContainer = Color(0xFF1E201E),
    surfaceContainerHigh = Color(0xFF282A28),
    surfaceContainerHighest = Color(0xFF333533),
    outline = Color(0xFF90918A),
    outlineVariant = Color(0xFF45483F),
)

/** Monospace for secrets so similar characters (l, 1, I, O, 0) stay distinguishable. */
val SecretFont = FontFamily.Monospace

@Composable
fun PassManTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

package app.passman.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// Palette shared with ssh_terminal (GitHub-dark style). Dark only, like that app.
private val Bg = Color(0xFF0D1117)
private val Surface = Color(0xFF161B22)
private val Border = Color(0xFF30363D)
private val Accent = Color(0xFF3FB950)
private val Text = Color(0xFFE6EDF3)
private val TextDim = Color(0xFF8B949E)
private val Error = Color(0xFFF85149)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Bg,
    primaryContainer = Color(0xFF1B3A24),
    onPrimaryContainer = Color(0xFFAFF5B4),
    inversePrimary = Color(0xFF1A7F37),
    secondary = TextDim,
    onSecondary = Bg,
    secondaryContainer = Color(0xFF21262D),
    onSecondaryContainer = Text,
    tertiary = Color(0xFFD29922),
    onTertiary = Bg,
    tertiaryContainer = Color(0xFF3B2E0A),
    onTertiaryContainer = Color(0xFFF8E3A1),
    background = Bg,
    onBackground = Text,
    surface = Bg,
    onSurface = Text,
    surfaceVariant = Surface,
    onSurfaceVariant = TextDim,
    surfaceTint = Accent,
    inverseSurface = Text,
    inverseOnSurface = Bg,
    surfaceContainerLowest = Color(0xFF010409),
    surfaceContainerLow = Color(0xFF11161D),
    surfaceContainer = Surface,
    surfaceContainerHigh = Color(0xFF1C2128),
    surfaceContainerHighest = Color(0xFF21262D),
    surfaceBright = Color(0xFF262C36),
    surfaceDim = Bg,
    outline = TextDim,
    outlineVariant = Border,
    error = Error,
    onError = Bg,
    errorContainer = Color(0xFF3D1214),
    onErrorContainer = Color(0xFFFFDCD7),
    scrim = Color.Black,
)

// ssh_terminal's buttons and fields use 10dp corners.
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/** Monospace for secrets so similar characters (l, 1, I, O, 0) stay distinguishable. */
val SecretFont = FontFamily.Monospace

@Composable
fun PassManTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, shapes = AppShapes, content = content)
}

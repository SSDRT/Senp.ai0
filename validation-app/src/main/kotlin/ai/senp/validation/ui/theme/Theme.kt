package ai.senp.validation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Modern HSL tailored dark-mode palette for exercise analysis
val DarkBackground = Color(0xFF0F141C)
val DarkSurface = Color(0xFF18202C)
val DarkSurfaceVariant = Color(0xFF222D3E)
val PrimaryCyan = Color(0xFF00E5FF)
val PrimaryCyanVariant = Color(0xFF00B0FF)
val SecondaryLime = Color(0xFF76FF03)
val AccentAmber = Color(0xFFFFC107)
val ErrorRed = Color(0xFFFF5252)

val OnDarkBackground = Color(0xFFECEFF1)
val OnDarkSurface = Color(0xFFCFD8DC)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = Color.Black,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = PrimaryCyan,
    secondary = SecondaryLime,
    onSecondary = Color.Black,
    background = DarkBackground,
    onBackground = OnDarkBackground,
    surface = DarkSurface,
    onSurface = OnDarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = OnDarkSurface,
    error = ErrorRed,
    onError = Color.White,
)

private val LightColorScheme = darkColorScheme(
    primary = PrimaryCyan,
    onPrimary = Color.Black,
    background = DarkBackground,
    surface = DarkSurface,
)

@Composable
fun SenpTheme(
    darkTheme: Boolean = true, // Default to sleek dark mode for video overlays
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

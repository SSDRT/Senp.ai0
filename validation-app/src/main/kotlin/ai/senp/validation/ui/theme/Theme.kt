package ai.senp.validation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Senp's visual language mirrors the reference onboarding screens: deep navy, electric blue, and violet. */
val SenpBackground = Color(0xFF050A16)
val SenpBackgroundRaised = Color(0xFF09172C)
val SenpSurface = Color(0xFF12182C)
val SenpSurfaceRaised = Color(0xFF191D35)
val SenpBorder = Color(0xFF29314D)
val SenpBlue = Color(0xFF0B91FF)
val SenpBlueBright = Color(0xFF21B6FF)
val SenpViolet = Color(0xFF5C52E8)
val SenpCream = Color(0xFFF5F1E8)
val SenpMuted = Color(0xFF9298AE)
val SenpSuccess = Color(0xFF6BE6C0)
val SenpWarning = Color(0xFFFFC56B)
val SenpError = Color(0xFFFF748B)
val SenpAccent = Brush.horizontalGradient(listOf(SenpBlue, SenpViolet))

private val DarkColorScheme = darkColorScheme(
    primary = SenpBlue,
    onPrimary = Color.White,
    primaryContainer = SenpBackgroundRaised,
    onPrimaryContainer = SenpBlueBright,
    secondary = SenpViolet,
    onSecondary = Color.Black,
    background = SenpBackground,
    onBackground = SenpCream,
    surface = SenpSurface,
    onSurface = SenpCream,
    surfaceVariant = SenpSurfaceRaised,
    onSurfaceVariant = SenpMuted,
    error = SenpError,
    onError = Color.White,
)

@Composable
fun SenpTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

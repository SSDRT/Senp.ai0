package ai.senp.validation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Minimal monochrome palette: black, graphite, grey, and white. */
val SenpBackground = Color(0xFF08090C)
val SenpBackgroundRaised = Color(0xFF0D0F14)
val SenpSurface = Color(0xFF12151B)
val SenpSurfaceRaised = Color(0xFF1A1E27)
val SenpBorder = Color(0xFF303641)
val SenpBlue = Color(0xFFB7BBC2)
val SenpBlueBright = Color(0xFFF2F2F2)
val SenpViolet = Color(0xFF969AA3)
val SenpCream = Color(0xFFF2F2F0)
val SenpMuted = Color(0xFF92969F)
val SenpSuccess = Color(0xFF6BE6C0)
val SenpWarning = Color(0xFFFFC56B)
val SenpError = Color(0xFFFF748B)
val SenpAccent = Brush.horizontalGradient(listOf(SenpBlue, SenpViolet))

private val DarkColorScheme = darkColorScheme(
    primary = SenpBlueBright,
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

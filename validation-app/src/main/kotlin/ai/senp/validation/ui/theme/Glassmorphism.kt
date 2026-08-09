package ai.senp.validation.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val SenpPageBackdrop = Brush.verticalGradient(
    0f to Color(0xFF090A0E),
    0.35f to SenpBackground,
    1f to Color(0xFF11131A),
)

private val SenpGlassFill = Brush.verticalGradient(
    0f to Color(0xE61D222B),
    0.42f to Color(0xD611141A),
    1f to Color(0xF20C0E13),
)

private val SenpGlassHighlight = Brush.horizontalGradient(
    0f to SenpBlueBright.copy(alpha = 0.72f),
    0.22f to Color.Transparent,
    0.78f to Color.Transparent,
    1f to SenpViolet.copy(alpha = 0.62f),
)

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .clip(shape)
            .background(SenpGlassFill)
            .border(BorderStroke(1.dp, SenpGlassHighlight), shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(SenpGlassFill)
            .border(BorderStroke(1.dp, SenpGlassHighlight), shape),
        content = { content() },
    )
}

fun Modifier.glassBackground(shape: RoundedCornerShape = RoundedCornerShape(22.dp)): Modifier =
    clip(shape)
        .background(SenpGlassFill)
        .border(BorderStroke(1.dp, SenpGlassHighlight), shape)

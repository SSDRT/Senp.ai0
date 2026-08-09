package ai.senp.validation.ui.screens

import ai.senp.core.contracts.PipelineStageId
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import ai.senp.validation.ui.theme.SenpViolet
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A lightweight radar/scan animation; it runs entirely in Compose and needs no image asset. */
@Composable
fun AnalysisProgressScreen(
    activeStage: PipelineStageId,
    progressPercent: Float,
    statusMessage: String,
    onBack: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "analysisRadar")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "radarRotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1500
                0.88f at 0
                1.08f at 750
                0.88f at 1500
            },
            RepeatMode.Restart,
        ),
        label = "radarPulse",
    )
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "scanLine",
    )
    val stageProgress = progressForStage(activeStage)
    val progress = maxOf(progressPercent, stageProgress).coerceIn(0.04f, 0.99f)

    Box(Modifier.fillMaxSize().background(SenpPageBackdrop)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("<", color = SenpCream, fontSize = 25.sp, modifier = Modifier.size(40.dp).clickable { onBack() })
                Text("PIPELINE", color = SenpBlueBright, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Spacer(Modifier.weight(1f))
                Text("LOCAL", color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text("ANALYSING", color = SenpCream, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(statusMessage, color = SenpMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(32.dp))

            Box(
                Modifier.fillMaxWidth().height(286.dp).clip(RoundedCornerShape(20.dp)).background(SenpBackgroundRaised),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(246.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension * 0.37f
                    drawCircle(SenpViolet.copy(alpha = 0.08f * pulse), radius * 1.48f, center)
                    drawCircle(SenpSurfaceRaised, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    drawCircle(SenpSurfaceRaised, radius * 0.68f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                    drawLine(SenpSurfaceRaised, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
                    drawLine(SenpSurfaceRaised, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)
                    rotate(rotation, center) {
                        drawLine(SenpBlueBright.copy(alpha = 0.04f), center, Offset(center.x + radius, center.y), radius * 0.9f, StrokeCap.Round)
                        drawArc(
                            color = SenpBlueBright,
                            startAngle = -28f,
                            sweepAngle = 58f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(7f),
                        )
                    }
                    val scanY = center.y - radius + (radius * 2f * scan)
                    drawLine(SenpBlueBright.copy(alpha = 0.6f), Offset(center.x - radius * 0.84f, scanY), Offset(center.x + radius * 0.84f, scanY), 2f)
                    drawCircle(SenpCream, 5f, center)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(progress * 100).toInt()}%", color = SenpCream, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("PROCESSING", color = SenpBlueBright, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(SenpSurfaceRaised)) {
                Box(Modifier.fillMaxWidth(progress).height(5.dp).background(SenpBlueBright))
            }
            Spacer(Modifier.height(22.dp))
            StageRail(activeStage)
        }
    }
}

@Composable
private fun StageRail(activeStage: PipelineStageId) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SenpSurface).padding(15.dp)) {
        Text("ANALYSIS PIPELINE", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(13.dp))
        listOf(
            PipelineStageId.VIDEO_POSE_SOURCE to "YOUR VIDEO",
            PipelineStageId.VIDEO_POSE_REFERENCE to "REFERENCE",
            PipelineStageId.ALIGNMENT to "EXTRACTIONS",
        ).forEachIndexed { index, (stage, label) ->
            val complete = progressForStage(activeStage) >= progressForStage(stage)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (complete) SenpBlueBright else SenpSurfaceRaised))
                Text(label, color = if (complete) SenpCream else SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                Text(if (complete) "READY" else "WAIT", color = if (complete) SenpBlueBright else SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            if (index < 2) Spacer(Modifier.height(11.dp))
        }
    }
}

private fun progressForStage(stage: PipelineStageId): Float = when (stage) {
    PipelineStageId.VALIDATION -> 0.10f
    PipelineStageId.CACHE_READ -> 0.18f
    PipelineStageId.VIDEO_POSE_SOURCE -> 0.34f
    PipelineStageId.MOTION_SOURCE -> 0.48f
    PipelineStageId.PHASE_SOURCE -> 0.58f
    PipelineStageId.VIDEO_POSE_REFERENCE -> 0.72f
    PipelineStageId.MOTION_REFERENCE -> 0.81f
    PipelineStageId.PHASE_REFERENCE -> 0.88f
    PipelineStageId.ALIGNMENT -> 0.95f
    PipelineStageId.CACHE_WRITE -> 0.98f
}

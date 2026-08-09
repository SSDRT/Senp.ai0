package ai.senp.validation.ui.screens

import ai.senp.core.contracts.PipelineStageId
import ai.senp.validation.ui.theme.SenpBackgroundRaised
import ai.senp.validation.ui.theme.SenpBorder
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpPageBackdrop
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

/** A deliberately quiet progress state; the simulated ramp keeps feedback visible while the local pipeline works. */
@Composable
fun AnalysisProgressScreen(
    activeStage: PipelineStageId,
    progressPercent: Float,
    statusMessage: String,
    onBack: () -> Unit,
) {
    var simulatedPercent by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (simulatedPercent < 99f) {
            delay(55L)
            simulatedPercent = (simulatedPercent + when {
                simulatedPercent < 65f -> 0.72f
                simulatedPercent < 88f -> 0.34f
                else -> 0.12f
            }).coerceAtMost(99f)
        }
    }
    val backendPercent = progressPercent.coerceIn(0f, 1f) * 100f
    val visiblePercent = if (backendPercent >= 99f) 100f else max(simulatedPercent, backendPercent)
    val pulseTransition = rememberInfiniteTransition(label = "loadingPulse")
    val pulse by pulseTransition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "pulse")

    Column(
        Modifier.fillMaxSize().background(SenpPageBackdrop).padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("<", color = SenpCream, fontSize = 25.sp, modifier = Modifier.size(40.dp).clickable { onBack() })
            Text("ANALYSIS", color = SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.height(36.dp))
        Text("ANALYSING", color = SenpCream, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(statusMessage, color = SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SenpBorder))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text("${visiblePercent.toInt()}%", color = SenpCream, fontSize = 56.sp, fontWeight = FontWeight.Light)
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(SenpCream.copy(alpha = pulse)))
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SenpSurfaceRaised)) {
            Box(Modifier.fillMaxWidth((visiblePercent / 100f).coerceIn(0f, 1f)).height(6.dp).background(SenpCream))
        }
        Spacer(Modifier.height(28.dp))
        PipelineList(activeStage)
        Spacer(Modifier.weight(1f))
        Text("RUNNING LOCALLY", color = SenpMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
    }
}

@Composable
private fun PipelineList(activeStage: PipelineStageId) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SenpSurface).padding(14.dp)) {
        listOf(
            PipelineStageId.VIDEO_POSE_SOURCE to "YOUR VIDEO",
            PipelineStageId.VIDEO_POSE_REFERENCE to "MASTER VIDEO",
            PipelineStageId.ALIGNMENT to "COMPARISON",
        ).forEachIndexed { index, (stage, label) ->
            val complete = progressForStage(activeStage) >= progressForStage(stage)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (complete) SenpCream else SenpBackgroundRaised))
                Text(label, color = if (complete) SenpCream else SenpMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                Text(if (complete) "DONE" else "WAIT", color = if (complete) SenpCream else SenpMuted, fontSize = 9.sp)
            }
            if (index < 2) Spacer(Modifier.height(12.dp))
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

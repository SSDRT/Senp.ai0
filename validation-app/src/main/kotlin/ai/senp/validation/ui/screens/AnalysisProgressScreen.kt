package ai.senp.validation.ui.screens

import ai.senp.core.contracts.PipelineStageId
import ai.senp.validation.ui.theme.SenpBackground
import ai.senp.validation.ui.theme.SenpBlue
import ai.senp.validation.ui.theme.SenpBlueBright
import ai.senp.validation.ui.theme.SenpCream
import ai.senp.validation.ui.theme.SenpMuted
import ai.senp.validation.ui.theme.SenpSurface
import ai.senp.validation.ui.theme.SenpSurfaceRaised
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnalysisProgressScreen(
    activeStage: PipelineStageId,
    progressPercent: Float,
    statusMessage: String,
) {
    val transition = rememberInfiniteTransition(label = "analysisPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse",
    )
    val activeProgress = progressForStage(activeStage)
    val progress = maxOf(progressPercent, activeProgress).coerceIn(0.05f, 0.98f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF06172F), SenpBackground, Color(0xFF100D25)))),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(SenpBlue.copy(alpha = 0.09f * pulse), radius = size.minDimension * 0.58f, center = center.copy(y = size.height * 0.28f))
            drawCircle(Color(0xFF6C4CF0).copy(alpha = 0.06f * pulse), radius = size.minDimension * 0.42f, center = center.copy(y = size.height * 0.86f))
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Senp.ai", color = SenpCream, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.6).sp)
            Text("MOTION AWAKENING", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(50.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(174.dp).clip(CircleShape).background(SenpBlue.copy(alpha = 0.08f * pulse)))
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(138.dp),
                    color = SenpBlueBright,
                    trackColor = SenpSurfaceRaised,
                    strokeWidth = 7.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(progress * 100).toInt()}%", color = SenpCream, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("ANALYSING", color = SenpMuted, fontSize = 10.sp, letterSpacing = 1.8.sp)
                }
            }
            Spacer(Modifier.height(34.dp))
            Text("Reading your movement", color = SenpCream, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(statusMessage, color = SenpMuted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                color = SenpBlue,
                trackColor = SenpSurfaceRaised,
            )
            Spacer(Modifier.height(28.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SenpSurface.copy(alpha = 0.86f)),
            ) {
                Column(Modifier.padding(17.dp)) {
                    Text("LOCAL ANALYSIS PIPELINE", color = SenpBlueBright, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        PipelineStageId.VIDEO_POSE_SOURCE to "Mapping your landmarks",
                        PipelineStageId.VIDEO_POSE_REFERENCE to "Mapping the master form",
                        PipelineStageId.ALIGNMENT to "Aligning both movements",
                    ).forEach { (stage, label) ->
                        StageRow(label, stage == activeStage || activeProgress > progressForStage(stage))
                        Spacer(Modifier.height(9.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StageRow(label: String, complete: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (complete) SenpBlueBright else SenpSurfaceRaised))
        Text(label, color = if (complete) SenpCream else SenpMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        Text(if (complete) "✓" else "…", color = if (complete) SenpBlueBright else SenpMuted, fontSize = 13.sp)
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

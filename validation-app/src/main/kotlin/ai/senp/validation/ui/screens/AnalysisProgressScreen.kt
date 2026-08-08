package ai.senp.validation.ui.screens

import ai.senp.core.contracts.PipelineStageId
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnalysisProgressScreen(
    activeStage: PipelineStageId,
    statusMessage: String,
) {
    val progress = when (activeStage) {
        PipelineStageId.VALIDATION -> 0.1f
        PipelineStageId.CACHE_READ -> 0.2f
        PipelineStageId.VIDEO_POSE_SOURCE -> 0.35f
        PipelineStageId.MOTION_SOURCE -> 0.5f
        PipelineStageId.PHASE_SOURCE -> 0.6f
        PipelineStageId.VIDEO_POSE_REFERENCE -> 0.75f
        PipelineStageId.MOTION_REFERENCE -> 0.85f
        PipelineStageId.PHASE_REFERENCE -> 0.9f
        PipelineStageId.ALIGNMENT -> 0.95f
        PipelineStageId.CACHE_WRITE -> 1.0f
    }

    val animatedProgress by animateFloatAsState(targetValue = progress, label = "stageProgress")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(120.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 8.dp,
            )
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Analyzing Motion Pipeline",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Stage: ${activeStage.name}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = statusMessage,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

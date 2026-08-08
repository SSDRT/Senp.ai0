package ai.senp.validation.ui.components

import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.CacheStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(
    result: AnalysisResult,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Engine Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Cache status badge
                val isHit = result.provenance.cacheStatus == CacheStatus.HIT
                val badgeBg = if (isHit) Color(0xFF00E5FF) else Color(0xFF76FF03)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "CACHE ${result.provenance.cacheStatus.name}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Diagnostic Summary Cards
            Text(
                text = "Request ID: ${result.requestId}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Engine Version: ${result.provenance.servingEngineVersion}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Media & Frame Diagnostics",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Candidate Video", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Frames: ${result.payload.sourceFrameCount}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Duration: ${result.payload.sourceDuration.value} ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    Text("Reference Video", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Frames: ${result.payload.referenceFrameCount}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Duration: ${result.payload.referenceDuration.value} ms", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Stage Latencies",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            var totalTimeMs = 0L
            result.timings.forEach { timing ->
                totalTimeMs += timing.durationMs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = timing.stage.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${timing.durationMs} ms",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Total Latency", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("$totalTimeMs ms", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Problem Bounds: ${result.payload.problems.size} detected",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = if (result.payload.problems.isEmpty()) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            result.payload.problems.forEach { prob ->
                Text(
                    text = "• [${prob.sourceStart.value}ms - ${prob.sourceEndExclusive.value}ms] ${prob.label} (${prob.certainty.name})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

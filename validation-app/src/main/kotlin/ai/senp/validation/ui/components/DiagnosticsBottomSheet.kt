package ai.senp.validation.ui.components

import ai.senp.core.contracts.MotionUnitCorrespondence
import ai.senp.core.contracts.SynchronizationStatus
import ai.senp.sync.v2.VideoSynchronizationRun
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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsBottomSheet(
    run: VideoSynchronizationRun,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val result = run.synchronization.result
    val matchedUnits = result.correspondences.filterIsInstance<MotionUnitCorrespondence.MatchedUnit>()
    val sourceUnmatched = result.correspondences.filterIsInstance<MotionUnitCorrespondence.SourceUnmatchedUnit>()
    val referenceUnmatched = result.correspondences.filterIsInstance<MotionUnitCorrespondence.ReferenceUnmatchedUnit>()
    val mappedTimestamps = matchedUnits.sumOf { unit -> unit.timeline.count { it is ai.senp.core.contracts.TimestampCorrespondence.Matched } }
    val statusColor = when (result.status) {
        SynchronizationStatus.SYNCHRONIZED -> Color(0xFF76FF03)
        SynchronizationStatus.PARTIAL -> Color(0xFFFFC857)
        SynchronizationStatus.REFUSED -> Color(0xFFFF5D73)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Sync-v2 diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(result.status.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            }

            Spacer(Modifier.height(16.dp))
            DiagnosticHeading("Confidence & coverage")
            DiagnosticRow("Overall", percent(result.diagnostics.overallConfidence))
            DiagnosticRow("Spatial", percent(result.diagnostics.spatialConfidence))
            DiagnosticRow("Temporal", percent(result.diagnostics.temporalConfidence))
            DiagnosticRow("Correspondence", percent(result.diagnostics.correspondenceConfidence))
            DiagnosticRow("Source analyzable", percent(result.diagnostics.sourceAnalyzableFraction))
            DiagnosticRow("Reference analyzable", percent(result.diagnostics.referenceAnalyzableFraction))
            DiagnosticRow("Ambiguity", percent(result.diagnostics.correspondenceAmbiguity))

            Spacer(Modifier.height(16.dp))
            DiagnosticHeading("Correspondence decisions")
            DiagnosticRow("Matched units", matchedUnits.size.toString())
            DiagnosticRow("Source unmatched units", sourceUnmatched.size.toString())
            DiagnosticRow("Reference unmatched units", referenceUnmatched.size.toString())
            DiagnosticRow("Mapped timestamps", mappedTimestamps.toString())
            DiagnosticRow("Source structure", result.sourceTemporalStructure.classification.name)
            DiagnosticRow("Reference structure", result.referenceTemporalStructure.classification.name)

            result.refusal?.let { refusal ->
                Spacer(Modifier.height(16.dp))
                DiagnosticHeading("Refusal")
                Text(refusal.reason.name, fontWeight = FontWeight.Bold, color = statusColor, fontSize = 13.sp)
                Text(
                    refusal.message,
                    modifier = Modifier.padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            DiagnosticHeading("Media & pose")
            DiagnosticRow("Source frames", run.sourcePoseExtraction.diagnostics.sampledFrameCount.toString())
            DiagnosticRow("Reference frames", run.referencePoseExtraction.diagnostics.sampledFrameCount.toString())
            DiagnosticRow("Source detections", run.sourcePoseExtraction.diagnostics.detectedFrameCount.toString())
            DiagnosticRow("Reference detections", run.referencePoseExtraction.diagnostics.detectedFrameCount.toString())
            DiagnosticRow("Source no-person", run.sourcePoseExtraction.diagnostics.noPersonFrameCount.toString())
            DiagnosticRow("Reference no-person", run.referencePoseExtraction.diagnostics.noPersonFrameCount.toString())
            DiagnosticRow("Source duration", "${run.sourcePoseExtraction.duration.value} ms")
            DiagnosticRow("Reference duration", "${run.referencePoseExtraction.duration.value} ms")

            Spacer(Modifier.height(16.dp))
            DiagnosticHeading("Timing")
            DiagnosticRow("Source pose", ms(run.timings.sourcePoseExtractionNanos))
            DiagnosticRow("Reference pose", ms(run.timings.referencePoseExtractionNanos))
            DiagnosticRow("Post-pose Sync-v2", ms(run.timings.postPoseSynchronizationNanos))
            DiagnosticRow("Total", ms(run.timings.totalNanos))
            DiagnosticRow("Post-pose fraction", percent(run.timings.postPoseFraction))
            DiagnosticRow("Source pose cache", if (run.timings.sourcePoseCacheHit) "HIT" else "MISS")
            DiagnosticRow("Reference pose cache", if (run.timings.referencePoseCacheHit) "HIT" else "MISS")

            Spacer(Modifier.height(16.dp))
            DiagnosticHeading("Spatial hypothesis")
            DiagnosticRow("View hypotheses", result.spatialDiagnostics.relativeViewHypotheses.size.toString())
            DiagnosticRow("Reliability segments", result.spatialDiagnostics.reliabilitySegments.size.toString())
            DiagnosticRow("Confidence", percent(result.spatialDiagnostics.aggregateConfidence))
            DiagnosticRow(
                "Reliability",
                result.spatialDiagnostics.reliabilitySegments.map { it.status.name }.distinct().joinToString().ifBlank { "NONE" },
            )
            DiagnosticRow(
                "Mirror hypotheses",
                result.spatialDiagnostics.relativeViewHypotheses.map { it.mirror.name }.distinct().joinToString().ifBlank { "NONE" },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiagnosticHeading(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value * 100.0)
private fun ms(nanos: Long): String = String.format(Locale.US, "%.1f ms", nanos / 1_000_000.0)

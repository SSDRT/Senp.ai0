package ai.senp.validation.ui.state

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageTiming
import android.net.Uri

data class VideoSelectionState(
    val sourceUri: Uri? = null,
    val referenceUri: Uri? = null,
    val sourceSha256: Sha256? = null,
    val referenceSha256: Sha256? = null,
    val isCalculatingHash: Boolean = false,
    val errorMessage: String? = null,
) {
    val isReadyForAnalysis: Boolean
        get() = sourceUri != null && referenceUri != null && sourceSha256 != null && referenceSha256 != null
}

data class ConfigurationState(
    val targetFps: Int = 15,
    val longEdgeCapPx: Int = 640,
    val modelVariant: String = "pose-landmarker-full",
    val minimumConfidence: Double = 0.5,
    val exerciseProfileId: String = "generic",
)

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState

    data class Analyzing(
        val activeStage: PipelineStageId,
        val progressPercent: Float,
        val statusMessage: String,
    ) : AnalysisUiState

    data class Success(
        val result: AnalysisResult,
        val sourcePoses: PoseSequence?,
        val referencePoses: PoseSequence?,
        val sourceUri: Uri,
        val referenceUri: Uri,
    ) : AnalysisUiState

    data class Failure(
        val failure: AnalysisFailure,
        val timings: List<StageTiming>,
    ) : AnalysisUiState
}

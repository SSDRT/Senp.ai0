package ai.senp.validation.ui.state

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageTiming
import ai.senp.core.contracts.VideoPoseExtraction
import ai.senp.motion.ActionProfile
import ai.senp.motion.ActionRecognitionResult
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.sync.v2.VideoSynchronizationRun
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
)

sealed interface ReferenceProfileUiState {
    data object Empty : ReferenceProfileUiState
    data class Preparing(val message: String = "Preparing reference action…") : ReferenceProfileUiState
    data class Ready(
        val profile: ActionProfile,
        val referenceSha256: Sha256,
        val analysisFramesPerSecond: Double,
    ) : ReferenceProfileUiState
    data class Rejected(val message: String) : ReferenceProfileUiState
}

data class ReferenceActionAnalysisUi(
    val profile: ActionProfile,
    val recognition: ActionRecognitionResult,
    val deviations: List<ReferenceDeviationMeasurement>,
)

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState

    data class Analyzing(
        val activeStage: PipelineStageId,
        val progressPercent: Float,
        val statusMessage: String,
    ) : AnalysisUiState

    data class Success(
        val sourceUri: Uri,
        val referenceUri: Uri,
        val sourcePoseExtraction: VideoPoseExtraction,
        val referencePoseExtraction: VideoPoseExtraction,
        val synchronizationRun: VideoSynchronizationRun? = null,
        val synchronizationFailure: AnalysisFailure? = null,
        val referenceAction: ReferenceActionAnalysisUi? = null,
        val referenceActionMessage: String? = null,
    ) : AnalysisUiState {
        init {
            require(synchronizationRun == null || synchronizationFailure == null) {
                "synchronization run and failure cannot both be present"
            }
        }
    }

    data class Failure(
        val failure: AnalysisFailure,
        val timings: List<StageTiming> = emptyList(),
    ) : AnalysisUiState
}

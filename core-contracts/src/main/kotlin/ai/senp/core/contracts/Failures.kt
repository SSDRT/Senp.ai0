package ai.senp.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AnalysisFailure {
    val stage: PipelineStageId
    val message: String

    @Serializable @SerialName("invalid_request")
    data class InvalidRequest(override val message: String) : AnalysisFailure { override val stage = PipelineStageId.VALIDATION }

    @Serializable @SerialName("video_pose")
    data class VideoPose(val role: VideoRole, val kind: VideoPoseFailureKind, override val message: String) : AnalysisFailure {
        override val stage = if (role == VideoRole.SOURCE) PipelineStageId.VIDEO_POSE_SOURCE else PipelineStageId.VIDEO_POSE_REFERENCE
    }

    @Serializable @SerialName("motion")
    data class Motion(val role: VideoRole, override val message: String) : AnalysisFailure {
        override val stage = if (role == VideoRole.SOURCE) PipelineStageId.MOTION_SOURCE else PipelineStageId.MOTION_REFERENCE
    }

    @Serializable @SerialName("phase")
    data class Phase(val role: VideoRole, override val message: String) : AnalysisFailure {
        override val stage = if (role == VideoRole.SOURCE) PipelineStageId.PHASE_SOURCE else PipelineStageId.PHASE_REFERENCE
    }

    @Serializable @SerialName("alignment")
    data class Alignment(override val message: String) : AnalysisFailure { override val stage = PipelineStageId.ALIGNMENT }

    @Serializable @SerialName("cache")
    data class Cache(val operation: CacheOperation, override val message: String) : AnalysisFailure {
        override val stage = if (operation == CacheOperation.READ) PipelineStageId.CACHE_READ else PipelineStageId.CACHE_WRITE
    }

    @Serializable @SerialName("cancelled")
    data class Cancelled(override val stage: PipelineStageId, override val message: String = "Analysis cancelled") : AnalysisFailure

    @Serializable @SerialName("unexpected")
    data class Unexpected(override val stage: PipelineStageId, val exceptionType: String, override val message: String) : AnalysisFailure
}

@Serializable
enum class VideoPoseFailureKind { SOURCE_MISSING, UNSUPPORTED_VIDEO, CORRUPT_VIDEO, CODEC, TIMEOUT, MODEL_LOAD, INFERENCE, NON_MONOTONIC_TIMESTAMP, CANCELLED }
@Serializable enum class CacheOperation { READ, WRITE }
sealed interface StageResult<out T> { data class Success<T>(val value:T):StageResult<T>; data class Failure(val failure:AnalysisFailure):StageResult<Nothing> }
sealed interface AnalysisOutcome { data class Success(val result:AnalysisResult):AnalysisOutcome; data class Failure(val failure:AnalysisFailure,val timings:List<StageTiming>):AnalysisOutcome }

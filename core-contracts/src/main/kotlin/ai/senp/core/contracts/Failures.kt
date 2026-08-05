package ai.senp.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface AnalysisFailure {
    val stage: PipelineStageId
    val message: String

    @Serializable
    @SerialName("invalid_request")
    data class InvalidRequest(
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = PipelineStageId.VALIDATION
    }

    @Serializable
    @SerialName("decode")
    data class Decode(
        val role: VideoRole,
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = when (role) {
            VideoRole.SOURCE -> PipelineStageId.DECODE_SOURCE
            VideoRole.REFERENCE -> PipelineStageId.DECODE_REFERENCE
        }
    }

    @Serializable
    @SerialName("pose")
    data class Pose(
        val role: VideoRole,
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = when (role) {
            VideoRole.SOURCE -> PipelineStageId.POSE_SOURCE
            VideoRole.REFERENCE -> PipelineStageId.POSE_REFERENCE
        }
    }

    @Serializable
    @SerialName("motion")
    data class Motion(
        val role: VideoRole,
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = when (role) {
            VideoRole.SOURCE -> PipelineStageId.MOTION_SOURCE
            VideoRole.REFERENCE -> PipelineStageId.MOTION_REFERENCE
        }
    }

    @Serializable
    @SerialName("phase")
    data class Phase(
        val role: VideoRole,
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = when (role) {
            VideoRole.SOURCE -> PipelineStageId.PHASE_SOURCE
            VideoRole.REFERENCE -> PipelineStageId.PHASE_REFERENCE
        }
    }

    @Serializable
    @SerialName("alignment")
    data class Alignment(
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = PipelineStageId.ALIGNMENT
    }

    @Serializable
    @SerialName("cache")
    data class Cache(
        val operation: CacheOperation,
        override val message: String,
    ) : AnalysisFailure {
        override val stage: PipelineStageId = when (operation) {
            CacheOperation.READ -> PipelineStageId.CACHE_READ
            CacheOperation.WRITE -> PipelineStageId.CACHE_WRITE
        }
    }

    @Serializable
    @SerialName("cancelled")
    data class Cancelled(
        override val stage: PipelineStageId,
        override val message: String = "Analysis cancelled",
    ) : AnalysisFailure

    @Serializable
    @SerialName("unexpected")
    data class Unexpected(
        override val stage: PipelineStageId,
        val exceptionType: String,
        override val message: String,
    ) : AnalysisFailure
}

@Serializable
enum class CacheOperation {
    READ,
    WRITE,
}

sealed interface StageResult<out T> {
    data class Success<T>(val value: T) : StageResult<T>
    data class Failure(val failure: AnalysisFailure) : StageResult<Nothing>
}

sealed interface AnalysisOutcome {
    data class Success(val result: AnalysisResult) : AnalysisOutcome
    data class Failure(
        val failure: AnalysisFailure,
        val timings: List<StageTiming>,
    ) : AnalysisOutcome
}

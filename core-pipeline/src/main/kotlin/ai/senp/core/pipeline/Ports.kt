package ai.senp.core.pipeline

import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.CacheKey
import ai.senp.core.contracts.CacheLookup
import ai.senp.core.contracts.CachedAnalysis
import ai.senp.core.contracts.DecodedVideo
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.ProblemWindow

interface VideoDecoder {
    suspend fun decode(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
    ): StageResult<DecodedVideo>
}

interface PoseEstimator {
    suspend fun estimate(
        role: VideoRole,
        video: DecodedVideo,
        configuration: PoseModelConfiguration,
    ): StageResult<PoseSequence>
}

interface MotionProcessor {
    suspend fun process(
        poses: PoseSequence,
        normalizationVersion: String,
        exerciseProfileVersion: String,
    ): StageResult<MotionSeries>
}

interface PhaseDetector {
    suspend fun detect(
        motion: MotionSeries,
        exerciseProfileVersion: String,
    ): StageResult<PhaseSeries>
}

interface AlignmentEngine {
    suspend fun align(
        sourceMotion: MotionSeries,
        sourcePhases: PhaseSeries,
        referenceMotion: MotionSeries,
        referencePhases: PhaseSeries,
        configuration: AnalysisConfiguration,
    ): StageResult<AlignmentAnalysis>
}

interface AnalysisCache {
    suspend fun lookup(key: CacheKey): StageResult<CacheLookup>
    suspend fun store(key: CacheKey, analysis: CachedAnalysis): StageResult<Unit>
}

interface PipelineStage<I, O> {
    val id: PipelineStageId
    suspend fun execute(input: I): StageResult<O>
}

data class AlignmentAnalysis(
    val alignment: AlignmentResult,
    val problems: List<ProblemWindow>,
)

fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

fun interface WallClock {
    fun nowEpochMs(): TimestampMs
}

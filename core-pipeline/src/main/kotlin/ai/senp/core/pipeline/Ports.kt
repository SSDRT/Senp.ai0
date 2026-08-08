package ai.senp.core.pipeline

import ai.senp.core.contracts.*

/** Adapter must decode sequentially and release/reuse each pixel buffer before accepting the next frame. */
interface VideoPoseExtractor {
    suspend fun extract(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
        model: PoseModelConfiguration,
    ): StageResult<VideoPoseExtraction>
}

interface MotionProcessor {
    suspend fun process(poses: PoseSequence, normalizationVersion: String, exerciseProfileVersion: String): StageResult<MotionSeries>
}
interface PhaseDetector { suspend fun detect(motion: MotionSeries, exerciseProfileVersion: String): StageResult<PhaseSeries> }
interface AlignmentEngine {
    suspend fun align(sourceMotion:MotionSeries, sourcePhases:PhaseSeries, referenceMotion:MotionSeries, referencePhases:PhaseSeries, configuration:AnalysisConfiguration):StageResult<AlignmentAnalysis>
}
interface AnalysisCache { suspend fun lookup(key:CacheKey):StageResult<CacheLookup>; suspend fun store(key:CacheKey,analysis:CachedAnalysis):StageResult<Unit> }
interface PipelineStage<I,O> { val id:PipelineStageId; suspend fun execute(input:I):StageResult<O> }
data class AlignmentAnalysis(val alignment:AlignmentResult,val problems:List<ProblemWindow>)
fun interface MonotonicClock { fun elapsedRealtimeMs():Long }
fun interface WallClock { fun nowEpochMs():TimestampMs }

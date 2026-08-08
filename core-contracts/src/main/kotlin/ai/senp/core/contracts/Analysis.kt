package ai.senp.core.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class PipelineStageId {
    VALIDATION, CACHE_READ, VIDEO_POSE_SOURCE, MOTION_SOURCE, PHASE_SOURCE,
    VIDEO_POSE_REFERENCE, MOTION_REFERENCE, PHASE_REFERENCE, ALIGNMENT, CACHE_WRITE,
}

@Serializable
data class StageTiming(val stage: PipelineStageId, val startedAtElapsedRealtimeMs: Long, val durationMs: Long) {
    init { require(startedAtElapsedRealtimeMs >= 0); require(durationMs >= 0) }
}

@Serializable
data class AnalysisPayload(
    val sourceDuration: DurationMs,
    val referenceDuration: DurationMs,
    val sourceFrameCount: Int,
    val referenceFrameCount: Int,
    val sourceVideoPoseDiagnostics: VideoPoseDiagnostics,
    val referenceVideoPoseDiagnostics: VideoPoseDiagnostics,
    val alignment: AlignmentResult,
    val problems: List<ProblemWindow>,
) {
    init { require(sourceFrameCount > 0); require(referenceFrameCount > 0) }
}

@Serializable
data class CachedAnalysis(val payload: AnalysisPayload, val computedAtEpochMs: TimestampMs, val producerEngineVersion: String) {
    init { requireVersion(producerEngineVersion, "producer engine version") }
}

@Serializable
enum class CacheStatus { HIT, MISS }

@Serializable
data class AnalysisProvenance(
    val cacheKey: CacheKey, val cacheKeyStableId: Sha256, val cacheStatus: CacheStatus,
    val computedAtEpochMs: TimestampMs, val servedAtEpochMs: TimestampMs,
    val producerEngineVersion: String, val servingEngineVersion: String,
) {
    init {
        require(cacheKeyStableId == cacheKey.stableId())
        requireVersion(producerEngineVersion, "producer engine version")
        requireVersion(servingEngineVersion, "serving engine version")
    }
}

@Serializable
data class AnalysisResult(
    val resultSchemaVersion: Int = CURRENT_SCHEMA_VERSION, val requestId: String,
    val requestedAtEpochMs: TimestampMs, val payload: AnalysisPayload,
    val timings: List<StageTiming>, val provenance: AnalysisProvenance,
) {
    init { require(resultSchemaVersion > 0); require(requestId.isNotBlank()) }
    companion object { const val CURRENT_SCHEMA_VERSION = 2 }
}

sealed interface CacheLookup { data object Miss : CacheLookup; data class Hit(val analysis: CachedAnalysis) : CacheLookup }

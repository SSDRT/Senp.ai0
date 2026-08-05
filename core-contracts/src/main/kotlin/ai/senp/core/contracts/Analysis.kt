package ai.senp.core.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class PipelineStageId {
    VALIDATION,
    CACHE_READ,
    DECODE_SOURCE,
    POSE_SOURCE,
    MOTION_SOURCE,
    PHASE_SOURCE,
    DECODE_REFERENCE,
    POSE_REFERENCE,
    MOTION_REFERENCE,
    PHASE_REFERENCE,
    ALIGNMENT,
    CACHE_WRITE,
}

@Serializable
data class StageTiming(
    val stage: PipelineStageId,
    val startedAtElapsedRealtimeMs: Long,
    val durationMs: Long,
) {
    init {
        require(startedAtElapsedRealtimeMs >= 0) { "monotonic start must be non-negative" }
        require(durationMs >= 0) { "stage duration must be non-negative" }
    }
}

@Serializable
data class AnalysisPayload(
    val sourceDuration: DurationMs,
    val referenceDuration: DurationMs,
    val sourceFrameCount: Int,
    val referenceFrameCount: Int,
    val alignment: AlignmentResult,
    val problems: List<ProblemWindow>,
) {
    init {
        require(sourceFrameCount > 0) { "source frame count must be positive" }
        require(referenceFrameCount > 0) { "reference frame count must be positive" }
    }
}

@Serializable
data class CachedAnalysis(
    val payload: AnalysisPayload,
    val computedAtEpochMs: TimestampMs,
    val producerEngineVersion: String,
) {
    init {
        requireVersion(producerEngineVersion, "producer engine version")
    }
}

@Serializable
enum class CacheStatus {
    HIT,
    MISS,
}

@Serializable
data class AnalysisProvenance(
    val cacheKey: CacheKey,
    val cacheKeyStableId: Sha256,
    val cacheStatus: CacheStatus,
    val computedAtEpochMs: TimestampMs,
    val servedAtEpochMs: TimestampMs,
    val producerEngineVersion: String,
    val servingEngineVersion: String,
) {
    init {
        require(cacheKeyStableId == cacheKey.stableId()) { "cache-key stable ID does not match the cache key" }
        requireVersion(producerEngineVersion, "producer engine version")
        requireVersion(servingEngineVersion, "serving engine version")
    }
}

@Serializable
data class AnalysisResult(
    val resultSchemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val requestId: String,
    val requestedAtEpochMs: TimestampMs,
    val payload: AnalysisPayload,
    val timings: List<StageTiming>,
    val provenance: AnalysisProvenance,
) {
    init {
        require(resultSchemaVersion > 0) { "result schema version must be positive" }
        require(requestId.isNotBlank()) { "request ID must not be blank" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

sealed interface CacheLookup {
    data object Miss : CacheLookup
    data class Hit(val analysis: CachedAnalysis) : CacheLookup
}

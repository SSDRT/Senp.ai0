package ai.senp.core.cache

import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.AnalysisPayload
import ai.senp.core.contracts.CacheKey
import ai.senp.core.contracts.CacheLookup
import ai.senp.core.contracts.CachedAnalysis
import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.PoseThresholds
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame

class InMemoryAnalysisCacheTest {
    @Test
    fun `store and lookup detach mutable collection implementations`() = runBlocking {
        val cache = InMemoryAnalysisCache()
        val key = key('a')
        assertIs<CacheLookup.Miss>((cache.lookup(key) as StageResult.Success).value)

        val callerOwnedPoints = mutableListOf(
            AlignmentPoint(TimestampMs(0), TimestampMs(0), localCost = 0.0, confidence = 1.0),
            AlignmentPoint(TimestampMs(1), TimestampMs(1), localCost = 0.0, confidence = 1.0),
        )
        val analysis = cachedAnalysis(callerOwnedPoints)
        cache.store(key, analysis)
        callerOwnedPoints.clear()

        val firstHit = assertIs<CacheLookup.Hit>((cache.lookup(key) as StageResult.Success).value)
        assertNotSame(analysis, firstHit.analysis)
        assertEquals(2, firstHit.analysis.payload.alignment.points.size)

        @Suppress("UNCHECKED_CAST")
        (firstHit.analysis.payload.alignment.points as MutableList<AlignmentPoint>).clear()
        val secondHit = assertIs<CacheLookup.Hit>((cache.lookup(key) as StageResult.Success).value)
        assertEquals(2, secondHit.analysis.payload.alignment.points.size)
        assertEquals(1, cache.size())
    }

    @Test
    fun `least recently used entry is evicted at configured bound`() = runBlocking {
        val cache = InMemoryAnalysisCache(maximumEntries = 2)
        val first = key('a')
        val second = key('b')
        val third = key('c')

        cache.store(first, cachedAnalysis())
        cache.store(second, cachedAnalysis())
        assertIs<CacheLookup.Hit>((cache.lookup(first) as StageResult.Success).value)
        cache.store(third, cachedAnalysis())

        assertIs<CacheLookup.Miss>((cache.lookup(second) as StageResult.Success).value)
        assertIs<CacheLookup.Hit>((cache.lookup(first) as StageResult.Success).value)
        assertIs<CacheLookup.Hit>((cache.lookup(third) as StageResult.Success).value)
        assertEquals(2, cache.size())
    }

    private fun key(seed: Char): CacheKey = CacheKey(
        sourceSha256 = Sha256(seed.toString().repeat(64)),
        referenceSha256 = Sha256("d".repeat(64)),
        modelSha256 = Sha256("e".repeat(64)),
        modelVariant = "full",
        poseThresholds = PoseThresholds(),
        pipelineVersion = "pipeline-v1",
        sampling = SamplingConfiguration(),
        normalizationVersion = "normalization-v1",
        exerciseProfileVersion = "profile-v1",
    )

    private fun cachedAnalysis(
        points: List<AlignmentPoint> = listOf(
            AlignmentPoint(TimestampMs(0), TimestampMs(0), localCost = 0.0, confidence = 1.0),
        ),
    ): CachedAnalysis = CachedAnalysis(
        payload = AnalysisPayload(
            sourceDuration = DurationMs(100),
            referenceDuration = DurationMs(100),
            sourceFrameCount = 2,
            referenceFrameCount = 2,
            alignment = AlignmentResult(
                mode = "test",
                points = points,
                aggregateConfidence = 1.0,
            ),
            problems = emptyList(),
        ),
        computedAtEpochMs = TimestampMs(10),
        producerEngineVersion = "test-engine",
    )
}

package ai.senp.headless

import ai.senp.core.cache.InMemoryAnalysisCache
import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.CacheStatus
import ai.senp.core.contracts.DecodedVideo
import ai.senp.core.contracts.PipelineStageId
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.pipeline.VideoDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisPipelineTest {
    @Test
    fun `end to end success emits deterministic timestamp-first result`() = runBlocking {
        val outcome = samplePipeline(InMemoryAnalysisCache()).analyze(sampleRequest())
        val success = assertIs<AnalysisOutcome.Success>(outcome)

        assertEquals("headless-demo-001", success.result.requestId)
        assertEquals(4, success.result.payload.sourceFrameCount)
        assertEquals(4, success.result.payload.referenceFrameCount)
        assertEquals(267, success.result.payload.sourceDuration.value)
        assertEquals(CacheStatus.MISS, success.result.provenance.cacheStatus)
        assertEquals(0.94, success.result.payload.alignment.aggregateConfidence)
        assertEquals(1, success.result.payload.problems.size)
        assertEquals(
            listOf(
                PipelineStageId.VALIDATION,
                PipelineStageId.CACHE_READ,
                PipelineStageId.DECODE_SOURCE,
                PipelineStageId.POSE_SOURCE,
                PipelineStageId.MOTION_SOURCE,
                PipelineStageId.PHASE_SOURCE,
                PipelineStageId.DECODE_REFERENCE,
                PipelineStageId.POSE_REFERENCE,
                PipelineStageId.MOTION_REFERENCE,
                PipelineStageId.PHASE_REFERENCE,
                PipelineStageId.ALIGNMENT,
                PipelineStageId.CACHE_WRITE,
            ),
            success.result.timings.map { it.stage },
        )
        assertTrue(success.result.timings.all { it.durationMs == 1L })
    }

    @Test
    fun `typed stage failure is preserved with completed timings`() = runBlocking {
        val outcome = samplePipeline(
            cache = InMemoryAnalysisCache(),
            decoder = FakeVideoDecoder(failingRole = VideoRole.SOURCE),
        ).analyze(sampleRequest())

        val failure = assertIs<AnalysisOutcome.Failure>(outcome)
        val decodeFailure = assertIs<AnalysisFailure.Decode>(failure.failure)
        assertEquals(VideoRole.SOURCE, decodeFailure.role)
        assertEquals(
            listOf(
                PipelineStageId.VALIDATION,
                PipelineStageId.CACHE_READ,
                PipelineStageId.DECODE_SOURCE,
            ),
            failure.timings.map { it.stage },
        )
    }

    @Test
    fun `adapter declared cancellation remains a typed failure`() = runBlocking {
        val cancellingDecoder = object : VideoDecoder {
            override suspend fun decode(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
            ): StageResult<DecodedVideo> = StageResult.Failure(
                AnalysisFailure.Cancelled(
                    stage = if (role == VideoRole.SOURCE) {
                        PipelineStageId.DECODE_SOURCE
                    } else {
                        PipelineStageId.DECODE_REFERENCE
                    },
                ),
            )
        }

        val outcome = samplePipeline(
            cache = InMemoryAnalysisCache(),
            decoder = cancellingDecoder,
        ).analyze(sampleRequest())

        val failure = assertIs<AnalysisOutcome.Failure>(outcome)
        assertIs<AnalysisFailure.Cancelled>(failure.failure)
        assertEquals(PipelineStageId.DECODE_SOURCE, failure.failure.stage)
    }

    @Test
    fun `coroutine cancellation propagates and does not populate cache`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val blockingDecoder = object : VideoDecoder {
            override suspend fun decode(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
            ): StageResult<DecodedVideo> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val cache = InMemoryAnalysisCache()
        val deferred = async {
            samplePipeline(cache = cache, decoder = blockingDecoder).analyze(sampleRequest())
        }

        started.await()
        deferred.cancel()
        val cancellation = try {
            deferred.await()
            null
        } catch (error: CancellationException) {
            error
        }

        assertNotNull(cancellation)
        assertTrue(deferred.isCancelled)
        assertEquals(0, cache.size())
    }

    @Test
    fun `cache miss then hit bypasses adapters and rebuilds request metadata`() = runBlocking {
        val cache = InMemoryAnalysisCache()
        val decoder = FakeVideoDecoder()
        val pose = FakePoseEstimator()
        val pipeline = samplePipeline(cache = cache, decoder = decoder, poseEstimator = pose)

        val first = assertIs<AnalysisOutcome.Success>(pipeline.analyze(sampleRequest(requestId = "request-one")))
        val second = assertIs<AnalysisOutcome.Success>(
            pipeline.analyze(
                sampleRequest(
                    requestId = "request-two",
                    requestedAtEpochMs = ai.senp.core.contracts.TimestampMs(1_750_000_000_100),
                ),
            ),
        )

        assertEquals(CacheStatus.MISS, first.result.provenance.cacheStatus)
        assertEquals(CacheStatus.HIT, second.result.provenance.cacheStatus)
        assertEquals("request-two", second.result.requestId)
        assertEquals(1_750_000_000_100, second.result.requestedAtEpochMs.value)
        assertEquals(1, decoder.callsFor(VideoRole.SOURCE))
        assertEquals(1, decoder.callsFor(VideoRole.REFERENCE))
        assertEquals(1, pose.callsFor(VideoRole.SOURCE))
        assertEquals(1, pose.callsFor(VideoRole.REFERENCE))
        assertEquals(
            listOf(PipelineStageId.VALIDATION, PipelineStageId.CACHE_READ),
            second.result.timings.map { it.stage },
        )
        assertEquals(first.result.payload, second.result.payload)
        assertEquals(
            first.result.provenance.computedAtEpochMs,
            second.result.provenance.computedAtEpochMs,
        )
    }

    @Test
    fun `concurrent calls on one pipeline isolate success and failure state`() = runBlocking {
        val delegate = FakeVideoDecoder(delayMs = 1)
        val selectiveDecoder = object : VideoDecoder {
            override suspend fun decode(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
            ): StageResult<DecodedVideo> {
                if (source.uri == "fake://bad") {
                    kotlinx.coroutines.delay(1)
                    return StageResult.Failure(AnalysisFailure.Decode(role, "Synthetic concurrent failure"))
                }
                return delegate.decode(role, source, sampling)
            }
        }
        val pipeline = samplePipeline(
            cache = InMemoryAnalysisCache(),
            decoder = selectiveDecoder,
        )
        val badRequest = sampleRequest(requestId = "bad-request").copy(
            source = VideoSource(
                uri = "fake://bad",
                sha256 = Sha256("d".repeat(64)),
            ),
        )

        val good = async { pipeline.analyze(sampleRequest(requestId = "good-request")) }
        val bad = async { pipeline.analyze(badRequest) }

        val goodOutcome = assertIs<AnalysisOutcome.Success>(good.await())
        val badOutcome = assertIs<AnalysisOutcome.Failure>(bad.await())
        assertEquals("good-request", goodOutcome.result.requestId)
        assertIs<AnalysisFailure.Decode>(badOutcome.failure)
        assertEquals(PipelineStageId.DECODE_SOURCE, badOutcome.failure.stage)
    }

    @Test
    fun `adapter failure declared for another stage becomes an active-stage contract failure`() = runBlocking {
        val mismatchedDecoder = object : VideoDecoder {
            override suspend fun decode(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
            ): StageResult<DecodedVideo> = StageResult.Failure(
                AnalysisFailure.Alignment("wrong failure type from decoder"),
            )
        }

        val outcome = samplePipeline(
            cache = InMemoryAnalysisCache(),
            decoder = mismatchedDecoder,
        ).analyze(sampleRequest())

        val failure = assertIs<AnalysisOutcome.Failure>(outcome)
        val unexpected = assertIs<AnalysisFailure.Unexpected>(failure.failure)
        assertEquals(PipelineStageId.DECODE_SOURCE, unexpected.stage)
        assertContains(unexpected.message, "declared for ALIGNMENT")
    }

    @Test
    fun `unexpected adapter exception becomes typed stage failure`() = runBlocking {
        val explodingDecoder = object : VideoDecoder {
            override suspend fun decode(
                role: VideoRole,
                source: VideoSource,
                sampling: SamplingConfiguration,
            ): StageResult<DecodedVideo> = error("decoder exploded")
        }

        val outcome = samplePipeline(
            cache = InMemoryAnalysisCache(),
            decoder = explodingDecoder,
        ).analyze(sampleRequest())

        val failure = assertIs<AnalysisOutcome.Failure>(outcome)
        val unexpected = assertIs<AnalysisFailure.Unexpected>(failure.failure)
        assertEquals(PipelineStageId.DECODE_SOURCE, unexpected.stage)
        assertEquals("decoder exploded", unexpected.message)
    }
}

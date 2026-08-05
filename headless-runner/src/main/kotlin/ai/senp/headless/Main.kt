package ai.senp.headless

import ai.senp.core.cache.InMemoryAnalysisCache
import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.AnalysisRequest
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoSource
import ai.senp.core.pipeline.AnalysisPipeline
import ai.senp.core.pipeline.VideoDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val prettyJson = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}

fun main() = runBlocking {
    val pipeline = samplePipeline(InMemoryAnalysisCache())
    when (val outcome = pipeline.analyze(sampleRequest())) {
        is AnalysisOutcome.Success -> println(prettyJson.encodeToString(outcome.result))

        is AnalysisOutcome.Failure -> error(
            "Headless fake pipeline failed at ${outcome.failure.stage}: ${outcome.failure.message}",
        )
    }
}

fun samplePipeline(
    cache: InMemoryAnalysisCache,
    decoder: VideoDecoder = FakeVideoDecoder(),
    poseEstimator: FakePoseEstimator = FakePoseEstimator(),
    motionProcessor: FakeMotionProcessor = FakeMotionProcessor(),
    phaseDetector: FakePhaseDetector = FakePhaseDetector(),
    alignmentEngine: FakeAlignmentEngine = FakeAlignmentEngine(),
): AnalysisPipeline = AnalysisPipeline(
    decoder = decoder,
    poseEstimator = poseEstimator,
    motionProcessor = motionProcessor,
    phaseDetector = phaseDetector,
    alignmentEngine = alignmentEngine,
    cache = cache,
    monotonicClock = IncrementingMonotonicClock(),
    wallClock = { TimestampMs(1_750_000_000_000) },
    engineVersion = "wave1-kernel-1",
)

fun sampleRequest(
    requestId: String = "headless-demo-001",
    requestedAtEpochMs: TimestampMs = TimestampMs(1_750_000_000_000),
): AnalysisRequest = AnalysisRequest(
    requestId = requestId,
    requestedAtEpochMs = requestedAtEpochMs,
    source = VideoSource(
        uri = "fake://source",
        sha256 = Sha256("a".repeat(64)),
    ),
    reference = VideoSource(
        uri = "fake://reference",
        sha256 = Sha256("b".repeat(64)),
    ),
    configuration = AnalysisConfiguration(
        model = PoseModelConfiguration(
            modelSha256 = Sha256("c".repeat(64)),
            modelVariant = "pose-landmarker-full",
        ),
        pipelineVersion = "pipeline-v1",
        sampling = SamplingConfiguration(
            targetFramesPerSecond = 15,
            longEdgeCapPx = 640,
        ),
        normalizationVersion = "normalization-v1",
        exerciseProfileVersion = "biceps-curl-v1",
    ),
)

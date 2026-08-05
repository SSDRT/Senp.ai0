package ai.senp.headless

import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.DecodedFrame
import ai.senp.core.contracts.DecodedVideo
import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.FeatureSample
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.ImmutableFrameBuffer
import ai.senp.core.contracts.JointAngle
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSegment
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PixelFormat
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.PoseSequence
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.core.contracts.ProblemWindow
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import ai.senp.core.contracts.VideoSource
import ai.senp.core.contracts.WorldLandmark
import ai.senp.core.pipeline.AlignmentAnalysis
import ai.senp.core.pipeline.AlignmentEngine
import ai.senp.core.pipeline.MonotonicClock
import ai.senp.core.pipeline.MotionProcessor
import ai.senp.core.pipeline.PhaseDetector
import ai.senp.core.pipeline.PoseEstimator
import ai.senp.core.pipeline.VideoDecoder
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class FakeVideoDecoder(
    private val frameCount: Int = 4,
    private val failingRole: VideoRole? = null,
    private val delayMs: Long = 0,
) : VideoDecoder {
    private val callCounts = VideoRole.entries.associateWith { AtomicInteger(0) }

    fun callsFor(role: VideoRole): Int = callCounts.getValue(role).get()

    override suspend fun decode(
        role: VideoRole,
        source: VideoSource,
        sampling: SamplingConfiguration,
    ): StageResult<DecodedVideo> {
        callCounts.getValue(role).incrementAndGet()
        if (delayMs > 0) delay(delayMs)
        if (role == failingRole) {
            return StageResult.Failure(
                AnalysisFailure.Decode(role, "Synthetic decode failure for ${source.uri}"),
            )
        }

        val frames = List(frameCount) { index ->
            val timestampMs = index * 1_000L / sampling.targetFramesPerSecond
            val fill = (index + if (role == VideoRole.SOURCE) 1 else 101).toByte()
            DecodedFrame(
                timestamp = TimestampMs(timestampMs),
                diagnosticFrameIndex = index.toLong(),
                buffer = ImmutableFrameBuffer.copyOf(
                    pixelFormat = PixelFormat.RGBA_8888,
                    widthPx = 2,
                    heightPx = 2,
                    rowStrideBytes = 8,
                    bytes = ByteArray(16) { fill },
                ),
            )
        }
        val durationMs = if (frameCount == 0) 0 else {
            (frameCount * 1_000L + sampling.targetFramesPerSecond - 1) / sampling.targetFramesPerSecond
        }
        return StageResult.Success(
            DecodedVideo(
                role = role,
                duration = DurationMs(durationMs),
                frames = frames,
            ),
        )
    }
}

class FakePoseEstimator(
    private val failingRole: VideoRole? = null,
) : PoseEstimator {
    private val callCounts = VideoRole.entries.associateWith { AtomicInteger(0) }

    fun callsFor(role: VideoRole): Int = callCounts.getValue(role).get()

    override suspend fun estimate(
        role: VideoRole,
        video: DecodedVideo,
        configuration: PoseModelConfiguration,
    ): StageResult<PoseSequence> {
        callCounts.getValue(role).incrementAndGet()
        if (role == failingRole) {
            return StageResult.Failure(AnalysisFailure.Pose(role, "Synthetic pose failure"))
        }

        val roleOffset = if (role == VideoRole.SOURCE) 0.0 else 0.01
        val frames = video.frames.map { frame ->
            val timeOffset = frame.timestamp.value / 10_000.0
            PoseFrame(
                timestamp = frame.timestamp,
                diagnosticFrameIndex = frame.diagnosticFrameIndex,
                landmarks = List(PoseLandmark.LANDMARK_COUNT) { index ->
                    PoseLandmark(
                        index = index,
                        image = ImageLandmark(
                            x = index / 32.0,
                            y = timeOffset + roleOffset,
                            z = -index / 100.0,
                        ),
                        world = WorldLandmark(
                            xMeters = index / 100.0,
                            yMeters = timeOffset,
                            zMeters = roleOffset,
                        ),
                        visibility = configuration.thresholds.minimumTrackingConfidence.coerceAtLeast(0.99),
                        presence = configuration.thresholds.minimumPresenceConfidence.coerceAtLeast(0.98),
                    )
                },
                validity = FrameValidity.Valid,
            )
        }
        return StageResult.Success(PoseSequence(role, frames))
    }
}

class FakeMotionProcessor(
    private val failingRole: VideoRole? = null,
) : MotionProcessor {
    private val callCounts = VideoRole.entries.associateWith { AtomicInteger(0) }

    fun callsFor(role: VideoRole): Int = callCounts.getValue(role).get()

    override suspend fun process(
        poses: PoseSequence,
        normalizationVersion: String,
        exerciseProfileVersion: String,
    ): StageResult<MotionSeries> {
        callCounts.getValue(poses.role).incrementAndGet()
        if (poses.role == failingRole) {
            return StageResult.Failure(AnalysisFailure.Motion(poses.role, "Synthetic motion failure"))
        }

        val profileSignal = (normalizationVersion.length + exerciseProfileVersion.length) / 100.0
        return StageResult.Success(
            MotionSeries(
                role = poses.role,
                features = poses.frames.map { pose ->
                    FeatureSample(
                        timestamp = pose.timestamp,
                        values = linkedMapOf(
                            "tempo_seconds" to pose.timestamp.value / 1_000.0,
                            "profile_signal" to profileSignal,
                        ),
                        validity = pose.validity,
                    )
                },
                angles = poses.frames.map { pose ->
                    JointAngle(
                        timestamp = pose.timestamp,
                        joint = "left_elbow",
                        degrees = 90.0 + pose.diagnosticFrameIndex,
                        confidence = 0.97,
                    )
                },
            ),
        )
    }
}

class FakePhaseDetector(
    private val failingRole: VideoRole? = null,
) : PhaseDetector {
    private val callCounts = VideoRole.entries.associateWith { AtomicInteger(0) }

    fun callsFor(role: VideoRole): Int = callCounts.getValue(role).get()

    override suspend fun detect(
        motion: MotionSeries,
        exerciseProfileVersion: String,
    ): StageResult<PhaseSeries> {
        callCounts.getValue(motion.role).incrementAndGet()
        if (motion.role == failingRole) {
            return StageResult.Failure(AnalysisFailure.Phase(motion.role, "Synthetic phase failure"))
        }

        val first = motion.features.firstOrNull()?.timestamp ?: TimestampMs(0)
        val last = motion.features.lastOrNull()?.timestamp ?: first
        return StageResult.Success(
            PhaseSeries(
                role = motion.role,
                phases = listOf(
                    PhaseSegment(
                        name = "concentric:$exerciseProfileVersion",
                        start = first,
                        endExclusive = TimestampMs(last.value + 1),
                        repetitionIndex = 0,
                        confidence = 0.95,
                    ),
                ),
            ),
        )
    }
}

class FakeAlignmentEngine(
    private val fail: Boolean = false,
) : AlignmentEngine {
    private val callCount = AtomicInteger(0)

    val calls: Int
        get() = callCount.get()

    override suspend fun align(
        sourceMotion: MotionSeries,
        sourcePhases: PhaseSeries,
        referenceMotion: MotionSeries,
        referencePhases: PhaseSeries,
        configuration: AnalysisConfiguration,
    ): StageResult<AlignmentAnalysis> {
        callCount.incrementAndGet()
        if (fail) {
            return StageResult.Failure(AnalysisFailure.Alignment("Synthetic alignment failure"))
        }

        val points = sourceMotion.features.zip(referenceMotion.features).map { (source, reference) ->
            AlignmentPoint(
                sourceTimestamp = source.timestamp,
                referenceTimestamp = reference.timestamp,
                localCost = 0.05,
                confidence = 0.94,
            )
        }
        val firstPoint = points.first()
        val lastPoint = points.last()
        return StageResult.Success(
            AlignmentAnalysis(
                alignment = AlignmentResult(
                    mode = "phase-aware-masked-dtw:${configuration.exerciseProfileVersion}",
                    points = points,
                    aggregateConfidence = 0.94,
                ),
                problems = listOf(
                    ProblemWindow(
                        sourceStart = firstPoint.sourceTimestamp,
                        sourceEndExclusive = TimestampMs(lastPoint.sourceTimestamp.value + 1),
                        referenceStart = firstPoint.referenceTimestamp,
                        referenceEndExclusive = TimestampMs(lastPoint.referenceTimestamp.value + 1),
                        label = "elbow_too_open",
                        metric = "left_elbow_degrees",
                        meanDeviation = 12.0,
                        peakDeviation = 18.0,
                        severity = 0.42,
                        alignmentConfidence = 0.58,
                        certainty = ProblemCertainty.UNCERTAIN,
                    ),
                ),
            ),
        )
    }
}

class IncrementingMonotonicClock : MonotonicClock {
    private val value = AtomicLong(0)

    override fun elapsedRealtimeMs(): Long = value.getAndIncrement()
}

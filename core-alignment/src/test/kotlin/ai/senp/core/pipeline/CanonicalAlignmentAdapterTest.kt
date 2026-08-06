package ai.senp.core.pipeline

import ai.senp.core.contracts.AnalysisConfiguration
import ai.senp.core.contracts.FeatureSample
import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.FrameValidityReason
import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.JointAngle
import ai.senp.core.contracts.MotionSeries
import ai.senp.core.contracts.PhaseSeries
import ai.senp.core.contracts.PoseModelConfiguration
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.core.contracts.SamplingConfiguration
import ai.senp.core.contracts.Sha256
import ai.senp.core.contracts.StageResult
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalAlignmentAdapterTest {
    private val phaseDetector: PhaseDetector = TimestampFirstPhaseDetector()
    private val alignmentEngine: AlignmentEngine = TimestampFirstAlignmentEngine()

    @Test
    fun cleanCanonicalMotionIsMonotonicAndHasNoGenuineError() = runBlocking {
        val source = syntheticMotion(VideoRole.SOURCE, fps = 15)
        val reference = syntheticMotion(VideoRole.REFERENCE, fps = 20)
        val result = align(source, reference)

        assertTrue(result.alignment.points.isNotEmpty())
        assertTrue(result.alignment.mode in setOf("REP_NORMALIZED", "ANCHOR_CONSTRAINED_DTW", "BANDED_GLOBAL_DTW"))
        assertMonotonicAndBounded(result.alignment.points, reference)
        assertTrue(result.problems.none { it.certainty == ProblemCertainty.GENUINE })
        assertTrue(result.alignment.aggregateConfidence in 0.0..1.0)
    }

    @Test
    fun speedShiftDoesNotBecomeFormError() = runBlocking {
        val result = align(
            syntheticMotion(VideoRole.SOURCE, fps = 15, timingExponent = 1.22),
            syntheticMotion(VideoRole.REFERENCE, fps = 20),
        )

        assertTrue(result.problems.none { it.certainty == ProblemCertainty.GENUINE })
        assertTrue(result.alignment.points.all { it.confidence in 0.0..1.0 })
    }

    @Test
    fun deliberateCanonicalErrorProducesGenuineWindow() = runBlocking {
        val result = align(
            syntheticMotion(VideoRole.SOURCE, fps = 20, errorRange = 4_000L..5_000L),
            syntheticMotion(VideoRole.REFERENCE, fps = 20),
        )

        assertTrue(result.problems.any { window ->
            window.certainty == ProblemCertainty.GENUINE &&
                window.sourceStart.value <= 4_300L &&
                window.sourceEndExclusive.value >= 4_700L
        })
    }

    @Test
    fun longBlindSpanHasZeroConfidenceAndNoGenuineError() = runBlocking {
        val result = align(
            syntheticMotion(
                VideoRole.SOURCE,
                fps = 20,
                errorRange = 3_000L..5_000L,
                blindRange = 3_000L..5_000L,
            ),
            syntheticMotion(VideoRole.REFERENCE, fps = 20),
        )
        val blindPoints = result.alignment.points.filter { it.sourceTimestamp.value in 3_000L..5_000L }

        assertTrue(blindPoints.isNotEmpty())
        assertTrue(blindPoints.all { it.confidence == 0.0 })
        assertTrue(result.problems.none { window ->
            window.certainty == ProblemCertainty.GENUINE &&
                window.sourceStart.value < 5_000L && window.sourceEndExclusive.value > 3_000L
        })
    }

    @Test
    fun differentRepCountsRemainMonotonic() = runBlocking {
        val source = syntheticMotion(VideoRole.SOURCE, fps = 15, reps = 3.0)
        val reference = syntheticMotion(VideoRole.REFERENCE, fps = 15, reps = 5.0)
        val result = align(source, reference)

        assertEquals("REP_NORMALIZED", result.alignment.mode)
        assertMonotonicAndBounded(result.alignment.points, reference)
    }

    @Test
    fun singleMotionUsesTurningOrGlobalFallbackWithoutFalseError() = runBlocking {
        val source = syntheticMotion(VideoRole.SOURCE, fps = 20, reps = 1.0)
        val reference = syntheticMotion(VideoRole.REFERENCE, fps = 15, reps = 1.0)
        val result = align(source, reference)

        assertTrue(result.alignment.mode in setOf("ANCHOR_CONSTRAINED_DTW", "BANDED_GLOBAL_DTW"))
        assertTrue(result.problems.none { it.certainty == ProblemCertainty.GENUINE })
        assertMonotonicAndBounded(result.alignment.points, reference)
    }

    @Test
    fun phaseAdapterReturnsOrderedCanonicalSegments() = runBlocking {
        val motion = syntheticMotion(VideoRole.SOURCE, fps = 15, reps = 4.0)
        val result = assertIs<StageResult.Success<PhaseSeries>>(
            phaseDetector.detect(motion, SYNTHETIC_PROFILE),
        ).value

        assertEquals(VideoRole.SOURCE, result.role)
        assertTrue(result.phases.isNotEmpty())
        assertTrue(result.phases.zipWithNext().all { (left, right) -> left.endExclusive <= right.start })
        assertTrue(result.phases.all { it.confidence in 0.0..1.0 })
        assertTrue(result.phases.maxOf { it.repetitionIndex } >= 2)
    }

    @Test
    fun canonicalJointAnglesAreAcceptedAndOptionalAnglesAreMasked() = runBlocking {
        val source = angleMotion(VideoRole.SOURCE, includeRightSide = false)
        val reference = angleMotion(VideoRole.REFERENCE, includeRightSide = true)
        val result = align(source, reference, profileVersion = "biceps-curl/1")

        assertTrue(result.alignment.points.isNotEmpty())
        assertTrue(result.alignment.points.all { it.confidence in 0.0..1.0 })
        assertFalse(result.problems.any { it.certainty == ProblemCertainty.GENUINE })
    }


    @Test
    fun canonicalArtifactsArePopulatedAndSchemaCompatible() {
        runBlocking {
            val analysis = align(
                syntheticMotion(VideoRole.SOURCE, fps = 15, errorRange = 4_000L..5_000L),
                syntheticMotion(VideoRole.REFERENCE, fps = 20),
            )
            val directory = createTempDirectory("canonical-alignment-artifacts").toFile()
            val json = File(directory, "alignment.json")
            val csv = File(directory, "alignment.csv")

            CanonicalAlignmentArtifacts.writeJson(analysis, json)
            CanonicalAlignmentArtifacts.writeCsv(analysis.alignment, csv)

            assertTrue(json.readText().contains("\"schemaVersion\": 1"))
            assertTrue(json.readText().contains("\"alignment\""))
            assertTrue(json.readText().contains("\"problems\""))
            assertEquals(analysis.alignment.points.size + 1, csv.readLines().size)
            assertFalse(csv.readLines().drop(1).any { it.startsWith(",") })
            CanonicalAlignmentArtifacts.validate(analysis)
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun invalidRoleReturnsTypedAlignmentFailure() {
        runBlocking {
            val source = syntheticMotion(VideoRole.REFERENCE, fps = 15)
            val reference = syntheticMotion(VideoRole.REFERENCE, fps = 15)
            val sourcePhases = assertIs<StageResult.Success<PhaseSeries>>(
                phaseDetector.detect(source, SYNTHETIC_PROFILE),
            ).value
            val referencePhases = assertIs<StageResult.Success<PhaseSeries>>(
                phaseDetector.detect(reference, SYNTHETIC_PROFILE),
            ).value

            val result = alignmentEngine.align(source, sourcePhases, reference, referencePhases, configuration())
            assertIs<StageResult.Failure>(result)
        }
    }

    private suspend fun align(
        source: MotionSeries,
        reference: MotionSeries,
        profileVersion: String = SYNTHETIC_PROFILE,
    ): AlignmentAnalysis {
        val sourcePhases = assertIs<StageResult.Success<PhaseSeries>>(
            phaseDetector.detect(source, profileVersion),
        ).value
        val referencePhases = assertIs<StageResult.Success<PhaseSeries>>(
            phaseDetector.detect(reference, profileVersion),
        ).value
        return assertIs<StageResult.Success<AlignmentAnalysis>>(
            alignmentEngine.align(
                source,
                sourcePhases,
                reference,
                referencePhases,
                configuration(profileVersion),
            ),
        ).value
    }

    private fun assertMonotonicAndBounded(
        points: List<ai.senp.core.contracts.AlignmentPoint>,
        reference: MotionSeries,
    ) {
        assertTrue(points.zipWithNext().all { (left, right) ->
            left.sourceTimestamp < right.sourceTimestamp &&
                left.referenceTimestamp <= right.referenceTimestamp
        })
        val first = reference.features.first().timestamp
        val last = reference.features.last().timestamp
        assertTrue(points.all { it.referenceTimestamp in first..last })
    }

    private fun syntheticMotion(
        role: VideoRole,
        fps: Int,
        durationMs: Long = 10_000L,
        timingExponent: Double = 1.0,
        errorRange: LongRange? = null,
        blindRange: LongRange? = null,
        reps: Double = 4.0,
    ): MotionSeries {
        val stepMs = 1000.0 / fps
        val timestamps = generateSequence(0) { it + 1 }
            .map { (it * stepMs).toLong() }
            .takeWhile { it <= durationMs }
            .toMutableList()
        if (timestamps.last() != durationMs) timestamps += durationMs
        val samples = timestamps.distinct().map { timestamp ->
            val normalized = (timestamp.toDouble() / durationMs).coerceIn(0.0, 1.0).pow(timingExponent)
            val phase = normalized * reps * 2.0 * PI
            val error = if (errorRange?.contains(timestamp) == true) 60.0 else 0.0
            val blind = blindRange?.contains(timestamp) == true
            val validity = if (blind) {
                FrameValidity(
                    FrameValidityStatus.BLIND,
                    confidence = 0.0,
                    reasons = setOf(FrameValidityReason.LONG_GAP),
                )
            } else {
                FrameValidity.Valid
            }
            FeatureSample(
                timestamp = TimestampMs(timestamp),
                values = mapOf(
                    "primary" to 90.0 + 35.0 * sin(phase),
                    "secondary" to 70.0 + 20.0 * cos(phase),
                    "stable" to 40.0 + 5.0 * sin(phase * 0.5),
                    "form" to 55.0 + 6.0 * sin(phase * 0.5) + error,
                ),
                validity = validity,
            )
        }
        return MotionSeries(role, samples, emptyList())
    }

    private fun angleMotion(role: VideoRole, includeRightSide: Boolean): MotionSeries {
        val timestamps = (0..150).map { it * 67L }
        val samples = timestamps.map { timestamp ->
            FeatureSample(TimestampMs(timestamp), emptyMap(), FrameValidity.Valid)
        }
        val angles = timestamps.flatMap { timestamp ->
            val phase = timestamp.toDouble() / timestamps.last() * 8.0 * PI
            buildList {
                add(JointAngle(TimestampMs(timestamp), "left_elbow", 90.0 + 35.0 * sin(phase), 0.95))
                add(JointAngle(TimestampMs(timestamp), "left_shoulder", 70.0 + 12.0 * cos(phase), 0.92))
                add(JointAngle(TimestampMs(timestamp), "left_hip", 100.0 + 5.0 * sin(phase), 0.90))
                if (includeRightSide) {
                    add(JointAngle(TimestampMs(timestamp), "right_elbow", 90.0 + 35.0 * sin(phase), 0.95))
                    add(JointAngle(TimestampMs(timestamp), "right_shoulder", 70.0 + 12.0 * cos(phase), 0.92))
                    add(JointAngle(TimestampMs(timestamp), "right_hip", 100.0 + 5.0 * sin(phase), 0.90))
                }
            }
        }
        return MotionSeries(role, samples, angles)
    }

    private fun configuration(profileVersion: String = SYNTHETIC_PROFILE) = AnalysisConfiguration(
        model = PoseModelConfiguration(Sha256("0".repeat(64))),
        pipelineVersion = "alignment-adapter/1",
        sampling = SamplingConfiguration(targetFramesPerSecond = 15, longEdgeCapPx = 640),
        normalizationVersion = "pelvis-torso-scale/1",
        exerciseProfileVersion = profileVersion,
    )

    private companion object {
        const val SYNTHETIC_PROFILE = "alignment-synthetic/1"
    }
}

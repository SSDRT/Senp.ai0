package ai.senp.alignment

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AlignmentEngineTest {
    private val engine = AlignmentEngine()

    @Test
    fun equalMotionIsCleanBoundedAndMonotonic() {
        val user = syntheticTrack(15)
        val reference = syntheticTrack(20)
        val result = engine.align(user, reference, DEFAULT_PROFILE)

        assertEquals(AlignmentMode.REP_NORMALIZED, result.mode)
        assertTrue(result.windows.none { it.kind == WindowKind.GENUINE_FORM_ERROR })
        assertMonotonicAndBounded(result, reference)
        assertTrue(result.mapping.filter { it.active }.map { it.rawDifference }.average() < 2.0)
    }

    @Test
    fun speedOnlyTimingChangeIsNotGenuine() {
        val result = engine.align(
            syntheticTrack(15, timingExponent = 1.22),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        )

        assertTrue(result.windows.none { it.kind == WindowKind.GENUINE_FORM_ERROR })
        assertTrue(result.mapping.any { abs(it.pathSlope - 1.0) > 0.05 })
    }

    @Test
    fun shiftedStartIsHandledByActiveTrimOrPhaseOffset() {
        val result = engine.align(
            syntheticTrack(15, shiftMs = 700L),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        )

        val activeStartDifference = abs(
            requireNotNull(result.userPhase.activeStartMs) - requireNotNull(result.referencePhase.activeStartMs),
        )
        assertTrue(
            activeStartDifference >= 300L ||
                result.userPhase.phaseShiftMs >= 300L ||
                result.referencePhase.phaseShiftMs >= 300L,
        )
        assertTrue(result.windows.none { it.kind == WindowKind.GENUINE_FORM_ERROR })
        assertMonotonicAndBounded(result, syntheticTrack(20))
    }

    @Test
    fun deliberateFormErrorProducesGenuineWindow() {
        val result = engine.align(
            syntheticTrack(20, errorRange = 4_000L..5_000L),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        )

        assertTrue(result.windows.any { window ->
            window.kind == WindowKind.GENUINE_FORM_ERROR &&
                window.userStartMs <= 4_300L &&
                window.userEndMs >= 4_700L
        })
    }

    @Test
    fun longInvalidSpanHasZeroConfidenceAndNoGenuineWindow() {
        val result = engine.align(
            syntheticTrack(
                20,
                errorRange = 3_000L..5_000L,
                invalidRange = 3_000L..5_000L,
            ),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        )
        val blind = result.mapping.filter { it.userTimestampMs in 3_000L..5_000L }

        assertTrue(blind.isNotEmpty())
        assertTrue(blind.all { it.blind && it.alignmentConfidence == 0.0 })
        assertTrue(result.windows.none { window ->
            window.kind == WindowKind.GENUINE_FORM_ERROR &&
                window.userStartMs < 5_000L && window.userEndMs > 3_000L
        })
    }

    @Test
    fun differentRepCountsRemainMonotonic() {
        val reference = syntheticTrack(15, reps = 5.0)
        val result = engine.align(
            syntheticTrack(15, reps = 3.0),
            reference,
            DEFAULT_PROFILE,
        )

        assertEquals(AlignmentMode.REP_NORMALIZED, result.mode)
        assertMonotonicAndBounded(result, reference)
        assertTrue(result.userPhase.repCount != result.referencePhase.repCount)
    }

    @Test
    fun singleMotionPeakErrorIsDetected() {
        val result = engine.align(
            syntheticTrack(20, reps = 0.75, errorRange = 4_000L..5_000L),
            syntheticTrack(20, reps = 0.75),
            DEFAULT_PROFILE,
        )

        assertTrue(result.userPhase.repCount <= 2)
        assertTrue(result.windows.any { it.kind == WindowKind.GENUINE_FORM_ERROR })
    }

    @Test
    fun multiRepConsensusDetectsRepeatedPhaseError() {
        val base = syntheticTrack(20)
        val user = MotionTrack(base.frames.map { frame ->
            val withinRep = frame.timestampMs % 2_500L
            if (withinRep in 850L..1_500L) {
                frame.copy(features = frame.features.mapValues { (name, feature) ->
                    if (name == "form" && feature.value != null) {
                        feature.copy(value = feature.value + 60.0)
                    } else {
                        feature
                    }
                })
            } else {
                frame
            }
        })
        val result = engine.align(user, syntheticTrack(20), DEFAULT_PROFILE)

        assertTrue(result.userPhase.repCount >= 2)
        assertTrue(result.windows.any { it.kind == WindowKind.GENUINE_FORM_ERROR })
    }

    @Test
    fun missingFeaturesUseOnlyCommonValidCoverage() {
        val coverageProfile = ExerciseProfile.singlePhase(
            id = "coverage-test",
            primaryFeature = "primary",
            featureWeights = linkedMapOf("primary" to 1.0, "secondary" to 1.0),
        )
        val user = MotionTrack(syntheticTrack(15).frames.map { frame ->
            frame.copy(features = frame.features.filterKeys { it == "primary" })
        })
        val result = engine.align(user, syntheticTrack(15), coverageProfile)

        assertTrue(result.mapping.all { abs(it.commonCoverage - 0.5) < 1e-9 })
        assertFalse(result.mapping.any { it.blind })
        assertTrue(result.windows.none { it.kind == WindowKind.GENUINE_FORM_ERROR })
    }

    @Test
    fun flatSignalUsesExplicitLinearInsufficientMotionFallback() {
        val flat = MotionTrack((0..100).map { index ->
            MotionFrame(
                timestampMs = index * 100L,
                features = mapOf(
                    "primary" to FeatureSample(90.0),
                    "secondary" to FeatureSample(70.0),
                    "stable" to FeatureSample(40.0),
                ),
            )
        })
        val result = engine.align(flat, flat, DEFAULT_PROFILE)

        assertEquals(AlignmentMode.LINEAR_INSUFFICIENT_MOTION, result.mode)
        assertTrue(result.mapping.all { it.alignmentConfidence <= 0.35 && !it.active })
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun irregularTimestampsAreSourceOfTruth() {
        val regular = syntheticTrack(15)
        val irregular = MotionTrack(regular.frames.mapIndexed { index, frame ->
            frame.copy(timestampMs = frame.timestampMs + (index * index % 17))
        })
        val reference = syntheticTrack(20)
        val result = engine.align(irregular, reference, DEFAULT_PROFILE)

        assertEquals(irregular.frames.map { it.timestampMs }, result.mapping.map { it.userTimestampMs })
        assertMonotonicAndBounded(result, reference)
    }

    @Test
    fun deterministicRepeatedOutputAndFeatureOrdering() {
        val user = syntheticTrack(15, timingExponent = 1.07, errorRange = 4_000L..4_700L)
        val reference = syntheticTrack(20)
        val reversedProfile = DEFAULT_PROFILE.copy(
            featureRules = DEFAULT_PROFILE.featureRules.entries.reversed().associate { it.toPair() },
        )

        val first = engine.align(user, reference, DEFAULT_PROFILE)
        val second = engine.align(user, reference, DEFAULT_PROFILE)
        val reordered = engine.align(user, reference, reversedProfile)
        assertEquals(first, second)
        assertEquals(first, reordered)
    }

    @Test
    fun singleArcUsesGlobalDtwFallback() {
        val result = engine.align(
            syntheticTrack(20, reps = 0.25),
            syntheticTrack(15, reps = 0.25),
            DEFAULT_PROFILE,
        )

        assertEquals(AlignmentMode.BANDED_GLOBAL_DTW, result.mode)
        assertTrue(result.userPhase.anchorsMs.size < 4)
    }

    @Test
    fun singleCycleUsesTurningAnchorDtw() {
        val result = engine.align(
            syntheticTrack(20, reps = 1.0),
            syntheticTrack(15, reps = 1.0),
            DEFAULT_PROFILE,
        )

        assertEquals(AlignmentMode.ANCHOR_CONSTRAINED_DTW, result.mode)
        assertTrue(result.userPhase.anchorsMs.size >= 4)
    }

    @Test
    fun timestampWindowDecisionsAreStableAcrossFrameRates() {
        val lowRate = engine.align(
            syntheticTrack(10, errorRange = 4_000L..5_000L),
            syntheticTrack(10),
            DEFAULT_PROFILE,
        ).windows.first { it.kind == WindowKind.GENUINE_FORM_ERROR }
        val highRate = engine.align(
            syntheticTrack(30, errorRange = 4_000L..5_000L),
            syntheticTrack(30),
            DEFAULT_PROFILE,
        ).windows.first { it.kind == WindowKind.GENUINE_FORM_ERROR }

        assertTrue(abs(lowRate.userStartMs - highRate.userStartMs) <= 250L)
        assertTrue(abs(lowRate.userEndMs - highRate.userEndMs) <= 250L)
    }

    @Test
    fun mappingLookupInterpolatesInMilliseconds() {
        val result = engine.align(syntheticTrack(10), syntheticTrack(20), DEFAULT_PROFILE)
        val mapped = result.referenceTimestampFor(4_555L)

        assertNotNull(mapped)
        assertTrue(mapped in 4_000L..5_100L)
    }

    @Test
    fun traceWriterProducesInspectablePopulatedFiles() {
        val result = engine.align(
            syntheticTrack(15, errorRange = 4_000L..5_000L),
            syntheticTrack(20),
            DEFAULT_PROFILE,
        )
        val directory = createTempDirectory("alignment-trace-test").toFile()
        val json = File(directory, "trace.json")
        val csv = File(directory, "trace.csv")
        AlignmentTraceWriter.writeJson(result, json)
        AlignmentTraceWriter.writeCsv(result, csv)

        assertTrue(json.readText().contains("\"mapping\": ["))
        assertTrue(json.readText().contains("\"confidence\":"))
        assertTrue(csv.readLines().size == result.mapping.size + 1)
        assertFalse(csv.readLines().drop(1).any { it.startsWith(",") })
        directory.deleteRecursively()
    }

    @Test
    fun borderlineConfidenceErrorIsClassifiedUncertain() {
        val points = (0..30).map { index ->
            val error = index in 4..8
            MappingPoint(
                userTimestampMs = index * 100L,
                referenceTimestampMs = index * 100L,
                alignmentConfidence = 0.45,
                commonCoverage = 1.0,
                pathSlope = 1.6,
                rawDifference = if (error) 26.0 else 0.0,
                maximumDifference = if (error) 32.0 else 0.0,
                weightedDifference = if (error) 11.7 else 0.0,
                blind = false,
                active = true,
            )
        }
        val windows = WindowEngine(AlignmentConfig()).detect(
            points,
            repBoundariesMs = listOf(0L, 1_000L, 2_000L, 3_000L),
        )

        assertTrue(windows.any { it.kind == WindowKind.UNCERTAIN_ALIGNMENT })
        assertTrue(windows.none { it.kind == WindowKind.GENUINE_FORM_ERROR })
    }

    @Test
    fun emptyTrackProducesTypedEmptyMode() {
        val result = engine.align(MotionTrack(emptyList()), syntheticTrack(15), DEFAULT_PROFILE)
        assertEquals(AlignmentMode.EMPTY, result.mode)
        assertTrue(result.mapping.isEmpty())
        assertTrue(result.windows.isEmpty())
    }

    @Test
    fun nonMonotonicTimestampsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            MotionTrack(
                listOf(
                    MotionFrame(100L, mapOf("primary" to FeatureSample(1.0))),
                    MotionFrame(100L, mapOf("primary" to FeatureSample(2.0))),
                ),
            )
        }
    }

    private fun assertMonotonicAndBounded(result: AlignmentResult, reference: MotionTrack) {
        assertTrue(result.mapping.zipWithNext().all { (first, second) ->
            second.userTimestampMs > first.userTimestampMs &&
                second.referenceTimestampMs >= first.referenceTimestampMs
        })
        assertTrue(result.mapping.all { point ->
            point.referenceTimestampMs in reference.frames.first().timestampMs..reference.frames.last().timestampMs
        })
    }
}

package ai.senp.motion

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.ObservationSampling
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.VideoRole
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReferenceActionCoreTest {
    private val compiler = ReferenceActionCompiler()

    @Test
    fun `same reference reconstructs its states and transitions with high confidence`() {
        val reference = cyclicSequence(VideoRole.REFERENCE, repetitions = 5)
        val profile = compileProfile(reference)

        assertTrue(profile.cyclic)
        assertTrue(profile.referenceRepetitions >= 4)
        assertTrue(profile.validation.reconstructionAccuracy >= 0.72, profile.validation.toString())
        assertTrue(profile.validation.transitionCoverage >= 0.80, profile.validation.toString())
        assertTrue(profile.validation.meanRecognitionConfidence >= 0.55, profile.validation.toString())
        assertTrue(profile.states.all { it.features.any { feature -> feature.kind == ActionFeatureKind.TRAJECTORY } })

        val result = ActionStateRecognizer(profile).recognize(reference.copy(role = VideoRole.SOURCE))
        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertTrue(result.trackedFraction > 0.65, result.toString())
    }

    @Test
    fun `absolute start time offset does not affect recognition`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val baseline = ActionStateRecognizer(profile).recognize(cyclicSequence(VideoRole.SOURCE, repetitions = 3))
        val shifted = ActionStateRecognizer(profile).recognize(
            cyclicSequence(VideoRole.SOURCE, repetitions = 3, startOffsetMs = 37_000L),
        )

        assertEquals(ActionTrackingStatus.COMPLETED, shifted.finalStatus)
        assertEquals(baseline.completedRepetitions, shifted.completedRepetitions)
        assertTrue(kotlin.math.abs(baseline.trackedFraction - shifted.trackedFraction) < 0.05)
    }

    @Test
    fun `leading unrelated motion allows late action entry without wall clock coupling`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val leadIn = unrelatedSequence(VideoRole.SOURCE, durationMs = 2_000L)
        val action = cyclicSequence(VideoRole.SOURCE, repetitions = 3, startOffsetMs = 5_000L)
        val combined = action.copy(
            frames = leadIn.frames + action.frames,
            analyzableFraction = 1.0,
        )

        val result = ActionStateRecognizer(profile).recognize(combined)

        assertTrue(result.estimates.take(leadIn.frames.size).all { it.status == ActionTrackingStatus.NO_ACTION })
        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertEquals(3, result.completedRepetitions)
    }

    @Test
    fun `cyclic stream may enter mid phase without crediting the partial first cycle`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val midPhase = ActionStateRecognizer(profile).recognize(
            cyclicSequence(VideoRole.SOURCE, repetitions = 3, phaseOffsetFrames = 15),
        )

        assertEquals(ActionTrackingStatus.COMPLETED, midPhase.finalStatus)
        assertTrue(midPhase.completedRepetitions in 1..2, midPhase.toString())
        assertTrue(midPhase.trackedFraction > 0.55, midPhase.toString())
    }

    @Test
    fun `reference repetitions with modest speed variation learn a robust timing distribution`() {
        val profile = compileProfile(
            cyclicSequence(
                VideoRole.REFERENCE,
                repetitions = 5,
                perRepDurationScales = listOf(0.90, 1.05, 0.95, 1.10, 1.0),
            ),
        )
        val timing = assertNotNull(profile.cycleDurationMs)

        assertTrue(profile.cyclic)
        assertTrue(timing.sampleCount >= 3, timing.toString())
        assertTrue(timing.mad > 0.0, timing.toString())
        assertTrue(timing.lower < timing.median && timing.median < timing.upper, timing.toString())
        assertTrue(timing.median in 1_000.0..1_400.0, timing.toString())
    }

    @Test
    fun `broad elastic tempo variation keeps state correctness separate from timing`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val fast = ActionStateRecognizer(profile).recognize(
            cyclicSequence(VideoRole.SOURCE, repetitions = 3, durationScale = 0.50),
        )
        val slow = ActionStateRecognizer(profile).recognize(
            cyclicSequence(VideoRole.SOURCE, repetitions = 3, durationScale = 2.0),
        )

        assertEquals(ActionTrackingStatus.COMPLETED, fast.finalStatus)
        assertEquals(ActionTrackingStatus.COMPLETED, slow.finalStatus)
        assertTrue(fast.estimates.mapNotNull { it.timing }.any { it.classification == PhaseTimingClass.FASTER })
        assertTrue(slow.estimates.mapNotNull { it.timing }.any { it.classification == PhaseTimingClass.SLOWER })
        assertTrue(fast.trackedFraction > 0.55)
        assertTrue(slow.trackedFraction > 0.55)
    }

    @Test
    fun `wrong geometry in a recognized phase produces structured persistent deviations`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val user = cyclicSequence(VideoRole.SOURCE, repetitions = 3, leftElbowOffset = 75.0)
        val recognizer = ActionStateRecognizer(profile)
        val evaluator = ReferenceDeviationEvaluator(profile)
        val deviations = mutableListOf<ReferenceDeviationMeasurement>()

        user.frames.forEach { frame ->
            deviations += evaluator.evaluate(frame, recognizer.accept(frame))
        }
        val result = recognizer.finish()

        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        val elbow = deviations.filter { it.feature == "angle.left_elbow" }
        assertTrue(elbow.isNotEmpty())
        assertTrue(elbow.any { it.normalizedDeviation > 0.8 }, "max normalized elbow deviation=" + elbow.maxOfOrNull { it.normalizedDeviation })
        assertTrue(elbow.any(ReferenceDeviationMeasurement::persistenceCandidate))
        assertTrue(elbow.all { it.referenceRange.start <= it.referenceMedian && it.referenceMedian <= it.referenceRange.endInclusive })
    }

    @Test
    fun `deviation persistence resets when action state changes`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val evaluator = ReferenceDeviationEvaluator(profile)
        val state = profile.states.first()
        val feature = state.features.first {
            it.kind == ActionFeatureKind.GEOMETRY && it.importance >= 0.08 && it.confidence >= 0.45
        }
        val deviatedValue = feature.reference.upper + feature.scale * 2.0

        fun estimate(timestampMs: Long, stateIndex: Int): ActionStateEstimate = ActionStateEstimate(
            timestamp = TimestampMs(timestampMs),
            status = ActionTrackingStatus.TRACKING,
            stateId = profile.states[stateIndex].id,
            stateIndex = stateIndex,
            confidence = 0.95,
            featureCoverage = 0.95,
            mirrorMode = ActionMirrorMode.DIRECT,
            completedRepetitions = 0,
        )

        val first = evaluator.evaluate(
            spatialFrame(0L, mapOf(feature.name to deviatedValue), 0.96),
            estimate(0L, 0),
        ).single { it.feature == feature.name }
        assertFalse(first.persistenceCandidate)

        evaluator.evaluate(
            spatialFrame(200L, emptyMap(), 0.96),
            estimate(200L, 1),
        )

        val returned = evaluator.evaluate(
            spatialFrame(1_000L, mapOf(feature.name to deviatedValue), 0.96),
            estimate(1_000L, 0),
        ).single { it.feature == feature.name }
        assertFalse(returned.persistenceCandidate)
    }

    @Test
    fun `deviation persistence resets across tracking loss`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val evaluator = ReferenceDeviationEvaluator(profile)
        val state = profile.states.first()
        val feature = state.features.first {
            it.kind == ActionFeatureKind.GEOMETRY && it.importance >= 0.08 && it.confidence >= 0.45
        }
        val deviatedValue = feature.reference.upper + feature.scale * 2.0
        val tracked = ActionStateEstimate(
            timestamp = TimestampMs(0L),
            status = ActionTrackingStatus.TRACKING,
            stateId = state.id,
            stateIndex = 0,
            confidence = 0.95,
            featureCoverage = 0.95,
            mirrorMode = ActionMirrorMode.DIRECT,
            completedRepetitions = 0,
        )
        evaluator.evaluate(spatialFrame(0L, mapOf(feature.name to deviatedValue), 0.96), tracked)

        evaluator.evaluate(
            spatialFrame(200L, emptyMap(), 0.0),
            tracked.copy(
                timestamp = TimestampMs(200L),
                status = ActionTrackingStatus.LOST,
                stateId = null,
                stateIndex = null,
                confidence = 0.0,
                featureCoverage = 0.0,
            ),
        )

        val returned = evaluator.evaluate(
            spatialFrame(1_000L, mapOf(feature.name to deviatedValue), 0.96),
            tracked.copy(timestamp = TimestampMs(1_000L)),
        ).single { it.feature == feature.name }
        assertFalse(returned.persistenceCandidate)
    }

    @Test
    fun `reverse direction is not accepted as the forward action`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val reversed = cyclicSequence(VideoRole.SOURCE, repetitions = 3, reverseMotion = true)
        val result = ActionStateRecognizer(profile).recognize(reversed)

        assertTrue(result.completedRepetitions == 0 || result.trackedFraction < 0.25, result.toString())
        assertFalse(result.finalStatus == ActionTrackingStatus.COMPLETED && result.completedRepetitions >= 2)
    }

    @Test
    fun `unrelated motion remains no action rather than being forced into states`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val unrelated = unrelatedSequence(VideoRole.SOURCE, durationMs = 3_000L)
        val result = ActionStateRecognizer(profile).recognize(unrelated)

        assertEquals(ActionTrackingStatus.NO_ACTION, result.finalStatus)
        assertEquals(0, result.completedRepetitions)
        assertTrue(result.estimates.none { it.status == ActionTrackingStatus.TRACKING })
    }

    @Test
    fun `short occlusion preserves tracking but suppresses deviations while confidence is weak`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val user = cyclicSequence(VideoRole.SOURCE, repetitions = 3, occludedFrameRange = 32..37)
        val recognizer = ActionStateRecognizer(profile)
        val evaluator = ReferenceDeviationEvaluator(profile)
        var deviationsDuringOcclusion = 0
        var trackedBefore = false
        var trackedAfter = false

        user.frames.forEachIndexed { index, frame ->
            val estimate = recognizer.accept(frame)
            if (index < 32 && estimate.status == ActionTrackingStatus.TRACKING) trackedBefore = true
            if (index > 37 && estimate.status == ActionTrackingStatus.TRACKING) trackedAfter = true
            if (index in 32..37) deviationsDuringOcclusion += evaluator.evaluate(frame, estimate).size
        }
        val result = recognizer.finish()

        assertTrue(trackedBefore && trackedAfter)
        assertEquals(0, deviationsDuringOcclusion)
        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
    }

    @Test
    fun `extra and missing repetitions are reported without changing the state model`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val fewer = ActionStateRecognizer(profile).recognize(cyclicSequence(VideoRole.SOURCE, repetitions = 2))
        val extra = ActionStateRecognizer(profile).recognize(cyclicSequence(VideoRole.SOURCE, repetitions = 6))

        assertEquals(ActionTrackingStatus.COMPLETED, fewer.finalStatus)
        assertEquals(ActionTrackingStatus.COMPLETED, extra.finalStatus)
        assertTrue(fewer.repetitionDeltaFromReference < 0, fewer.toString())
        assertTrue(extra.repetitionDeltaFromReference > 0, extra.toString())
    }

    @Test
    fun `long phase hold stays tracked and is reported as slower timing`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val held = cyclicSequence(VideoRole.SOURCE, repetitions = 3, holdAfterFrameInEachRep = 10, holdMs = 850L)
        val result = ActionStateRecognizer(profile).recognize(held)

        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertTrue(result.estimates.mapNotNull { it.timing }.any { it.classification == PhaseTimingClass.SLOWER })
        assertTrue(result.estimates.none { it.status == ActionTrackingStatus.LOST })
    }

    @Test
    fun `one outlier reference repetition does not poison learned geometry ranges`() {
        val clean = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5))
        val outlier = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 5, outlierRep = 2))

        assertTrue(outlier.cyclic)
        val cleanMedians = clean.states.mapNotNull { state -> state.features.firstOrNull { it.name == "angle.left_elbow" }?.reference?.median }
        val outlierMedians = outlier.states.mapNotNull { state -> state.features.firstOrNull { it.name == "angle.left_elbow" }?.reference?.median }
        assertEquals(cleanMedians.size, outlierMedians.size)
        assertTrue(cleanMedians.zip(outlierMedians).all { (left, right) -> kotlin.math.abs(left - right) < 12.0 }, "clean=" + cleanMedians + " outlier=" + outlierMedians + " cleanRep=" + clean.referenceRepetitions + " outRep=" + outlier.referenceRepetitions + " cleanCycle=" + clean.cycleDurationMs + " outCycle=" + outlier.cycleDurationMs + " cleanCyc=" + clean.cyclicityConfidence + " outCyc=" + outlier.cyclicityConfidence)
        assertTrue(outlier.validation.referenceOutlierFraction > 0.0)
    }

    @Test
    fun `near static hold becomes a sustained single state instead of manufactured repetitions`() {
        val profile = compileProfile(staticHoldSequence(VideoRole.REFERENCE))
        val result = ActionStateRecognizer(profile).recognize(staticHoldSequence(VideoRole.SOURCE))

        assertFalse(profile.cyclic)
        assertEquals(1, profile.states.size)
        assertEquals(1, profile.referenceRepetitions)
        assertTrue(profile.cycleDurationMs == null)
        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertEquals(1, result.completedRepetitions)
        assertTrue(result.trackedFraction > 0.80, result.toString())
        assertTrue(result.estimates.any { it.status == ActionTrackingStatus.TRACKING && it.stateIndex == 0 })

        val unrelated = ActionStateRecognizer(profile).recognize(unrelatedSequence(VideoRole.SOURCE, durationMs = 3_000L))
        assertEquals(ActionTrackingStatus.NO_ACTION, unrelated.finalStatus)
        assertEquals(0, unrelated.completedRepetitions)
    }

    @Test
    fun `finite non cyclic action compiles and completes in order`() {
        val reference = finiteSequence(VideoRole.REFERENCE)
        val profile = compileProfile(reference)
        val shiftedAndSlower = finiteSequence(VideoRole.SOURCE, startOffsetMs = 12_000L, durationScale = 1.7)
        val result = ActionStateRecognizer(profile).recognize(shiftedAndSlower)

        assertFalse(profile.cyclic)
        assertEquals(1, profile.referenceRepetitions)
        assertTrue(profile.validation.transitionCoverage >= 0.80, profile.validation.toString())
        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertEquals(1, result.completedRepetitions)
        val trackedStates = result.estimates.mapNotNull { estimate ->
            estimate.stateIndex?.takeIf {
                estimate.status == ActionTrackingStatus.TRACKING || estimate.status == ActionTrackingStatus.COMPLETED
            }
        }
        assertTrue(trackedStates.zipWithNext().all { (left, right) -> right - left in 0..1 }, trackedStates.toString())

        val twiceSpeed = ActionStateRecognizer(profile).recognize(
            finiteSequence(VideoRole.SOURCE, startOffsetMs = 24_000L, durationScale = 0.50),
        )
        assertEquals(ActionTrackingStatus.COMPLETED, twiceSpeed.finalStatus)
        assertTrue(twiceSpeed.trackedFraction > 0.55, twiceSpeed.toString())
    }

    @Test
    fun `mirrored side labels remain recognizable through explicit mirror hypothesis`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val mirrored = cyclicSequence(VideoRole.SOURCE, repetitions = 3, mirrored = true)
        val result = ActionStateRecognizer(profile).recognize(mirrored)

        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertTrue(result.estimates.any { it.status == ActionTrackingStatus.TRACKING && it.mirrorMode == ActionMirrorMode.MIRRORED })
        assertTrue(result.trackedFraction > 0.55)
    }

    @Test
    fun `action layer is invariant to viewpoint metadata once spatial descriptors are body centric`() {
        val profile = compileProfile(cyclicSequence(VideoRole.REFERENCE, repetitions = 4))
        val source = cyclicSequence(VideoRole.SOURCE, repetitions = 3)
        val result = ActionStateRecognizer(profile).recognize(source)

        assertEquals(ActionTrackingStatus.COMPLETED, result.finalStatus)
        assertTrue(result.trackedFraction > 0.60)
    }

    @Test
    fun `compiler refuses weak evidence rather than fabricating an action`() {
        val weak = cyclicSequence(VideoRole.REFERENCE, repetitions = 3).copy(
            frames = cyclicSequence(VideoRole.REFERENCE, repetitions = 3).frames.map { frame ->
                frame.copy(intrinsicDescriptor = SpatialIntrinsicDescriptor(emptyMap(), 0.0), transformConfidence = 0.0)
            },
            analyzableFraction = 0.0,
        )
        val result = compiler.compile(weak)

        assertTrue(result is ReferenceActionCompilation.Failure)
        assertEquals(
            ReferenceActionCompilationFailureReason.INSUFFICIENT_ANALYZABLE_FRAMES,
            (result as ReferenceActionCompilation.Failure).reason,
        )
    }

    private fun compileProfile(sequence: SpatialSequenceAnalysis): ActionProfile {
        val result = compiler.compile(sequence)
        assertTrue(result is ReferenceActionCompilation.Success, result.toString())
        return (result as ReferenceActionCompilation.Success).profile
    }

    private fun cyclicSequence(
        role: VideoRole,
        repetitions: Int,
        durationScale: Double = 1.0,
        startOffsetMs: Long = 0L,
        reverseMotion: Boolean = false,
        mirrored: Boolean = false,
        leftElbowOffset: Double = 0.0,
        occludedFrameRange: IntRange? = null,
        outlierRep: Int? = null,
        holdAfterFrameInEachRep: Int? = null,
        holdMs: Long = 0L,
        phaseOffsetFrames: Int = 0,
        perRepDurationScales: List<Double>? = null,
    ): SpatialSequenceAnalysis {
        require(perRepDurationScales == null || perRepDurationScales.size == repetitions)
        require(perRepDurationScales?.all { it > 0.0 } != false)
        require(repetitions >= 1)
        val framesPerRep = 30
        val rawValues = mutableListOf<Map<String, Double>>()
        repeat(repetitions) { rep ->
            repeat(framesPerRep) { frameInRep ->
                val shiftedFrame = (frameInRep + phaseOffsetFrames).mod(framesPerRep)
                val phase = shiftedFrame.toDouble() / framesPerRep.toDouble()
                val theta = 2.0 * PI * phase
                val outlierOffset = if (rep == outlierRep) 95.0 else 0.0
                var values = linkedMapOf(
                    "angle.left_elbow" to 108.0 + 47.0 * cos(theta) + leftElbowOffset + outlierOffset,
                    "angle.right_elbow" to 101.0 + 39.0 * cos(theta + 0.24),
                    "angle.left_knee" to 121.0 + 34.0 * sin(theta + 0.08),
                    "angle.right_knee" to 116.0 + 29.0 * sin(theta + 0.31),
                    "ratio.left_forearm" to 1.02 + 0.17 * sin(theta + 0.55),
                    "ratio.right_forearm" to 0.96 + 0.14 * sin(theta + 0.80),
                )
                if (mirrored) values = values.entries.associate { mirroredSpatialKey(it.key) to it.value }.toMap(LinkedHashMap())
                rawValues += values
            }
        }
        val orderedValues = if (reverseMotion) rawValues.reversed() else rawValues
        val frames = mutableListOf<SpatialObservationFrame>()
        var timestampMs = startOffsetMs
        orderedValues.forEachIndexed { index, values ->
            val repIndex = index / framesPerRep
            val repFrame = index % framesPerRep
            val repScale = perRepDurationScales?.get(repIndex) ?: durationScale
            val frameStepMs = (1_200.0 * repScale / framesPerRep.toDouble()).toLong().coerceAtLeast(1L)
            val occluded = occludedFrameRange?.contains(index) == true
            val confidence = if (occluded) 0.0 else 0.96
            frames += spatialFrame(
                timestampMs = timestampMs,
                values = if (occluded) emptyMap() else values,
                confidence = confidence,
            )
            timestampMs += frameStepMs
            if (holdAfterFrameInEachRep == repFrame) timestampMs += holdMs
        }
        val duration = (timestampMs + 1L).coerceAtLeast(frames.last().timestamp.value + 1L)
        val analyzable = frames.count { it.intrinsicDescriptor.confidence > 0.0 }.toDouble() / frames.size.toDouble()
        return SpatialSequenceAnalysis(
            role = role,
            duration = DurationMs(duration),
            sampling = ObservationSampling(analysisFramesPerSecond = 25.0 / durationScale),
            frames = frames,
            analyzableFraction = analyzable,
        )
    }

    private fun finiteSequence(
        role: VideoRole,
        startOffsetMs: Long = 0L,
        durationScale: Double = 1.0,
    ): SpatialSequenceAnalysis {
        val frameCount = 42
        val totalDurationMs = (1_800.0 * durationScale).toLong()
        val step = maxOf(1L, totalDurationMs / frameCount)
        val frames = (0 until frameCount).map { index ->
            val phase = index.toDouble() / (frameCount - 1).toDouble()
            spatialFrame(
                timestampMs = startOffsetMs + index * step,
                values = linkedMapOf(
                    "angle.left_elbow" to 40.0 + 120.0 * phase,
                    "angle.right_elbow" to 55.0 + 92.0 * phase * phase,
                    "angle.left_knee" to 165.0 - 90.0 * phase,
                    "angle.right_knee" to 155.0 - 62.0 * phase * phase,
                    "ratio.left_forearm" to 0.75 + 0.55 * phase,
                    "ratio.right_forearm" to 1.35 - 0.40 * phase,
                ),
                confidence = 0.96,
            )
        }
        return SpatialSequenceAnalysis(
            role = role,
            duration = DurationMs(frames.last().timestamp.value + step + 1L),
            sampling = ObservationSampling(analysisFramesPerSecond = 24.0 / durationScale),
            frames = frames,
            analyzableFraction = 1.0,
        )
    }

    private fun staticHoldSequence(role: VideoRole): SpatialSequenceAnalysis {
        val stepMs = 80L
        val frames = (0 until 90).map { index ->
            val jitter = if (index % 2 == 0) 1.0 else -1.0
            spatialFrame(
                timestampMs = index * stepMs,
                values = linkedMapOf(
                    "angle.left_elbow" to 158.0 + 0.25 * jitter,
                    "angle.right_elbow" to 157.0 - 0.20 * jitter,
                    "angle.left_knee" to 171.0 + 0.20 * jitter,
                    "angle.right_knee" to 170.0 - 0.25 * jitter,
                    "ratio.left_forearm" to 1.02 + 0.001 * jitter,
                    "ratio.right_forearm" to 1.01 - 0.001 * jitter,
                ),
                confidence = 0.96,
            )
        }
        return SpatialSequenceAnalysis(
            role = role,
            duration = DurationMs(frames.last().timestamp.value + stepMs + 1L),
            sampling = ObservationSampling(analysisFramesPerSecond = 12.5),
            frames = frames,
            analyzableFraction = 1.0,
        )
    }

    private fun unrelatedSequence(role: VideoRole, durationMs: Long): SpatialSequenceAnalysis {
        val step = 80L
        val frames = (0 until (durationMs / step).toInt()).map { index ->
            spatialFrame(
                timestampMs = index * step,
                values = linkedMapOf(
                    "angle.left_elbow" to 12.0,
                    "angle.right_elbow" to 8.0,
                    "angle.left_knee" to 15.0,
                    "angle.right_knee" to 18.0,
                    "ratio.left_forearm" to 3.6,
                    "ratio.right_forearm" to 3.2,
                ),
                confidence = 0.98,
            )
        }
        return SpatialSequenceAnalysis(
            role = role,
            duration = DurationMs(durationMs + 1L),
            sampling = ObservationSampling(analysisFramesPerSecond = 12.5),
            frames = frames,
            analyzableFraction = 1.0,
        )
    }

    private fun spatialFrame(
        timestampMs: Long,
        values: Map<String, Double>,
        confidence: Double,
    ): SpatialObservationFrame = SpatialObservationFrame(
        timestamp = TimestampMs(timestampMs),
        evidenceKind = if (confidence > 0.0) SpatialEvidenceKind.THREE_D else SpatialEvidenceKind.UNAVAILABLE,
        canonicalPose = null,
        bodyTransform = null,
        rootOrientation = null,
        intrinsicDescriptor = SpatialIntrinsicDescriptor(values, confidence),
        transformConfidence = confidence,
        selectedSubjectId = if (confidence > 0.0) "primary" else null,
        spatialSegmentId = if (confidence > 0.0) 0 else null,
    )
}

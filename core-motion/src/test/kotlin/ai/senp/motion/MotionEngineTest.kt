package ai.senp.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionEngineTest {
    private val config = MotionConfig(
        maxRepairGapMs = 220L,
        continuityBreakGapMs = 300L,
        emaHalfLifeMs = 120L,
        blindEnterDurationMs = 200L,
        recoverDurationMs = 150L,
    )

    private val bothArms = ExerciseProfile(
        id = "both_arms_test",
        required = setOf(
            LandmarkId.LEFT_SHOULDER,
            LandmarkId.LEFT_ELBOW,
            LandmarkId.LEFT_WRIST,
            LandmarkId.RIGHT_SHOULDER,
            LandmarkId.RIGHT_ELBOW,
            LandmarkId.RIGHT_WRIST,
        ),
        sidePolicy = SidePolicy.BOTH,
        minimumRequiredCoverage = 0.75,
    )

    @Test
    fun `clean track remains valid and retains all 33 landmarks`() {
        val result = MotionEngine(config).analyze(SyntheticPose.sequence(fps = 15, seconds = 2), ExerciseProfiles.bicepsCurl)
        assertEquals(31, result.size)
        assertTrue(result.all { it.frame.landmarks.size == LandmarkId.COUNT })
        assertTrue(result.all { it.quality.validity == FrameValidity.VALID })
        assertTrue(result.all { it.quality.score > 0.85 })
    }

    @Test
    fun `elapsed-time EMA reduces static jitter`() {
        val raw = (0..80).map { index ->
            SyntheticPose.frame(index * 50L, phase = 0.2, noise = 0.025, seed = index)
        }
        val smoothed = TrackProcessor(config).process(raw)
        assertTrue(jitter(smoothed, LandmarkId.LEFT_WRIST) < jitter(raw, LandmarkId.LEFT_WRIST) * 0.65)
    }

    @Test
    fun `EMA alpha follows irregular elapsed milliseconds exactly`() {
        val base = SyntheticPose.frame(0L)
        fun withWrist(timestampMs: Long, x: Double): PoseFrame = base.copy(
            timestampMs = timestampMs,
            landmarks = base.landmarks.toMutableList().also { landmarks ->
                val wrist = landmarks[LandmarkId.LEFT_WRIST.index]
                landmarks[LandmarkId.LEFT_WRIST.index] = wrist.copy(image = wrist.image!!.copy(x = x))
            },
        )
        val processor = TrackProcessor(config.copy(emaHalfLifeMs = 100L))
        val result = processor.process(listOf(withWrist(0L, 0.0), withWrist(100L, 1.0), withWrist(300L, 1.0)))
        assertEquals(0.5, result[1][LandmarkId.LEFT_WRIST].image!!.x, 1e-12)
        assertEquals(0.875, result[2][LandmarkId.LEFT_WRIST].image!!.x, 1e-12)
    }

    @Test
    fun `short internal gap is repaired with downgraded confidence and repaired validity`() {
        val frames = SyntheticPose.withMissing(
            SyntheticPose.sequence(fps = 20, seconds = 2),
            fromMs = 800L,
            toMs = 850L,
            ids = setOf(LandmarkId.LEFT_WRIST),
        )
        val leftCurl = ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.LEFT_ONLY)
        val result = MotionEngine(config).analyze(frames, leftCurl)
        val repaired = result.filter { it.frame.timestampMs in 800L..850L }
        assertTrue(repaired.isNotEmpty())
        assertTrue(repaired.all { LandmarkId.LEFT_WRIST in it.repairedLandmarks })
        assertTrue(repaired.all { it.frame[LandmarkId.LEFT_WRIST].repaired })
        assertTrue(repaired.all { it.frame[LandmarkId.LEFT_WRIST].visibility <= config.repairedConfidenceCap })
        assertTrue(repaired.all { it.quality.validity == FrameValidity.REPAIRED })
        assertTrue(repaired.all { MotionFeatures.angles(it.frame, it.quality.validity)["left_elbow"] == null })
    }

    @Test
    fun `repair boundary is inclusive and a just-too-long gap stays missing`() {
        val base = listOf(0L, 100L, 200L).map { SyntheticPose.frame(it) }
        val missing = SyntheticPose.withMissing(base, 100L, 100L, setOf(LandmarkId.LEFT_WRIST))
        val repaired = TrackProcessor(config.copy(maxRepairGapMs = 200L)).processDetailed(missing)
        assertTrue(LandmarkId.LEFT_WRIST in repaired[1].repairedLandmarks)

        val over = listOf(0L, 101L, 201L).map { SyntheticPose.frame(it) }
        val overMissing = SyntheticPose.withMissing(over, 101L, 101L, setOf(LandmarkId.LEFT_WRIST))
        val notRepaired = TrackProcessor(config.copy(maxRepairGapMs = 200L)).processDetailed(overMissing)
        assertFalse(LandmarkId.LEFT_WRIST in notRepaired[1].repairedLandmarks)
        assertNull(notRepaired[1].frame[LandmarkId.LEFT_WRIST].image)
    }

    @Test
    fun `leading and trailing gaps are never extrapolated`() {
        val frames = SyntheticPose.sequence(fps = 10, seconds = 1)
        val missing = SyntheticPose.withMissing(
            SyntheticPose.withMissing(frames, 0L, 100L, setOf(LandmarkId.LEFT_WRIST)),
            900L,
            1000L,
            setOf(LandmarkId.RIGHT_WRIST),
        )
        val tracked = TrackProcessor(config).processDetailed(missing)
        assertTrue(tracked.take(2).all { !it.frame[LandmarkId.LEFT_WRIST].repaired })
        assertTrue(tracked.takeLast(2).all { !it.frame[LandmarkId.RIGHT_WRIST].repaired })
    }

    @Test
    fun `long blind gap is not repaired and recovery emits continuity break without EMA bleed`() {
        val armIds = bothArms.required
        val frames = SyntheticPose.withMissing(
            SyntheticPose.sequence(fps = 20, seconds = 3),
            fromMs = 800L,
            toMs = 1500L,
            ids = armIds,
        )
        val result = MotionEngine(config).analyze(frames, bothArms)
        val blindWindow = result.filter { it.frame.timestampMs in 800L..1500L }
        assertTrue(blindWindow.any { it.quality.validity == FrameValidity.BLIND })
        assertTrue(blindWindow.none { it.repairedLandmarks.isNotEmpty() })

        val recovered = result.first { it.frame.timestampMs == 1550L }
        assertEquals(FrameValidity.CONTINUITY_BREAK, recovered.quality.validity)
        assertTrue(armIds.all { it in recovered.continuityBreakLandmarks })
        val rawRecovered = frames.first { it.timestampMs == 1550L }
        assertEquals(rawRecovered[LandmarkId.LEFT_WRIST].image!!.x, recovered.frame[LandmarkId.LEFT_WRIST].image!!.x, 1e-12)
    }

    @Test
    fun `large timestamp jump itself breaks continuity`() {
        val frames = listOf(SyntheticPose.frame(0L), SyntheticPose.frame(300L, phase = 0.15))
        val tracked = TrackProcessor(config.copy(continuityBreakGapMs = 300L)).processDetailed(frames)
        assertEquals(LandmarkId.COUNT, tracked[1].continuityBreakLandmarks.size)
        val result = MotionEngine(config.copy(continuityBreakGapMs = 300L)).analyze(frames, bothArms)
        assertEquals(FrameValidity.CONTINUITY_BREAK, result[1].quality.validity)
    }

    @Test
    fun `timestamp-duration hysteresis backdates blind entry and recovery`() {
        val timestamps = listOf(0L, 100L, 200L, 300L, 400L, 500L, 550L)
        val lowTimes = setOf(100L, 200L, 300L)
        val frames = timestamps.map { timestamp ->
            SyntheticPose.frame(timestamp, confidence = if (timestamp in lowTimes) 0.0 else 0.95)
        }
        val quality = QualityGate(config).evaluate(frames, bothArms)
        assertEquals(FrameValidity.VALID, quality[0].validity)
        assertTrue(quality.slice(1..3).all { it.validity == FrameValidity.BLIND })
        assertTrue(quality.slice(4..6).all { it.validity == FrameValidity.VALID })
    }

    @Test
    fun `sub-threshold low duration degrades but never enters blind`() {
        val frames = listOf(
            SyntheticPose.frame(0L),
            SyntheticPose.frame(100L, confidence = 0.0),
            SyntheticPose.frame(299L, confidence = 0.0),
            SyntheticPose.frame(300L),
        )
        val quality = QualityGate(config).evaluate(frames, bothArms)
        assertFalse(quality.any { it.validity == FrameValidity.BLIND })
        assertTrue(quality.slice(1..2).all { it.validity == FrameValidity.DEGRADED })
    }

    @Test
    fun `clipping instability and impossible proportions reduce quality`() {
        val frame = SyntheticPose.frame(0L)
        val clean = QualityGate(config).evaluate(listOf(frame), bothArms).single().score
        val signaled = QualityGate(config).evaluateTracked(
            frames = listOf(TrackedFrame(frame)),
            profile = bothArms,
            signals = listOf(FrameSignals(clipping = 0.8, instability = 0.7)),
            guardrails = listOf(GuardrailFlags(impossibleProportions = true)),
        ).single().score
        assertTrue(signaled < clean - 0.25)
    }

    @Test
    fun `exercise-specific joints change score without arm hardcoding`() {
        val base = SyntheticPose.frame(0L)
        val weakLegs = base.copy(landmarks = base.landmarks.mapIndexed { index, landmark ->
            if (LandmarkId.entries[index] in setOf(
                    LandmarkId.LEFT_HIP,
                    LandmarkId.RIGHT_HIP,
                    LandmarkId.LEFT_KNEE,
                    LandmarkId.RIGHT_KNEE,
                    LandmarkId.LEFT_ANKLE,
                    LandmarkId.RIGHT_ANKLE,
                )
            ) landmark.copy(visibility = 0.0, presence = 0.0) else landmark
        })
        val curl = QualityGate(config).evaluate(listOf(weakLegs), ExerciseProfiles.bicepsCurl).single()
        val squat = QualityGate(config).evaluate(listOf(weakLegs), ExerciseProfiles.squat).single()
        assertTrue(curl.score > squat.score + 0.45)
        assertEquals(FrameValidity.DEGRADED, squat.validity)
    }

    @Test
    fun `best-visible side is deterministic and uses switch margin`() {
        fun armConfidence(frame: PoseFrame, left: Double, right: Double): PoseFrame = frame.copy(
            landmarks = frame.landmarks.mapIndexed { index, landmark ->
                when (LandmarkId.entries[index]) {
                    LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST -> landmark.copy(visibility = left, presence = left)
                    LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_ELBOW, LandmarkId.RIGHT_WRIST -> landmark.copy(visibility = right, presence = right)
                    else -> landmark
                }
            },
        )
        val frames = listOf(
            armConfidence(SyntheticPose.frame(0L), left = 0.20, right = 0.95),
            armConfidence(SyntheticPose.frame(50L), left = 0.95, right = 0.90),
            armConfidence(SyntheticPose.frame(100L), left = 0.95, right = 0.65),
        )
        val quality = QualityGate(config).evaluate(frames, ExerciseProfiles.bicepsCurl)
        assertEquals(listOf(BodySide.RIGHT, BodySide.RIGHT, BodySide.LEFT), quality.map { it.selectedSide })

        val tie = QualityGate(config).evaluate(listOf(SyntheticPose.frame(0L)), ExerciseProfiles.bicepsCurl).single()
        assertEquals(BodySide.LEFT, tie.selectedSide)
    }

    @Test
    fun `fixed side policies evaluate only the requested side plus neutral landmarks`() {
        val frame = SyntheticPose.frame(0L).copy(landmarks = SyntheticPose.frame(0L).landmarks.mapIndexed { index, landmark ->
            if (LandmarkId.entries[index].side == BodySide.LEFT) landmark.copy(visibility = 0.1, presence = 0.1) else landmark
        })
        val left = QualityGate(config).evaluate(
            listOf(frame),
            ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.LEFT_ONLY),
        ).single()
        val right = QualityGate(config).evaluate(
            listOf(frame),
            ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.RIGHT_ONLY),
        ).single()
        assertEquals(BodySide.LEFT, left.selectedSide)
        assertEquals(BodySide.RIGHT, right.selectedSide)
        assertTrue(right.score > left.score + 0.45)
    }

    @Test
    fun `left-right swap guardrail requires strong whole-side continuity evidence`() {
        val previous = SyntheticPose.frame(0L, phase = 0.1)
        val swapped = SyntheticPose.swapAllSides(SyntheticPose.frame(50L, phase = 0.11))
        val inspection = PoseGuardrails().inspect(swapped, previous)
        assertTrue(inspection.flags.leftRightSwapApplied)
        assertTrue(inspection.comparedPairs >= 4)

        val normal = PoseGuardrails().inspect(SyntheticPose.frame(50L, phase = 0.11), previous)
        assertFalse(normal.flags.leftRightSwapApplied)
    }

    @Test
    fun `impossible proportions are flagged but bone coordinates are not rewritten`() {
        val base = SyntheticPose.frame(0L)
        val bad = base.copy(landmarks = base.landmarks.toMutableList().also { landmarks ->
            val wrist = landmarks[LandmarkId.LEFT_WRIST.index]
            landmarks[LandmarkId.LEFT_WRIST.index] = wrist.copy(image = Vec3(10.0, 10.0, 0.0))
        })
        val inspection = PoseGuardrails().inspect(bad)
        assertTrue(inspection.flags.impossibleProportions)
        assertEquals(Vec3(10.0, 10.0, 0.0), inspection.frame[LandmarkId.LEFT_WRIST].image)
        assertFalse(PoseGuardrails().inspect(base).flags.impossibleProportions)
    }

    @Test
    fun `image normalization is translation-scale invariant and preserves world points`() {
        val normalizer = PoseNormalizer()
        val original = SyntheticPose.frame(0L)
        val transformed = SyntheticPose.transformImage(original, scale = 3.5, translation = Vec3(4.0, -2.0, 0.0))
        val a = normalizer.normalizeImage(original)
        val b = normalizer.normalizeImage(transformed)
        assertEquals(NormalizationStatus.NORMALIZED, a.status)
        assertEquals(NormalizationStatus.NORMALIZED, b.status)
        assertTrue((a.frame[LandmarkId.LEFT_WRIST].image!! - b.frame[LandmarkId.LEFT_WRIST].image!!).norm() < 1e-9)
        assertEquals(original[LandmarkId.LEFT_WRIST].world, a.frame[LandmarkId.LEFT_WRIST].world)
    }

    @Test
    fun `world orientation normalization is rigid-rotation invariant and never synthesizes depth`() {
        val normalizer = PoseNormalizer()
        val original = SyntheticPose.frame(0L)
        val rotated = SyntheticPose.rotateWorldZ(original, PI / 2.0)
        val a = normalizer.normalizeWorld(original, orientToBodyAxes = true)
        val b = normalizer.normalizeWorld(rotated, orientToBodyAxes = true)
        assertEquals(NormalizationStatus.NORMALIZED, a.status)
        assertEquals(NormalizationStatus.NORMALIZED, b.status)
        assertTrue((a.frame[LandmarkId.LEFT_WRIST].world!! - b.frame[LandmarkId.LEFT_WRIST].world!!).norm() < 1e-9)

        val noWorld = original.copy(landmarks = original.landmarks.map { it.copy(world = null) })
        val missing = normalizer.normalizeWorld(noWorld)
        assertEquals(NormalizationStatus.MISSING_ANCHORS, missing.status)
        assertTrue(missing.frame.landmarks.all { it.world == null })
    }

    @Test
    fun `angle definitions are ordered deterministic and invalid triplets return null`() {
        assertEquals(
            listOf(
                "left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_wrist", "right_wrist",
                "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle",
            ),
            MotionFeatures.angleDefinitions.keys.toList(),
        )
        val base = SyntheticPose.frame(0L)
        val collapsed = base.copy(landmarks = base.landmarks.toMutableList().also { landmarks ->
            landmarks[LandmarkId.LEFT_ELBOW.index] = landmarks[LandmarkId.LEFT_ELBOW.index].copy(
                image = landmarks[LandmarkId.LEFT_SHOULDER.index].image,
            )
        })
        assertNull(MotionFeatures.angles(collapsed, FrameValidity.VALID)["left_elbow"])
        assertTrue(MotionFeatures.angles(base, FrameValidity.BLIND).values.all { it == null })
        assertTrue(MotionFeatures.angles(base, FrameValidity.CONTINUITY_BREAK).values.all { it == null })
    }

    @Test
    fun `angular velocity torso and trajectory features respect timestamps`() {
        assertEquals(1000.0, MotionFeatures.angularVelocity(10.0, 40.0, 100L, 130L)!!, 1e-12)
        assertNull(MotionFeatures.angularVelocity(10.0, 40.0, 100L, 100L))
        val frames = listOf(SyntheticPose.frame(0L), SyntheticPose.frame(80L, phase = 0.1), SyntheticPose.frame(220L, phase = 0.2))
        val torso = MotionFeatures.torso(frames.first())
        assertNotNull(torso)
        assertTrue(torso.torsoLength > 0.0)
        val trajectory = MotionFeatures.trajectory(frames, LandmarkId.LEFT_WRIST)
        val velocities = MotionFeatures.trajectoryVelocities(trajectory)
        assertEquals(3, trajectory.size)
        assertEquals(listOf(80L, 220L), velocities.map { it.timestampMs })
    }

    @Test
    fun `behavior is approximately invariant at 10 15 and 20 FPS`() {
        data class Summary(
            val meanScore: Double,
            val angleRange: Double,
            val blindDurationMs: Long,
            val commonWristX: List<Double>,
        )

        val summaries = listOf(10, 15, 20).map { fps ->
            val shortGap = SyntheticPose.withMissing(
                SyntheticPose.sequence(fps = fps, seconds = 10),
                4000L,
                4080L,
                setOf(LandmarkId.LEFT_WRIST),
            )
            val leftCurl = ExerciseProfiles.bicepsCurl.copy(sidePolicy = SidePolicy.LEFT_ONLY)
            val result = MotionEngine(config).analyze(shortGap, leftCurl)
            val angles = result.mapNotNull { MotionFeatures.angles(it.frame, it.quality.validity)["left_elbow"] }

            val longGap = SyntheticPose.withMissing(
                SyntheticPose.sequence(fps = fps, seconds = 10),
                6000L,
                6700L,
                bothArms.required,
            )
            val blindResult = MotionEngine(config).analyze(longGap, bothArms)
            val blindTimes = blindResult.filter { it.quality.validity == FrameValidity.BLIND }.map { it.frame.timestampMs }
            val blindDuration = if (blindTimes.isEmpty()) 0L else blindTimes.last() - blindTimes.first()

            Summary(
                meanScore = result.map { it.quality.score }.average(),
                angleRange = angles.maxOrNull()!! - angles.minOrNull()!!,
                blindDurationMs = blindDuration,
                commonWristX = listOf(2000L, 4000L, 6000L, 8000L).map { timestamp ->
                    result.first { it.frame.timestampMs == timestamp }.frame[LandmarkId.LEFT_WRIST].image!!.x
                },
            )
        }

        assertTrue(summaries.maxOf { it.meanScore } - summaries.minOf { it.meanScore } < 0.02)
        assertTrue(summaries.maxOf { it.angleRange } - summaries.minOf { it.angleRange } < 2.5)
        assertTrue(summaries.maxOf { it.blindDurationMs } - summaries.minOf { it.blindDurationMs } <= 100L)
        for (sampleIndex in summaries.first().commonWristX.indices) {
            val values = summaries.map { it.commonWristX[sampleIndex] }
            assertTrue(values.max() - values.min() < 0.015)
        }
    }

    @Test
    fun `empty input is supported and duplicate timestamps are rejected`() {
        assertTrue(MotionEngine(config).analyze(emptyList(), ExerciseProfiles.generic).isEmpty())
        val duplicate = listOf(SyntheticPose.frame(0L), SyntheticPose.frame(0L))
        assertFailsWith<IllegalArgumentException> { TrackProcessor(config).process(duplicate) }
    }

    private fun jitter(frames: List<PoseFrame>, id: LandmarkId): Double {
        val values = frames.map { it[id].image!!.x }
        val differences = values.zipWithNext { first, second -> second - first }
        val mean = differences.average()
        return sqrt(differences.map { (it - mean) * (it - mean) }.average())
    }
}

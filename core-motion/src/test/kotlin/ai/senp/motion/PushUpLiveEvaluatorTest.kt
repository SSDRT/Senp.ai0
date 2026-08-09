package ai.senp.motion

import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.TimestampMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PushUpLiveEvaluatorTest {
    @Test
    fun `counts one full depth pushup only after returning to lockout`() {
        val evaluator = PushUpLiveEvaluator()
        val feedback = listOf(
            sample(0, PoseShape.TOP),
            sample(100, PoseShape.TOP),
            sample(250, PoseShape.MID),
            sample(400, PoseShape.BOTTOM),
            sample(500, PoseShape.BOTTOM),
            sample(650, PoseShape.MID),
            sample(850, PoseShape.TOP),
        ).map(evaluator::update)

        assertEquals(1, feedback.last().correctReps)
        assertEquals(0, feedback.last().rejectedAttempts)
        assertEquals(PushUpCue.GOOD_REP, feedback.last().cue)
        assertEquals(PushUpPhase.READY, feedback.last().phase)
    }

    @Test
    fun `rejects shallow attempt and does not increment correct reps`() {
        val evaluator = PushUpLiveEvaluator()
        val feedback = listOf(
            sample(0, PoseShape.TOP),
            sample(150, PoseShape.MID),
            sample(350, PoseShape.MID),
            sample(550, PoseShape.TOP),
        ).map(evaluator::update).last()

        assertEquals(0, feedback.correctReps)
        assertEquals(1, feedback.rejectedAttempts)
        assertEquals(PushUpCue.GO_LOWER, feedback.cue)
    }

    @Test
    fun `persistent hip sag invalidates otherwise full depth repetition`() {
        val evaluator = PushUpLiveEvaluator()
        val feedback = listOf(
            sample(0, PoseShape.TOP),
            sample(150, PoseShape.MID),
            sample(300, PoseShape.BOTTOM, sag = true),
            sample(450, PoseShape.BOTTOM, sag = true),
            sample(600, PoseShape.MID, sag = true),
            sample(750, PoseShape.MID),
            sample(950, PoseShape.TOP),
        ).map(evaluator::update).last()

        assertEquals(0, feedback.correctReps)
        assertEquals(1, feedback.rejectedAttempts)
    }

    @Test
    fun `front facing framing is rejected before phase state advances`() {
        val evaluator = PushUpLiveEvaluator()
        val feedback = evaluator.update(sample(0, PoseShape.TOP, frontFacing = true))

        assertEquals(PushUpCue.TURN_SIDEWAYS, feedback.cue)
        assertEquals(PushUpPhase.SEARCHING, feedback.phase)
        assertEquals(0, feedback.correctReps)
        assertTrue(feedback.sideViewScore < 0.45)
    }

    @Test
    fun `tracking gap abandons partial attempt without creating a valid rep`() {
        val evaluator = PushUpLiveEvaluator()
        evaluator.update(sample(0, PoseShape.TOP))
        evaluator.update(sample(150, PoseShape.MID))
        val blind = evaluator.update(
            sample(1_000, PoseShape.MID).copy(
                validity = ai.senp.core.contracts.FrameValidity(
                    ai.senp.core.contracts.FrameValidityStatus.BLIND,
                    0.0,
                    setOf(ai.senp.core.contracts.FrameValidityReason.NO_PERSON),
                ),
            ),
        )

        assertEquals(PushUpPhase.SEARCHING, blind.phase)
        assertEquals(0, blind.correctReps)
        assertEquals(1, blind.rejectedAttempts)
        assertEquals(PushUpCue.NO_POSE, blind.cue)
    }

    @Test
    fun `persistent body violation remains rejected when the recovery frame crosses the duration threshold`() {
        val evaluator = PushUpLiveEvaluator()
        val feedback = listOf(
            sample(0, PoseShape.TOP),
            sample(150, PoseShape.MID),
            sample(300, PoseShape.BOTTOM, sag = true),
            sample(450, PoseShape.BOTTOM),
            sample(600, PoseShape.MID),
            sample(850, PoseShape.TOP),
        ).map(evaluator::update).last()

        assertEquals(0, feedback.correctReps)
        assertEquals(1, feedback.rejectedAttempts)
    }

    @Test
    fun `duplicate live timestamps are rejected`() {
        val evaluator = PushUpLiveEvaluator()
        evaluator.update(sample(100, PoseShape.TOP))

        assertFailsWith<IllegalArgumentException> {
            evaluator.update(sample(100, PoseShape.TOP))
        }
    }

    @Test
    fun `low confidence pose never arms a repetition`() {
        val evaluator = PushUpLiveEvaluator()
        val lowConfidence = sample(0, PoseShape.TOP).copy(
            landmarks = sample(1, PoseShape.TOP).landmarks.map { landmark ->
                landmark.copy(visibility = 0.2, presence = 0.2)
            },
        )
        val feedback = evaluator.update(lowConfidence)

        assertEquals(PushUpPhase.SEARCHING, feedback.phase)
        assertEquals(PushUpCue.HOLD_STEADY, feedback.cue)
        assertEquals(0, feedback.correctReps)
    }

    private enum class PoseShape { TOP, MID, BOTTOM }

    private fun sample(
        timestampMs: Long,
        shape: PoseShape,
        sag: Boolean = false,
        frontFacing: Boolean = false,
    ): PoseFrame {
        val points = PoseLandmarkId.entries.associateWith { ImageLandmark(0.5, 0.5, 0.0) }.toMutableMap()
        val sideOffset = if (frontFacing) 0.24 else 0.008
        val shoulderY = 0.42
        val hipY = if (sag) 0.73 else 0.53
        val ankleY = 0.64

        fun setSide(left: Boolean, offset: Double) {
            val shoulder = if (left) PoseLandmarkId.LEFT_SHOULDER else PoseLandmarkId.RIGHT_SHOULDER
            val elbow = if (left) PoseLandmarkId.LEFT_ELBOW else PoseLandmarkId.RIGHT_ELBOW
            val wrist = if (left) PoseLandmarkId.LEFT_WRIST else PoseLandmarkId.RIGHT_WRIST
            val hip = if (left) PoseLandmarkId.LEFT_HIP else PoseLandmarkId.RIGHT_HIP
            val ankle = if (left) PoseLandmarkId.LEFT_ANKLE else PoseLandmarkId.RIGHT_ANKLE
            val x = offset
            points[shoulder] = ImageLandmark(0.28 + x, shoulderY, 0.0)
            points[elbow] = ImageLandmark(0.43 + x, 0.52, 0.0)
            points[wrist] = when (shape) {
                PoseShape.TOP -> ImageLandmark(0.58 + x, 0.62, 0.0)
                PoseShape.MID -> ImageLandmark(0.47 + x, 0.71, 0.0)
                PoseShape.BOTTOM -> ImageLandmark(0.33 + x, 0.67, 0.0)
            }
            points[hip] = ImageLandmark(0.54 + x, hipY, 0.0)
            points[ankle] = ImageLandmark(0.80 + x, ankleY, 0.0)
        }

        if (frontFacing) {
            setSide(left = true, offset = -sideOffset / 2.0)
            setSide(left = false, offset = sideOffset / 2.0)
        } else {
            setSide(left = true, offset = -sideOffset / 2.0)
            setSide(left = false, offset = sideOffset / 2.0)
        }

        return PoseFrame(
            timestamp = TimestampMs(timestampMs),
            diagnosticFrameIndex = timestampMs,
            landmarks = PoseLandmarkId.entries.map { id ->
                PoseLandmark(
                    id = id,
                    image = points.getValue(id),
                    visibility = 0.95,
                    presence = 0.95,
                )
            },
            validity = FrameValidity.Valid,
        )
    }
}

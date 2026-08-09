package ai.senp.motion

import ai.senp.core.contracts.FrameValidityStatus
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import kotlin.math.acos
import kotlin.math.hypot

/**
 * Stateful, timestamp-driven live push-up evaluator.
 *
 * This class is deliberately pure Kotlin/JVM. Camera and MediaPipe code only produce canonical
 * [PoseFrame] values; rep state, hysteresis, confidence gating, and form decisions live here so
 * they are deterministic and unit-testable.
 */
class PushUpLiveEvaluator(
    private val config: Config = Config(),
) {
    private var phase = PushUpPhase.SEARCHING
    private var selectedSide: PushUpSide? = null
    private var previousTimestampMs: Long? = null
    private var lastUsableTimestampMs: Long? = null
    private var phaseEnteredAtMs: Long = 0L
    private var attemptStartedAtMs: Long? = null
    private var bottomCandidateSinceMs: Long? = null
    private var bodyViolationSinceMs: Long? = null
    private var repCompromised = false
    private var correctReps = 0
    private var rejectedAttempts = 0
    private var lastCue = PushUpCue.NO_POSE

    @Synchronized
    fun reset() {
        phase = PushUpPhase.SEARCHING
        selectedSide = null
        previousTimestampMs = null
        lastUsableTimestampMs = null
        phaseEnteredAtMs = 0L
        attemptStartedAtMs = null
        bottomCandidateSinceMs = null
        bodyViolationSinceMs = null
        repCompromised = false
        correctReps = 0
        rejectedAttempts = 0
        lastCue = PushUpCue.NO_POSE
    }

    @Synchronized
    fun update(frame: PoseFrame): PushUpLiveFeedback {
        val timestampMs = frame.timestamp.value
        val previousTimestamp = previousTimestampMs
        require(previousTimestamp == null || timestampMs > previousTimestamp) {
            "live push-up timestamps must strictly increase"
        }
        previousTimestampMs = timestampMs

        if (frame.validity.status in setOf(FrameValidityStatus.BLIND, FrameValidityStatus.CONTINUITY_BREAK)) {
            return unusable(timestampMs, PushUpCue.NO_POSE, frame.validity.confidence)
        }

        val measurement = measure(frame)
            ?: return unusable(timestampMs, PushUpCue.MOVE_FULL_BODY_IN_FRAME, frame.validity.confidence)

        if (measurement.confidence < config.minimumLandmarkConfidence) {
            return unusable(timestampMs, PushUpCue.HOLD_STEADY, measurement.confidence)
        }
        if (!measurement.fullBodyInFrame) {
            return unusable(timestampMs, PushUpCue.MOVE_FULL_BODY_IN_FRAME, measurement.confidence, measurement)
        }
        if (measurement.sideViewScore < config.minimumSideViewScore) {
            return unusable(timestampMs, PushUpCue.TURN_SIDEWAYS, measurement.confidence, measurement)
        }

        lastUsableTimestampMs = timestampMs
        updateBodyViolation(timestampMs, measurement.bodyLineDegrees)
        val cue = advance(timestampMs, measurement)
        lastCue = cue
        return feedback(timestampMs, cue, measurement)
    }

    private fun advance(timestampMs: Long, measurement: Measurement): PushUpCue {
        val elbow = measurement.elbowDegrees
        val bodyGood = measurement.bodyLineDegrees >= config.minimumBodyLineDegrees

        when (phase) {
            PushUpPhase.SEARCHING -> {
                if (elbow >= config.topEnterDegrees && bodyGood) {
                    transition(PushUpPhase.READY, timestampMs)
                    return PushUpCue.LOWER_CHEST
                }
                return if (!bodyGood) PushUpCue.KEEP_BODY_STRAIGHT else PushUpCue.GET_IN_START_POSITION
            }

            PushUpPhase.READY -> {
                if (!bodyGood) return PushUpCue.KEEP_BODY_STRAIGHT
                if (elbow <= config.topExitDegrees) {
                    attemptStartedAtMs = timestampMs
                    bottomCandidateSinceMs = null
                    bodyViolationSinceMs = null
                    repCompromised = false
                    transition(PushUpPhase.DESCENDING, timestampMs)
                    return PushUpCue.LOWER_CHEST
                }
                return PushUpCue.LOWER_CHEST
            }

            PushUpPhase.DESCENDING -> {
                if (bodyViolationPersisted(timestampMs)) repCompromised = true
                if (!bodyGood) {
                    bottomCandidateSinceMs = null
                    return PushUpCue.KEEP_BODY_STRAIGHT
                }
                if (elbow <= config.bottomEnterDegrees) {
                    val since = bottomCandidateSinceMs
                    if (since == null) {
                        bottomCandidateSinceMs = timestampMs
                    } else if (timestampMs - since >= config.minimumBottomDwellMs) {
                        transition(PushUpPhase.BOTTOM, timestampMs)
                        return PushUpCue.PRESS_UP
                    }
                    return PushUpCue.PRESS_UP
                }
                if (elbow >= config.topEnterDegrees && timestampMs - phaseEnteredAtMs >= config.minimumTransitionDwellMs) {
                    rejectedAttempts++
                    clearAttempt()
                    transition(PushUpPhase.READY, timestampMs)
                    return PushUpCue.GO_LOWER
                }
                return PushUpCue.GO_LOWER
            }

            PushUpPhase.BOTTOM -> {
                if (bodyViolationPersisted(timestampMs)) repCompromised = true
                if (!bodyGood) return PushUpCue.KEEP_BODY_STRAIGHT
                if (elbow >= config.bottomExitDegrees) {
                    transition(PushUpPhase.ASCENDING, timestampMs)
                    return PushUpCue.PRESS_UP
                }
                return PushUpCue.PRESS_UP
            }

            PushUpPhase.ASCENDING -> {
                if (bodyViolationPersisted(timestampMs)) repCompromised = true
                if (!bodyGood) return PushUpCue.KEEP_BODY_STRAIGHT
                if (elbow >= config.topEnterDegrees) {
                    val started = attemptStartedAtMs
                    val duration = if (started == null) Long.MAX_VALUE else timestampMs - started
                    val validDuration = duration in config.minimumRepDurationMs..config.maximumRepDurationMs
                    val counted = !repCompromised && validDuration
                    if (counted) correctReps++ else rejectedAttempts++
                    clearAttempt()
                    transition(PushUpPhase.READY, timestampMs)
                    return if (counted) PushUpCue.GOOD_REP else PushUpCue.KEEP_BODY_STRAIGHT
                }
                return if (elbow >= config.lockoutPromptDegrees) PushUpCue.LOCK_OUT else PushUpCue.PRESS_UP
            }
        }
    }

    private fun updateBodyViolation(timestampMs: Long, bodyLineDegrees: Double) {
        if (bodyLineDegrees < config.minimumBodyLineDegrees) {
            if (bodyViolationSinceMs == null) bodyViolationSinceMs = timestampMs
        } else {
            val since = bodyViolationSinceMs
            if (since != null && timestampMs - since >= config.maximumBodyViolationMs) {
                repCompromised = true
            }
            bodyViolationSinceMs = null
        }
    }

    private fun bodyViolationPersisted(timestampMs: Long): Boolean {
        val since = bodyViolationSinceMs ?: return false
        return timestampMs - since >= config.maximumBodyViolationMs
    }

    private fun unusable(
        timestampMs: Long,
        cue: PushUpCue,
        confidence: Double,
        measurement: Measurement? = null,
    ): PushUpLiveFeedback {
        val lastUsable = lastUsableTimestampMs
        if (lastUsable != null && timestampMs - lastUsable >= config.trackingResetGapMs) {
            abandonAttempt()
            phase = PushUpPhase.SEARCHING
            selectedSide = null
        }
        lastCue = cue
        return PushUpLiveFeedback(
            timestampMs = timestampMs,
            correctReps = correctReps,
            rejectedAttempts = rejectedAttempts,
            phase = phase,
            cue = cue,
            selectedSide = measurement?.side ?: selectedSide,
            elbowDegrees = measurement?.elbowDegrees,
            bodyLineDegrees = measurement?.bodyLineDegrees,
            sideViewScore = measurement?.sideViewScore ?: 0.0,
            trackingConfidence = confidence.coerceIn(0.0, 1.0),
            fullBodyInFrame = measurement?.fullBodyInFrame ?: false,
        )
    }

    private fun feedback(timestampMs: Long, cue: PushUpCue, measurement: Measurement): PushUpLiveFeedback =
        PushUpLiveFeedback(
            timestampMs = timestampMs,
            correctReps = correctReps,
            rejectedAttempts = rejectedAttempts,
            phase = phase,
            cue = cue,
            selectedSide = measurement.side,
            elbowDegrees = measurement.elbowDegrees,
            bodyLineDegrees = measurement.bodyLineDegrees,
            sideViewScore = measurement.sideViewScore,
            trackingConfidence = measurement.confidence,
            fullBodyInFrame = measurement.fullBodyInFrame,
        )

    private fun measure(frame: PoseFrame): Measurement? {
        val byId = frame.landmarks.associateBy(PoseLandmark::id)
        val left = sideMeasurement(byId, PushUpSide.LEFT)
        val right = sideMeasurement(byId, PushUpSide.RIGHT)
        val chosen = chooseSide(left, right) ?: return null
        selectedSide = chosen.side

        val leftShoulder = byId.getValue(PoseLandmarkId.LEFT_SHOULDER).image
        val rightShoulder = byId.getValue(PoseLandmarkId.RIGHT_SHOULDER).image
        val leftHip = byId.getValue(PoseLandmarkId.LEFT_HIP).image
        val rightHip = byId.getValue(PoseLandmarkId.RIGHT_HIP).image
        val shoulderSpan = distance(leftShoulder, rightShoulder)
        val hipSpan = distance(leftHip, rightHip)
        val bodyLength = distance(chosen.shoulder.image, chosen.ankle.image).coerceAtLeast(1e-6)
        val crossBodyRatio = ((shoulderSpan + hipSpan) / 2.0) / bodyLength
        val sideViewScore = (1.0 - crossBodyRatio / config.frontViewCrossBodyRatio).coerceIn(0.0, 1.0)

        val fullBody = listOf(chosen.shoulder, chosen.elbow, chosen.wrist, chosen.hip, chosen.ankle)
            .all { landmark -> insideFrame(landmark.image, config.frameMargin) }

        return Measurement(
            side = chosen.side,
            elbowDegrees = angle(chosen.shoulder.image, chosen.elbow.image, chosen.wrist.image),
            bodyLineDegrees = angle(chosen.shoulder.image, chosen.hip.image, chosen.ankle.image),
            sideViewScore = sideViewScore,
            confidence = chosen.confidence,
            fullBodyInFrame = fullBody,
        )
    }

    private fun sideMeasurement(byId: Map<PoseLandmarkId, PoseLandmark>, side: PushUpSide): SideMeasurement? {
        val ids = when (side) {
            PushUpSide.LEFT -> listOf(
                PoseLandmarkId.LEFT_SHOULDER,
                PoseLandmarkId.LEFT_ELBOW,
                PoseLandmarkId.LEFT_WRIST,
                PoseLandmarkId.LEFT_HIP,
                PoseLandmarkId.LEFT_ANKLE,
            )
            PushUpSide.RIGHT -> listOf(
                PoseLandmarkId.RIGHT_SHOULDER,
                PoseLandmarkId.RIGHT_ELBOW,
                PoseLandmarkId.RIGHT_WRIST,
                PoseLandmarkId.RIGHT_HIP,
                PoseLandmarkId.RIGHT_ANKLE,
            )
        }
        val landmarks = ids.map { byId[it] ?: return null }
        if (landmarks.any { !finite(it.image) }) return null
        val confidences = landmarks.map(::effectiveConfidence)
        val confidence = confidences.minOrNull() ?: return null
        return SideMeasurement(
            side = side,
            shoulder = landmarks[0],
            elbow = landmarks[1],
            wrist = landmarks[2],
            hip = landmarks[3],
            ankle = landmarks[4],
            confidence = confidence,
        )
    }

    private fun chooseSide(left: SideMeasurement?, right: SideMeasurement?): SideMeasurement? {
        if (left == null) return right
        if (right == null) return left
        return when (selectedSide) {
            PushUpSide.LEFT -> if (right.confidence > left.confidence + config.sideSwitchConfidenceMargin) right else left
            PushUpSide.RIGHT -> if (left.confidence > right.confidence + config.sideSwitchConfidenceMargin) left else right
            null -> if (left.confidence >= right.confidence) left else right
        }
    }

    private fun transition(next: PushUpPhase, timestampMs: Long) {
        phase = next
        phaseEnteredAtMs = timestampMs
    }

    private fun clearAttempt() {
        attemptStartedAtMs = null
        bottomCandidateSinceMs = null
        bodyViolationSinceMs = null
        repCompromised = false
    }

    private fun abandonAttempt() {
        if (phase in setOf(PushUpPhase.DESCENDING, PushUpPhase.BOTTOM, PushUpPhase.ASCENDING)) {
            rejectedAttempts++
        }
        clearAttempt()
    }

    data class Config(
        val minimumLandmarkConfidence: Double = 0.45,
        val minimumSideViewScore: Double = 0.45,
        val frontViewCrossBodyRatio: Double = 0.72,
        val sideSwitchConfidenceMargin: Double = 0.12,
        val frameMargin: Double = 0.015,
        val topEnterDegrees: Double = 158.0,
        val topExitDegrees: Double = 145.0,
        val bottomEnterDegrees: Double = 100.0,
        val bottomExitDegrees: Double = 116.0,
        val lockoutPromptDegrees: Double = 145.0,
        val minimumBodyLineDegrees: Double = 155.0,
        val minimumBottomDwellMs: Long = 70L,
        val minimumTransitionDwellMs: Long = 80L,
        val maximumBodyViolationMs: Long = 120L,
        val minimumRepDurationMs: Long = 400L,
        val maximumRepDurationMs: Long = 10_000L,
        val trackingResetGapMs: Long = 700L,
    ) {
        init {
            require(minimumLandmarkConfidence in 0.0..1.0)
            require(minimumSideViewScore in 0.0..1.0)
            require(frontViewCrossBodyRatio > 0.0)
            require(sideSwitchConfidenceMargin in 0.0..1.0)
            require(frameMargin in 0.0..<0.5)
            require(bottomEnterDegrees < bottomExitDegrees)
            require(bottomExitDegrees < topExitDegrees)
            require(topExitDegrees < topEnterDegrees)
            require(minimumBodyLineDegrees in 90.0..180.0)
            require(listOf(minimumBottomDwellMs, minimumTransitionDwellMs, maximumBodyViolationMs, minimumRepDurationMs, maximumRepDurationMs, trackingResetGapMs).all { it >= 0L })
            require(maximumRepDurationMs >= minimumRepDurationMs)
        }
    }

    private data class Measurement(
        val side: PushUpSide,
        val elbowDegrees: Double,
        val bodyLineDegrees: Double,
        val sideViewScore: Double,
        val confidence: Double,
        val fullBodyInFrame: Boolean,
    )

    private data class SideMeasurement(
        val side: PushUpSide,
        val shoulder: PoseLandmark,
        val elbow: PoseLandmark,
        val wrist: PoseLandmark,
        val hip: PoseLandmark,
        val ankle: PoseLandmark,
        val confidence: Double,
    )
}

enum class PushUpSide { LEFT, RIGHT }

enum class PushUpPhase { SEARCHING, READY, DESCENDING, BOTTOM, ASCENDING }

enum class PushUpCue {
    NO_POSE,
    TURN_SIDEWAYS,
    MOVE_FULL_BODY_IN_FRAME,
    HOLD_STEADY,
    GET_IN_START_POSITION,
    LOWER_CHEST,
    GO_LOWER,
    KEEP_BODY_STRAIGHT,
    PRESS_UP,
    LOCK_OUT,
    GOOD_REP,
}

data class PushUpLiveFeedback(
    val timestampMs: Long,
    val correctReps: Int,
    val rejectedAttempts: Int,
    val phase: PushUpPhase,
    val cue: PushUpCue,
    val selectedSide: PushUpSide?,
    val elbowDegrees: Double?,
    val bodyLineDegrees: Double?,
    val sideViewScore: Double,
    val trackingConfidence: Double,
    val fullBodyInFrame: Boolean,
)

private fun effectiveConfidence(landmark: PoseLandmark): Double =
    minOf(landmark.visibility ?: 0.0, landmark.presence ?: 0.0).coerceIn(0.0, 1.0)

private fun finite(point: ImageLandmark): Boolean = point.x.isFinite() && point.y.isFinite() && point.z.isFinite()

private fun insideFrame(point: ImageLandmark, margin: Double): Boolean =
    point.x in margin..(1.0 - margin) && point.y in margin..(1.0 - margin)

private fun distance(a: ImageLandmark, b: ImageLandmark): Double = hypot(a.x - b.x, a.y - b.y)

private fun angle(first: ImageLandmark, vertex: ImageLandmark, third: ImageLandmark): Double {
    val ax = first.x - vertex.x
    val ay = first.y - vertex.y
    val bx = third.x - vertex.x
    val by = third.y - vertex.y
    val aLength = hypot(ax, ay)
    val bLength = hypot(bx, by)
    if (aLength <= 1e-9 || bLength <= 1e-9) return 0.0
    val cosine = ((ax * bx + ay * by) / (aLength * bLength)).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(cosine))
}

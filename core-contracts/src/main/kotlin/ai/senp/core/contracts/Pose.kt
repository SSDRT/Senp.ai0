package ai.senp.core.contracts

import kotlinx.serialization.Serializable

@Serializable
enum class PoseLandmarkId(val index: Int) {
    NOSE(0), LEFT_EYE_INNER(1), LEFT_EYE(2), LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4), RIGHT_EYE(5), RIGHT_EYE_OUTER(6), LEFT_EAR(7), RIGHT_EAR(8),
    MOUTH_LEFT(9), MOUTH_RIGHT(10), LEFT_SHOULDER(11), RIGHT_SHOULDER(12),
    LEFT_ELBOW(13), RIGHT_ELBOW(14), LEFT_WRIST(15), RIGHT_WRIST(16),
    LEFT_PINKY(17), RIGHT_PINKY(18), LEFT_INDEX(19), RIGHT_INDEX(20),
    LEFT_THUMB(21), RIGHT_THUMB(22), LEFT_HIP(23), RIGHT_HIP(24),
    LEFT_KNEE(25), RIGHT_KNEE(26), LEFT_ANKLE(27), RIGHT_ANKLE(28),
    LEFT_HEEL(29), RIGHT_HEEL(30), LEFT_FOOT_INDEX(31), RIGHT_FOOT_INDEX(32);

    companion object {
        const val COUNT: Int = 33
        fun fromIndex(index: Int): PoseLandmarkId = entries.getOrNull(index)
            ?: throw IllegalArgumentException("landmark index must be in 0..32")
    }
}

@Serializable
data class ImageLandmark(val x: Double, val y: Double, val z: Double) {
    init { requireFinite(x, "image landmark x"); requireFinite(y, "image landmark y"); requireFinite(z, "image landmark z") }
}

@Serializable
data class WorldLandmark(val xMeters: Double, val yMeters: Double, val zMeters: Double) {
    init { requireFinite(xMeters, "world landmark x"); requireFinite(yMeters, "world landmark y"); requireFinite(zMeters, "world landmark z") }
}

@Serializable
data class PoseLandmark(
    val id: PoseLandmarkId,
    val image: ImageLandmark,
    val world: WorldLandmark? = null,
    val visibility: Double? = null,
    val presence: Double? = null,
) {
    init {
        visibility?.let { requireProbability(it, "landmark visibility") }
        presence?.let { requireProbability(it, "landmark presence") }
    }
}

@Serializable
enum class FrameValidityStatus { VALID, REPAIRED, DEGRADED, BLIND, CONTINUITY_BREAK }

@Serializable
enum class FrameValidityReason {
    BELOW_DETECTION_THRESHOLD, BELOW_PRESENCE_THRESHOLD, BELOW_TRACKING_THRESHOLD,
    SHORT_GAP_INTERPOLATION, LONG_GAP, OUT_OF_FRAME, NON_FINITE_INPUT,
    NO_PERSON, UNUSABLE_TRACKING, TRACKING_RESET, LEFT_RIGHT_SWAP, IMPOSSIBLE_PROPORTION,
}

@Serializable
data class FrameValidity(
    val status: FrameValidityStatus,
    val confidence: Double,
    val reasons: Set<FrameValidityReason> = emptySet(),
) {
    init {
        requireProbability(confidence, "frame validity confidence")
        require(status != FrameValidityStatus.VALID || reasons.isEmpty()) { "valid frames cannot contain invalidity reasons" }
        require(status == FrameValidityStatus.VALID || reasons.isNotEmpty()) { "non-valid frames require at least one reason" }
    }
    companion object { val Valid = FrameValidity(FrameValidityStatus.VALID, 1.0) }
}

@Serializable
data class PoseFrame(
    val timestamp: TimestampMs,
    val diagnosticFrameIndex: Long,
    val landmarks: List<PoseLandmark>,
    val validity: FrameValidity,
) {
    init {
        require(diagnosticFrameIndex >= 0) { "diagnostic frame index must be non-negative" }
        require(landmarks.size == PoseLandmarkId.COUNT) { "pose frame must contain exactly 33 landmarks" }
        require(landmarks.map(PoseLandmark::id) == PoseLandmarkId.entries) { "pose landmarks must use canonical MediaPipe order" }
    }
}

@Serializable
data class PoseSequence(val role: VideoRole, val frames: List<PoseFrame>) {
    init { require(frames.zipWithNext().all { (a,b) -> a.timestamp < b.timestamp }) { "pose timestamps must be strictly increasing" } }
}

@Serializable
data class VideoPoseDiagnostics(
    val decodedFrameCount: Int,
    val sampledFrameCount: Int,
    val detectedFrameCount: Int,
    val noPersonFrameCount: Int,
    val unusableTrackingFrameCount: Int,
    val decodeNanos: Long,
    val inferenceNanos: Long,
    val maxInFlightFrames: Int,
    val peakInFlightFrames: Int,
) {
    init {
        require(listOf(decodedFrameCount, sampledFrameCount, detectedFrameCount, noPersonFrameCount, unusableTrackingFrameCount).all { it >= 0 })
        require(decodeNanos >= 0 && inferenceNanos >= 0)
        require(maxInFlightFrames > 0)
        require(peakInFlightFrames in 0..maxInFlightFrames) { "producer exceeded bounded in-flight frame limit" }
        require(detectedFrameCount + noPersonFrameCount + unusableTrackingFrameCount == sampledFrameCount)
    }
}

@Serializable
data class VideoPoseExtraction(
    val role: VideoRole,
    val duration: DurationMs,
    val poses: PoseSequence,
    val diagnostics: VideoPoseDiagnostics,
) {
    init {
        require(poses.role == role)
        require(poses.frames.size == diagnostics.sampledFrameCount)
        require(poses.frames.lastOrNull()?.timestamp?.value?.let { it < duration.value } ?: true)
    }
}

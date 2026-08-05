package ai.senp.motion

import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double = 0.0) {
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double): Vec3 = Vec3(x * scale, y * scale, z * scale)
    operator fun div(scale: Double): Vec3 = Vec3(x / scale, y / scale, z / scale)

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun squaredNorm(): Double = dot(this)
    fun norm(): Double = sqrt(squaredNorm())
    fun finite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

enum class BodySide { LEFT, RIGHT }

enum class LandmarkId(val index: Int, val side: BodySide? = null) {
    NOSE(0),
    LEFT_EYE_INNER(1, BodySide.LEFT),
    LEFT_EYE(2, BodySide.LEFT),
    LEFT_EYE_OUTER(3, BodySide.LEFT),
    RIGHT_EYE_INNER(4, BodySide.RIGHT),
    RIGHT_EYE(5, BodySide.RIGHT),
    RIGHT_EYE_OUTER(6, BodySide.RIGHT),
    LEFT_EAR(7, BodySide.LEFT),
    RIGHT_EAR(8, BodySide.RIGHT),
    MOUTH_LEFT(9, BodySide.LEFT),
    MOUTH_RIGHT(10, BodySide.RIGHT),
    LEFT_SHOULDER(11, BodySide.LEFT),
    RIGHT_SHOULDER(12, BodySide.RIGHT),
    LEFT_ELBOW(13, BodySide.LEFT),
    RIGHT_ELBOW(14, BodySide.RIGHT),
    LEFT_WRIST(15, BodySide.LEFT),
    RIGHT_WRIST(16, BodySide.RIGHT),
    LEFT_PINKY(17, BodySide.LEFT),
    RIGHT_PINKY(18, BodySide.RIGHT),
    LEFT_INDEX(19, BodySide.LEFT),
    RIGHT_INDEX(20, BodySide.RIGHT),
    LEFT_THUMB(21, BodySide.LEFT),
    RIGHT_THUMB(22, BodySide.RIGHT),
    LEFT_HIP(23, BodySide.LEFT),
    RIGHT_HIP(24, BodySide.RIGHT),
    LEFT_KNEE(25, BodySide.LEFT),
    RIGHT_KNEE(26, BodySide.RIGHT),
    LEFT_ANKLE(27, BodySide.LEFT),
    RIGHT_ANKLE(28, BodySide.RIGHT),
    LEFT_HEEL(29, BodySide.LEFT),
    RIGHT_HEEL(30, BodySide.RIGHT),
    LEFT_FOOT_INDEX(31, BodySide.LEFT),
    RIGHT_FOOT_INDEX(32, BodySide.RIGHT);

    fun mirrored(): LandmarkId = when (this) {
        NOSE -> NOSE
        LEFT_EYE_INNER -> RIGHT_EYE_INNER
        LEFT_EYE -> RIGHT_EYE
        LEFT_EYE_OUTER -> RIGHT_EYE_OUTER
        RIGHT_EYE_INNER -> LEFT_EYE_INNER
        RIGHT_EYE -> LEFT_EYE
        RIGHT_EYE_OUTER -> LEFT_EYE_OUTER
        LEFT_EAR -> RIGHT_EAR
        RIGHT_EAR -> LEFT_EAR
        MOUTH_LEFT -> MOUTH_RIGHT
        MOUTH_RIGHT -> MOUTH_LEFT
        LEFT_SHOULDER -> RIGHT_SHOULDER
        RIGHT_SHOULDER -> LEFT_SHOULDER
        LEFT_ELBOW -> RIGHT_ELBOW
        RIGHT_ELBOW -> LEFT_ELBOW
        LEFT_WRIST -> RIGHT_WRIST
        RIGHT_WRIST -> LEFT_WRIST
        LEFT_PINKY -> RIGHT_PINKY
        RIGHT_PINKY -> LEFT_PINKY
        LEFT_INDEX -> RIGHT_INDEX
        RIGHT_INDEX -> LEFT_INDEX
        LEFT_THUMB -> RIGHT_THUMB
        RIGHT_THUMB -> LEFT_THUMB
        LEFT_HIP -> RIGHT_HIP
        RIGHT_HIP -> LEFT_HIP
        LEFT_KNEE -> RIGHT_KNEE
        RIGHT_KNEE -> LEFT_KNEE
        LEFT_ANKLE -> RIGHT_ANKLE
        RIGHT_ANKLE -> LEFT_ANKLE
        LEFT_HEEL -> RIGHT_HEEL
        RIGHT_HEEL -> LEFT_HEEL
        LEFT_FOOT_INDEX -> RIGHT_FOOT_INDEX
        RIGHT_FOOT_INDEX -> LEFT_FOOT_INDEX
    }

    companion object {
        val COUNT: Int = entries.size
        val SIDE_PAIRS: List<Pair<LandmarkId, LandmarkId>> = entries
            .filter { it.side == BodySide.LEFT }
            .map { it to it.mirrored() }
    }
}

data class Landmark(
    val image: Vec3?,
    val world: Vec3? = null,
    val visibility: Double = 0.0,
    val presence: Double = 0.0,
    val repaired: Boolean = false,
) {
    init {
        require(visibility.isFinite() && visibility in 0.0..1.0) { "visibility must be finite and in [0,1]" }
        require(presence.isFinite() && presence in 0.0..1.0) { "presence must be finite and in [0,1]" }
    }

    fun hasFiniteImage(): Boolean = image?.finite() == true
    fun hasFiniteWorld(): Boolean = world?.finite() == true
}

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: List<Landmark>,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(landmarks.size == LandmarkId.COUNT) { "exactly ${LandmarkId.COUNT} landmarks are required" }
    }

    operator fun get(id: LandmarkId): Landmark = landmarks[id.index]
}

enum class FrameValidity {
    VALID,
    REPAIRED,
    DEGRADED,
    BLIND,
    CONTINUITY_BREAK,
}

enum class SidePolicy {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY,
    BEST_VISIBLE,
}

data class QualityWeights(
    val visibility: Double = 0.25,
    val presence: Double = 0.20,
    val requiredCoverage: Double = 0.35,
    val preferredQuality: Double = 0.20,
    val repairedPenalty: Double = 0.18,
    val clippingPenalty: Double = 0.30,
    val instabilityPenalty: Double = 0.30,
    val impossibleProportionPenalty: Double = 0.20,
) {
    init {
        val positive = listOf(visibility, presence, requiredCoverage, preferredQuality)
        require(positive.all { it.isFinite() && it >= 0.0 } && positive.sum() > 0.0)
        val penalties = listOf(repairedPenalty, clippingPenalty, instabilityPenalty, impossibleProportionPenalty)
        require(penalties.all { it.isFinite() && it in 0.0..1.0 })
    }
}

data class ExerciseProfile(
    val id: String,
    val required: Set<LandmarkId>,
    val preferred: Set<LandmarkId> = emptySet(),
    val sidePolicy: SidePolicy = SidePolicy.BOTH,
    val minimumRequiredCoverage: Double = 0.75,
    val weights: QualityWeights = QualityWeights(),
) {
    init {
        require(id.isNotBlank())
        require(required.isNotEmpty())
        require(minimumRequiredCoverage.isFinite() && minimumRequiredCoverage in 0.0..1.0)
    }
}

data class FrameSignals(
    val clipping: Double = 0.0,
    val instability: Double = 0.0,
) {
    init {
        require(clipping.isFinite() && clipping in 0.0..1.0)
        require(instability.isFinite() && instability in 0.0..1.0)
    }
}

data class GuardrailFlags(
    val leftRightSwapApplied: Boolean = false,
    val impossibleProportions: Boolean = false,
)

data class TrackedFrame(
    val frame: PoseFrame,
    val repairedLandmarks: Set<LandmarkId> = emptySet(),
    val continuityBreakLandmarks: Set<LandmarkId> = emptySet(),
)

data class QualityResult(
    val timestampMs: Long,
    val score: Double,
    val validity: FrameValidity,
    val selectedSide: BodySide?,
    val requiredCoverage: Double,
    val requiredVisibility: Double,
    val requiredPresence: Double,
    val preferredQuality: Double,
    val repairedFraction: Double,
    val clipping: Double,
    val instability: Double,
)

data class ProcessedFrame(
    val frame: PoseFrame,
    val quality: QualityResult,
    val repairedLandmarks: Set<LandmarkId>,
    val continuityBreakLandmarks: Set<LandmarkId>,
    val guardrails: GuardrailFlags,
)

data class MotionConfig(
    val minVisibility: Double = 0.50,
    val minPresence: Double = 0.50,
    val minAngleConfidence: Double = 0.50,
    val maxRepairGapMs: Long = 180L,
    val continuityBreakGapMs: Long = 300L,
    val emaHalfLifeMs: Long = 120L,
    val blindEnterThreshold: Double = 0.40,
    val usableThreshold: Double = 0.55,
    val blindEnterDurationMs: Long = 250L,
    val recoverDurationMs: Long = 200L,
    val repairedConfidenceCap: Double = 0.35,
    val repairedCoverageCredit: Double = 0.75,
    val sideSwitchMargin: Double = 0.08,
    val minScale: Double = 1e-6,
) {
    init {
        require(minVisibility.isFinite() && minVisibility in 0.0..1.0)
        require(minPresence.isFinite() && minPresence in 0.0..1.0)
        require(minAngleConfidence.isFinite() && minAngleConfidence in 0.0..1.0)
        require(maxRepairGapMs >= 0L)
        require(continuityBreakGapMs >= maxRepairGapMs)
        require(emaHalfLifeMs > 0L)
        require(blindEnterThreshold.isFinite() && blindEnterThreshold in 0.0..1.0)
        require(usableThreshold.isFinite() && usableThreshold in 0.0..1.0)
        require(blindEnterThreshold <= usableThreshold)
        require(blindEnterDurationMs >= 0L && recoverDurationMs >= 0L)
        require(repairedConfidenceCap.isFinite() && repairedConfidenceCap in 0.0..1.0)
        require(repairedCoverageCredit.isFinite() && repairedCoverageCredit in 0.0..1.0)
        require(sideSwitchMargin.isFinite() && sideSwitchMargin in 0.0..1.0)
        require(minScale.isFinite() && minScale > 0.0)
    }
}

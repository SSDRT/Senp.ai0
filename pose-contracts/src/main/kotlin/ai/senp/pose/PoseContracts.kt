package ai.senp.pose

/** Stable neutral 33-point pose schema. Ordinals intentionally match the model's documented order. */
enum class PoseLandmarkId(val index: Int) {
    NOSE(0),
    LEFT_EYE_INNER(1),
    LEFT_EYE(2),
    LEFT_EYE_OUTER(3),
    RIGHT_EYE_INNER(4),
    RIGHT_EYE(5),
    RIGHT_EYE_OUTER(6),
    LEFT_EAR(7),
    RIGHT_EAR(8),
    MOUTH_LEFT(9),
    MOUTH_RIGHT(10),
    LEFT_SHOULDER(11),
    RIGHT_SHOULDER(12),
    LEFT_ELBOW(13),
    RIGHT_ELBOW(14),
    LEFT_WRIST(15),
    RIGHT_WRIST(16),
    LEFT_PINKY(17),
    RIGHT_PINKY(18),
    LEFT_INDEX(19),
    RIGHT_INDEX(20),
    LEFT_THUMB(21),
    RIGHT_THUMB(22),
    LEFT_HIP(23),
    RIGHT_HIP(24),
    LEFT_KNEE(25),
    RIGHT_KNEE(26),
    LEFT_ANKLE(27),
    RIGHT_ANKLE(28),
    LEFT_HEEL(29),
    RIGHT_HEEL(30),
    LEFT_FOOT_INDEX(31),
    RIGHT_FOOT_INDEX(32);

    companion object {
        private val byIndex = entries.associateBy(PoseLandmarkId::index)
        fun fromIndex(index: Int): PoseLandmarkId =
            byIndex[index] ?: throw IllegalArgumentException("Unknown neutral pose landmark index: $index")
    }
}

data class LandmarkConfidence(
    val visibility: Float?,
    val presence: Float?,
) {
    init {
        require(visibility == null || visibility in 0f..1f) { "visibility must be null or in [0,1]" }
        require(presence == null || presence in 0f..1f) { "presence must be null or in [0,1]" }
    }
}

data class ImageLandmark(
    val xNormalized: Float,
    val yNormalized: Float,
    val zNormalized: Float,
    val confidence: LandmarkConfidence,
)

data class WorldLandmark(
    val xMeters: Float,
    val yMeters: Float,
    val zMeters: Float,
    val confidence: LandmarkConfidence,
)

data class PoseLandmark(
    val id: PoseLandmarkId,
    val image: ImageLandmark,
    val world: WorldLandmark,
)

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: List<PoseLandmark>,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(landmarks.size == PoseLandmarkId.entries.size) { "PoseFrame must contain all 33 landmarks" }
        require(landmarks.map(PoseLandmark::id) == PoseLandmarkId.entries) {
            "PoseFrame landmarks must be in neutral schema order"
        }
    }
}

data class PoseDiagnostics(
    val inferenceNanos: Long,
    val mappingNanos: Long,
    val visibleLandmarkCount: Int,
    val presentLandmarkCount: Int,
)

sealed interface UnusableTrackingReason {
    data class LandmarkCountMismatch(val imageCount: Int, val worldCount: Int) : UnusableTrackingReason
    data class InsufficientConfidence(
        val usableLandmarks: Int,
        val requiredLandmarks: Int,
        val minimumVisibility: Float,
        val minimumPresence: Float,
    ) : UnusableTrackingReason
}

sealed interface PoseOutcome {
    val timestampMs: Long
    val diagnostics: PoseDiagnostics

    data class Detected(
        val frame: PoseFrame,
        override val diagnostics: PoseDiagnostics,
    ) : PoseOutcome {
        override val timestampMs: Long get() = frame.timestampMs
    }

    data class NoPerson(
        override val timestampMs: Long,
        override val diagnostics: PoseDiagnostics,
    ) : PoseOutcome

    data class UnusableTracking(
        override val timestampMs: Long,
        val reason: UnusableTrackingReason,
        override val diagnostics: PoseDiagnostics,
    ) : PoseOutcome
}

sealed class PoseFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelLoad(message: String, cause: Throwable? = null) : PoseFailure(message, cause)
    class InvalidInput(message: String) : PoseFailure(message)
    class Inference(message: String, cause: Throwable? = null) : PoseFailure(message, cause)
    class NonMonotonicTimestamp(val previousMs: Long, val currentMs: Long) :
        PoseFailure("Pose timestamps must strictly increase: $previousMs -> $currentMs")
}

interface PoseEstimator : AutoCloseable {
    @Throws(PoseFailure::class)
    fun estimate(frame: PoseInputFrame): PoseOutcome
}

/** Synchronous frame view; the estimator never retains [argb8888] after [PoseEstimator.estimate]. */
data class PoseInputFrame(
    val timestampMs: Long,
    val width: Int,
    val height: Int,
    val argb8888: IntArray,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        require(argb8888.size == width * height) {
            "ARGB buffer size ${argb8888.size} does not match ${width}x$height"
        }
    }
}

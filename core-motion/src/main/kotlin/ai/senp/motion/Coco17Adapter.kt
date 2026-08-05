package ai.senp.motion

enum class Coco17LandmarkId(val index: Int, val mediaPipeId: LandmarkId) {
    NOSE(0, LandmarkId.NOSE),
    LEFT_EYE(1, LandmarkId.LEFT_EYE),
    RIGHT_EYE(2, LandmarkId.RIGHT_EYE),
    LEFT_EAR(3, LandmarkId.LEFT_EAR),
    RIGHT_EAR(4, LandmarkId.RIGHT_EAR),
    LEFT_SHOULDER(5, LandmarkId.LEFT_SHOULDER),
    RIGHT_SHOULDER(6, LandmarkId.RIGHT_SHOULDER),
    LEFT_ELBOW(7, LandmarkId.LEFT_ELBOW),
    RIGHT_ELBOW(8, LandmarkId.RIGHT_ELBOW),
    LEFT_WRIST(9, LandmarkId.LEFT_WRIST),
    RIGHT_WRIST(10, LandmarkId.RIGHT_WRIST),
    LEFT_HIP(11, LandmarkId.LEFT_HIP),
    RIGHT_HIP(12, LandmarkId.RIGHT_HIP),
    LEFT_KNEE(13, LandmarkId.LEFT_KNEE),
    RIGHT_KNEE(14, LandmarkId.RIGHT_KNEE),
    LEFT_ANKLE(15, LandmarkId.LEFT_ANKLE),
    RIGHT_ANKLE(16, LandmarkId.RIGHT_ANKLE);

    companion object {
        const val COUNT = 17
    }
}

data class Coco17Landmark(
    val image: Vec3?,
    val world: Vec3? = null,
    val confidence: Double,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "confidence must be finite and in [0,1]"
        }
    }
}

data class Coco17Frame(
    val timestampMs: Long,
    val landmarks: List<Coco17Landmark>,
) {
    init {
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(landmarks.size == Coco17LandmarkId.COUNT) {
            "exactly ${Coco17LandmarkId.COUNT} COCO-17 landmarks are required"
        }
    }
}

/**
 * Compatibility-only adapter for legacy backend fixtures and exports.
 *
 * Coordinates are copied without inventing MediaPipe-only landmarks or depth. A legacy confidence value is
 * copied to both visibility and presence. Unmapped MP33 landmarks remain explicitly absent.
 */
object Coco17Adapter {
    fun toPoseFrame(frame: Coco17Frame): PoseFrame {
        val mapped = MutableList(LandmarkId.COUNT) { missingLandmark() }
        for (id in Coco17LandmarkId.entries) {
            val legacy = frame.landmarks[id.index]
            mapped[id.mediaPipeId.index] = Landmark(
                image = legacy.image?.takeIf { it.finite() },
                world = legacy.world?.takeIf { it.finite() },
                visibility = legacy.confidence,
                presence = legacy.confidence,
            )
        }
        return PoseFrame(frame.timestampMs, mapped)
    }

    fun fromArrays(
        timestampMs: Long,
        image: List<Vec3?>,
        confidence: List<Double>,
        world: List<Vec3?>? = null,
    ): PoseFrame {
        require(image.size == Coco17LandmarkId.COUNT) { "image must contain 17 entries" }
        require(confidence.size == Coco17LandmarkId.COUNT) { "confidence must contain 17 entries" }
        require(world == null || world.size == Coco17LandmarkId.COUNT) { "world must be null or contain 17 entries" }
        return toPoseFrame(
            Coco17Frame(
                timestampMs = timestampMs,
                landmarks = Coco17LandmarkId.entries.map { id ->
                    Coco17Landmark(
                        image = image[id.index],
                        world = world?.get(id.index),
                        confidence = confidence[id.index],
                    )
                },
            ),
        )
    }

    private fun missingLandmark(): Landmark = Landmark(
        image = null,
        world = null,
        visibility = 0.0,
        presence = 0.0,
    )
}

package ai.senp.motion

enum class CoordinateSpace { IMAGE, WORLD }

enum class NormalizationStatus {
    NORMALIZED,
    MISSING_ANCHORS,
    DEGENERATE_SCALE,
    DEGENERATE_ORIENTATION,
}

data class NormalizationResult(
    val frame: PoseFrame,
    val status: NormalizationStatus,
    val coordinateSpace: CoordinateSpace,
    val root: Vec3? = null,
    val scale: Double? = null,
)

/** Root/torso-scale normalization with optional orientation for real world landmarks only. */
class PoseNormalizer(private val minScale: Double = 1e-6) {
    init {
        require(minScale.isFinite() && minScale > 0.0)
    }

    fun normalizeImage(frame: PoseFrame): NormalizationResult = normalize(
        frame = frame,
        coordinateSpace = CoordinateSpace.IMAGE,
        orientToBodyAxes = false,
    )

    fun normalizeWorld(frame: PoseFrame, orientToBodyAxes: Boolean = true): NormalizationResult = normalize(
        frame = frame,
        coordinateSpace = CoordinateSpace.WORLD,
        orientToBodyAxes = orientToBodyAxes,
    )

    private fun normalize(
        frame: PoseFrame,
        coordinateSpace: CoordinateSpace,
        orientToBodyAxes: Boolean,
    ): NormalizationResult {
        val leftHip = point(frame, LandmarkId.LEFT_HIP, coordinateSpace)
        val rightHip = point(frame, LandmarkId.RIGHT_HIP, coordinateSpace)
        val leftShoulder = point(frame, LandmarkId.LEFT_SHOULDER, coordinateSpace)
        val rightShoulder = point(frame, LandmarkId.RIGHT_SHOULDER, coordinateSpace)
        if (listOf(leftHip, rightHip, leftShoulder, rightShoulder).any { it == null }) {
            return NormalizationResult(frame, NormalizationStatus.MISSING_ANCHORS, coordinateSpace)
        }

        val root = (leftHip!! + rightHip!!) / 2.0
        val shoulderCenter = (leftShoulder!! + rightShoulder!!) / 2.0
        val scale = (shoulderCenter - root).norm()
        if (!scale.isFinite() || scale < minScale) {
            return NormalizationResult(frame, NormalizationStatus.DEGENERATE_SCALE, coordinateSpace, root, scale)
        }

        val axes = if (coordinateSpace == CoordinateSpace.WORLD && orientToBodyAxes) {
            bodyAxes(leftHip, rightHip, root, shoulderCenter)
                ?: return NormalizationResult(
                    frame,
                    NormalizationStatus.DEGENERATE_ORIENTATION,
                    coordinateSpace,
                    root,
                    scale,
                )
        } else {
            null
        }

        fun transform(point: Vec3?): Vec3? {
            if (point == null || !point.finite()) return null
            val centered = (point - root) / scale
            return axes?.let { (xAxis, yAxis, zAxis) ->
                Vec3(centered.dot(xAxis), centered.dot(yAxis), centered.dot(zAxis))
            } ?: centered
        }

        val normalized = frame.landmarks.map { landmark ->
            when (coordinateSpace) {
                CoordinateSpace.IMAGE -> landmark.copy(image = transform(landmark.image))
                CoordinateSpace.WORLD -> landmark.copy(world = transform(landmark.world))
            }
        }
        return NormalizationResult(
            frame = PoseFrame(frame.timestampMs, normalized),
            status = NormalizationStatus.NORMALIZED,
            coordinateSpace = coordinateSpace,
            root = root,
            scale = scale,
        )
    }

    private fun bodyAxes(
        leftHip: Vec3,
        rightHip: Vec3,
        root: Vec3,
        shoulderCenter: Vec3,
    ): Triple<Vec3, Vec3, Vec3>? {
        val xAxis = (rightHip - leftHip).unitOrNull() ?: return null
        val torsoUp = (shoulderCenter - root).unitOrNull() ?: return null
        val zAxis = xAxis.cross(torsoUp).unitOrNull() ?: return null
        val yAxis = zAxis.cross(xAxis).unitOrNull() ?: return null
        return Triple(xAxis, yAxis, zAxis)
    }

    private fun point(frame: PoseFrame, id: LandmarkId, space: CoordinateSpace): Vec3? {
        val point = when (space) {
            CoordinateSpace.IMAGE -> frame[id].image
            CoordinateSpace.WORLD -> frame[id].world
        }
        return point?.takeIf { it.finite() }
    }

    private fun Vec3.unitOrNull(): Vec3? {
        val norm = norm()
        return if (!norm.isFinite() || norm < minScale) null else this / norm
    }
}

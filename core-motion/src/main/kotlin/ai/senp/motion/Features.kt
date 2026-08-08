package ai.senp.motion

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2

internal data class AngleDefinition(
    val first: LandmarkId,
    val vertex: LandmarkId,
    val third: LandmarkId,
)

internal data class TorsoFeatures(
    val pelvisCenter: Vec3,
    val shoulderCenter: Vec3,
    val torsoVector: Vec3,
    val torsoLength: Double,
    val leanFromVerticalDeg: Double,
    val shoulderTiltDeg: Double,
    val hipTiltDeg: Double,
)

internal data class TrajectoryPoint(val timestampMs: Long, val position: Vec3)

internal data class TimedVelocity(val timestampMs: Long, val velocityPerSecond: Vec3)

/** Deterministic features over the full MediaPipe 33-landmark contract. */
internal object MotionFeatures {
    val angleDefinitions: Map<String, AngleDefinition> = linkedMapOf(
        "left_shoulder" to AngleDefinition(LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_HIP),
        "right_shoulder" to AngleDefinition(LandmarkId.RIGHT_ELBOW, LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_HIP),
        "left_elbow" to AngleDefinition(LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST),
        "right_elbow" to AngleDefinition(LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_ELBOW, LandmarkId.RIGHT_WRIST),
        "left_wrist" to AngleDefinition(LandmarkId.LEFT_ELBOW, LandmarkId.LEFT_WRIST, LandmarkId.LEFT_INDEX),
        "right_wrist" to AngleDefinition(LandmarkId.RIGHT_ELBOW, LandmarkId.RIGHT_WRIST, LandmarkId.RIGHT_INDEX),
        "left_hip" to AngleDefinition(LandmarkId.LEFT_SHOULDER, LandmarkId.LEFT_HIP, LandmarkId.LEFT_KNEE),
        "right_hip" to AngleDefinition(LandmarkId.RIGHT_SHOULDER, LandmarkId.RIGHT_HIP, LandmarkId.RIGHT_KNEE),
        "left_knee" to AngleDefinition(LandmarkId.LEFT_HIP, LandmarkId.LEFT_KNEE, LandmarkId.LEFT_ANKLE),
        "right_knee" to AngleDefinition(LandmarkId.RIGHT_HIP, LandmarkId.RIGHT_KNEE, LandmarkId.RIGHT_ANKLE),
        "left_ankle" to AngleDefinition(LandmarkId.LEFT_KNEE, LandmarkId.LEFT_ANKLE, LandmarkId.LEFT_FOOT_INDEX),
        "right_ankle" to AngleDefinition(LandmarkId.RIGHT_KNEE, LandmarkId.RIGHT_ANKLE, LandmarkId.RIGHT_FOOT_INDEX),
    )

    fun angles(
        frame: PoseFrame,
        validity: FrameValidity,
        minVisibility: Double = 0.5,
        minPresence: Double = 0.5,
        coordinateSpace: CoordinateSpace = CoordinateSpace.IMAGE,
    ): Map<String, Double?> {
        require(minVisibility in 0.0..1.0 && minPresence in 0.0..1.0)
        if (validity == FrameValidity.BLIND || validity == FrameValidity.CONTINUITY_BREAK) {
            return angleDefinitions.mapValues { null }
        }
        return angleDefinitions.mapValues { (_, definition) ->
            angleFor(frame, definition, minVisibility, minPresence, coordinateSpace)
        }
    }

    fun angleFor(
        frame: PoseFrame,
        definition: AngleDefinition,
        minVisibility: Double = 0.5,
        minPresence: Double = 0.5,
        coordinateSpace: CoordinateSpace = CoordinateSpace.IMAGE,
    ): Double? {
        val landmarks = listOf(frame[definition.first], frame[definition.vertex], frame[definition.third])
        if (landmarks.any { it.visibility < minVisibility || it.presence < minPresence }) return null
        val points = landmarks.map { point(it, coordinateSpace, planarImage = true) }
        if (points.any { it == null }) return null
        return vectorAngleDeg(points[0]!! - points[1]!!, points[2]!! - points[1]!!)
    }

    fun angularVelocity(
        previousAngleDeg: Double?,
        currentAngleDeg: Double?,
        previousTimestampMs: Long,
        currentTimestampMs: Long,
    ): Double? {
        if (previousAngleDeg == null || currentAngleDeg == null) return null
        if (!previousAngleDeg.isFinite() || !currentAngleDeg.isFinite()) return null
        val elapsedMs = currentTimestampMs - previousTimestampMs
        if (elapsedMs <= 0L) return null
        return (currentAngleDeg - previousAngleDeg) * 1000.0 / elapsedMs.toDouble()
    }

    fun angularVelocities(
        previousAngles: Map<String, Double?>,
        currentAngles: Map<String, Double?>,
        previousTimestampMs: Long,
        currentTimestampMs: Long,
    ): Map<String, Double?> = angleDefinitions.keys.associateWith { name ->
        angularVelocity(previousAngles[name], currentAngles[name], previousTimestampMs, currentTimestampMs)
    }

    fun torso(
        frame: PoseFrame,
        coordinateSpace: CoordinateSpace = CoordinateSpace.IMAGE,
        minVisibility: Double = 0.5,
        minPresence: Double = 0.5,
    ): TorsoFeatures? {
        val anchorIds = listOf(
            LandmarkId.LEFT_SHOULDER,
            LandmarkId.RIGHT_SHOULDER,
            LandmarkId.LEFT_HIP,
            LandmarkId.RIGHT_HIP,
        )
        if (anchorIds.any { id ->
                val landmark = frame[id]
                landmark.visibility < minVisibility ||
                    landmark.presence < minPresence ||
                    point(landmark, coordinateSpace, planarImage = true) == null
            }
        ) return null

        val leftShoulder = point(frame[LandmarkId.LEFT_SHOULDER], coordinateSpace, planarImage = true)!!
        val rightShoulder = point(frame[LandmarkId.RIGHT_SHOULDER], coordinateSpace, planarImage = true)!!
        val leftHip = point(frame[LandmarkId.LEFT_HIP], coordinateSpace, planarImage = true)!!
        val rightHip = point(frame[LandmarkId.RIGHT_HIP], coordinateSpace, planarImage = true)!!
        val pelvisCenter = (leftHip + rightHip) / 2.0
        val shoulderCenter = (leftShoulder + rightShoulder) / 2.0
        val torsoVector = shoulderCenter - pelvisCenter
        val torsoLength = torsoVector.norm()
        if (torsoLength < 1e-9) return null
        val vertical = when (coordinateSpace) {
            CoordinateSpace.IMAGE -> Vec3(0.0, -1.0, 0.0)
            CoordinateSpace.WORLD -> Vec3(0.0, 1.0, 0.0)
        }
        return TorsoFeatures(
            pelvisCenter = pelvisCenter,
            shoulderCenter = shoulderCenter,
            torsoVector = torsoVector,
            torsoLength = torsoLength,
            leanFromVerticalDeg = vectorAngleDeg(torsoVector, vertical) ?: return null,
            shoulderTiltDeg = planarTiltDeg(rightShoulder - leftShoulder),
            hipTiltDeg = planarTiltDeg(rightHip - leftHip),
        )
    }

    fun trajectory(
        frames: List<PoseFrame>,
        id: LandmarkId,
        coordinateSpace: CoordinateSpace = CoordinateSpace.IMAGE,
        minVisibility: Double = 0.5,
        minPresence: Double = 0.5,
    ): List<TrajectoryPoint> = frames.mapNotNull { frame ->
        val landmark = frame[id]
        if (landmark.visibility < minVisibility || landmark.presence < minPresence) return@mapNotNull null
        point(landmark, coordinateSpace, planarImage = true)?.let { TrajectoryPoint(frame.timestampMs, it) }
    }

    fun trajectoryVelocities(points: List<TrajectoryPoint>): List<TimedVelocity> = points.zipWithNext().mapNotNull { (a, b) ->
        val elapsedMs = b.timestampMs - a.timestampMs
        if (elapsedMs <= 0L) null else TimedVelocity(
            timestampMs = b.timestampMs,
            velocityPerSecond = (b.position - a.position) * (1000.0 / elapsedMs.toDouble()),
        )
    }

    private fun point(
        landmark: Landmark,
        coordinateSpace: CoordinateSpace,
        planarImage: Boolean = false,
    ): Vec3? {
        val value = when (coordinateSpace) {
            CoordinateSpace.IMAGE -> landmark.image
            CoordinateSpace.WORLD -> landmark.world
        }
        val finite = value?.takeIf { it.finite() } ?: return null
        return if (coordinateSpace == CoordinateSpace.IMAGE && planarImage) {
            Vec3(finite.x, finite.y, 0.0)
        } else {
            finite
        }
    }

    private fun vectorAngleDeg(first: Vec3, second: Vec3): Double? {
        val denominator = first.norm() * second.norm()
        if (!denominator.isFinite() || denominator < 1e-9) return null
        val cosine = (first.dot(second) / denominator).coerceIn(-1.0, 1.0)
        return acos(cosine) * 180.0 / PI
    }

    private fun planarTiltDeg(vector: Vec3): Double = atan2(vector.y, vector.x) * 180.0 / PI
}

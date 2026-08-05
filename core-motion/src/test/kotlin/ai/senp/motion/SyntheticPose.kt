package ai.senp.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

object SyntheticPose {
    fun frame(
        timestampMs: Long,
        phase: Double = 0.0,
        noise: Double = 0.0,
        confidence: Double = 0.95,
        seed: Int = timestampMs.toInt(),
    ): PoseFrame {
        val random = Random(seed)
        fun jitter(): Double = if (noise == 0.0) 0.0 else (random.nextDouble() * 2.0 - 1.0) * noise
        val image = MutableList(LandmarkId.COUNT) { Vec3(0.5, 0.5) }
        fun set(id: LandmarkId, x: Double, y: Double, z: Double = 0.0) {
            image[id.index] = Vec3(x + jitter(), y + jitter(), z + jitter())
        }

        val curl = 0.11 * sin(phase * 2.0 * PI)
        val handLift = abs(curl) * 0.9
        set(LandmarkId.NOSE, 0.50, 0.19)
        set(LandmarkId.LEFT_EYE_INNER, 0.485, 0.175)
        set(LandmarkId.LEFT_EYE, 0.47, 0.175)
        set(LandmarkId.LEFT_EYE_OUTER, 0.455, 0.178)
        set(LandmarkId.RIGHT_EYE_INNER, 0.515, 0.175)
        set(LandmarkId.RIGHT_EYE, 0.53, 0.175)
        set(LandmarkId.RIGHT_EYE_OUTER, 0.545, 0.178)
        set(LandmarkId.LEFT_EAR, 0.43, 0.195)
        set(LandmarkId.RIGHT_EAR, 0.57, 0.195)
        set(LandmarkId.MOUTH_LEFT, 0.48, 0.22)
        set(LandmarkId.MOUTH_RIGHT, 0.52, 0.22)

        set(LandmarkId.LEFT_SHOULDER, 0.42, 0.36)
        set(LandmarkId.RIGHT_SHOULDER, 0.58, 0.36)
        set(LandmarkId.LEFT_ELBOW, 0.34, 0.49)
        set(LandmarkId.RIGHT_ELBOW, 0.66, 0.49)
        set(LandmarkId.LEFT_WRIST, 0.29 + curl, 0.62 - handLift)
        set(LandmarkId.RIGHT_WRIST, 0.71 - curl, 0.62 - handLift)
        set(LandmarkId.LEFT_PINKY, 0.275 + curl, 0.635 - handLift)
        set(LandmarkId.RIGHT_PINKY, 0.725 - curl, 0.635 - handLift)
        set(LandmarkId.LEFT_INDEX, 0.27 + curl, 0.615 - handLift)
        set(LandmarkId.RIGHT_INDEX, 0.73 - curl, 0.615 - handLift)
        set(LandmarkId.LEFT_THUMB, 0.285 + curl, 0.60 - handLift)
        set(LandmarkId.RIGHT_THUMB, 0.715 - curl, 0.60 - handLift)

        set(LandmarkId.LEFT_HIP, 0.45, 0.61)
        set(LandmarkId.RIGHT_HIP, 0.55, 0.61)
        set(LandmarkId.LEFT_KNEE, 0.45, 0.78)
        set(LandmarkId.RIGHT_KNEE, 0.55, 0.78)
        set(LandmarkId.LEFT_ANKLE, 0.44, 0.94)
        set(LandmarkId.RIGHT_ANKLE, 0.56, 0.94)
        set(LandmarkId.LEFT_HEEL, 0.43, 0.96)
        set(LandmarkId.RIGHT_HEEL, 0.57, 0.96)
        set(LandmarkId.LEFT_FOOT_INDEX, 0.39, 0.97)
        set(LandmarkId.RIGHT_FOOT_INDEX, 0.61, 0.97)

        return PoseFrame(
            timestampMs = timestampMs,
            landmarks = image.mapIndexed { index, point ->
                val depth = 0.04 * sin(phase * 2.0 * PI + index * 0.07)
                Landmark(
                    image = point,
                    world = Vec3(point.x - 0.5, 0.61 - point.y, depth),
                    visibility = confidence,
                    presence = confidence,
                )
            },
        )
    }

    fun sequence(
        fps: Int,
        seconds: Int = 10,
        noise: Double = 0.0,
        confidence: Double = 0.95,
    ): List<PoseFrame> {
        require(fps > 0 && seconds > 0)
        return (0..fps * seconds).map { index ->
            val timestampMs = (index * 1000.0 / fps).roundToLong()
            frame(
                timestampMs = timestampMs,
                phase = timestampMs / 2000.0,
                noise = noise,
                confidence = confidence,
                seed = 10_000 * fps + index,
            )
        }
    }

    fun withMissing(
        frames: List<PoseFrame>,
        fromMs: Long,
        toMs: Long,
        ids: Set<LandmarkId>,
    ): List<PoseFrame> = frames.map { frame ->
        if (frame.timestampMs !in fromMs..toMs) {
            frame
        } else {
            frame.copy(landmarks = frame.landmarks.mapIndexed { index, landmark ->
                if (LandmarkId.entries[index] in ids) {
                    landmark.copy(image = null, world = null, visibility = 0.0, presence = 0.0)
                } else {
                    landmark
                }
            })
        }
    }

    fun withConfidence(
        frames: List<PoseFrame>,
        fromMs: Long,
        toMs: Long,
        ids: Set<LandmarkId>,
        confidence: Double,
    ): List<PoseFrame> = frames.map { frame ->
        if (frame.timestampMs !in fromMs..toMs) frame else frame.copy(
            landmarks = frame.landmarks.mapIndexed { index, landmark ->
                if (LandmarkId.entries[index] in ids) landmark.copy(visibility = confidence, presence = confidence) else landmark
            },
        )
    }

    fun swapAllSides(frame: PoseFrame): PoseFrame {
        val landmarks = frame.landmarks.toMutableList()
        for ((left, right) in LandmarkId.SIDE_PAIRS) {
            val value = landmarks[left.index]
            landmarks[left.index] = landmarks[right.index]
            landmarks[right.index] = value
        }
        return frame.copy(landmarks = landmarks)
    }

    fun transformImage(frame: PoseFrame, scale: Double, translation: Vec3): PoseFrame = frame.copy(
        landmarks = frame.landmarks.map { landmark ->
            landmark.copy(image = landmark.image?.times(scale)?.plus(translation))
        },
    )

    fun rotateWorldZ(frame: PoseFrame, radians: Double): PoseFrame {
        val c = cos(radians)
        val s = sin(radians)
        return frame.copy(landmarks = frame.landmarks.map { landmark ->
            landmark.copy(world = landmark.world?.let { Vec3(c * it.x - s * it.y, s * it.x + c * it.y, it.z) })
        })
    }
}

package ai.senp.motion

import kotlin.math.exp
import kotlin.math.ln

/** Timestamp-driven short-gap repair and elapsed-time EMA smoothing. */
internal class TrackProcessor(private val config: MotionConfig = MotionConfig()) {
    fun process(frames: List<PoseFrame>): List<PoseFrame> = processDetailed(frames).map { it.frame }

    fun processDetailed(frames: List<PoseFrame>): List<TrackedFrame> {
        if (frames.isEmpty()) return emptyList()
        require(frames.zipWithNext().all { (previous, current) -> current.timestampMs > previous.timestampMs }) {
            "timestamps must be strictly increasing"
        }
        return smooth(repair(frames))
    }

    private fun repair(frames: List<PoseFrame>): List<TrackedFrame> {
        val output = frames.map { it.landmarks.toMutableList() }
        val repairedAt = Array(frames.size) { linkedSetOf<LandmarkId>() }
        val continuityBreakAt = Array(frames.size) { linkedSetOf<LandmarkId>() }

        for (frameIndex in frames.indices) {
            val explicitBreak = frames[frameIndex].inputValidity == FrameValidity.CONTINUITY_BREAK
            val elapsedBreak = frameIndex > 0 &&
                frames[frameIndex].timestampMs - frames[frameIndex - 1].timestampMs >= config.continuityBreakGapMs
            if (explicitBreak || elapsedBreak) {
                for (id in LandmarkId.entries) {
                    if (usable(frames[frameIndex][id])) continuityBreakAt[frameIndex] += id
                }
            }
        }

        for (id in LandmarkId.entries) {
            var index = 0
            while (index < frames.size) {
                if (usable(frames[index][id])) {
                    index += 1
                    continue
                }

                val firstMissing = index
                var lastMissing = index
                while (lastMissing + 1 < frames.size && !usable(frames[lastMissing + 1][id])) {
                    lastMissing += 1
                }

                val leftIndex = firstMissing - 1
                val rightIndex = lastMissing + 1
                for (missingIndex in firstMissing..lastMissing) {
                    output[missingIndex][id.index] = missingLandmark()
                }
                if (leftIndex >= 0 && rightIndex < frames.size) {
                    val gapBoundaryDurationMs = frames[rightIndex].timestampMs - frames[leftIndex].timestampMs
                    when {
                        gapBoundaryDurationMs <= config.maxRepairGapMs -> {
                            val left = frames[leftIndex][id]
                            val right = frames[rightIndex][id]
                            for (missingIndex in firstMissing..lastMissing) {
                                val ratio = (frames[missingIndex].timestampMs - frames[leftIndex].timestampMs).toDouble() /
                                    gapBoundaryDurationMs.toDouble()
                                output[missingIndex][id.index] = interpolate(left, right, ratio)
                                repairedAt[missingIndex] += id
                            }
                        }

                        gapBoundaryDurationMs >= config.continuityBreakGapMs -> {
                            continuityBreakAt[rightIndex] += id
                        }
                    }
                }
                index = lastMissing + 1
            }
        }

        return frames.indices.map { index ->
            TrackedFrame(
                frame = frames[index].copy(landmarks = output[index].toList()),
                repairedLandmarks = repairedAt[index].toSet(),
                continuityBreakLandmarks = continuityBreakAt[index].toSet(),
            )
        }
    }

    private fun missingLandmark(): Landmark = Landmark(
        image = null,
        world = null,
        visibility = 0.0,
        presence = 0.0,
    )

    private fun interpolate(left: Landmark, right: Landmark, ratio: Double): Landmark {
        fun lerp(a: Vec3?, b: Vec3?): Vec3? = when {
            a == null || b == null || !a.finite() || !b.finite() -> null
            else -> a + (b - a) * ratio
        }

        fun lerpConfidence(a: Double, b: Double): Double =
            (a + (b - a) * ratio).coerceAtMost(config.repairedConfidenceCap)

        return Landmark(
            image = lerp(left.image, right.image),
            world = lerp(left.world, right.world),
            visibility = lerpConfidence(left.visibility, right.visibility),
            presence = lerpConfidence(left.presence, right.presence),
            repaired = true,
        )
    }

    private fun smooth(frames: List<TrackedFrame>): List<TrackedFrame> {
        val previous = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        var previousTimestampMs = frames.first().frame.timestampMs

        return frames.mapIndexed { frameIndex, tracked ->
            val elapsedMs = if (frameIndex == 0) 0L else tracked.frame.timestampMs - previousTimestampMs
            val alpha = if (frameIndex == 0) {
                1.0
            } else {
                1.0 - exp(-ln(2.0) * elapsedMs.toDouble() / config.emaHalfLifeMs.toDouble())
            }

            val smoothed = tracked.frame.landmarks.mapIndexed { landmarkIndex, current ->
                val id = LandmarkId.entries[landmarkIndex]
                if (id in tracked.continuityBreakLandmarks) previous[landmarkIndex] = null

                if (!trackable(current)) {
                    previous[landmarkIndex] = null
                    current
                } else {
                    val prior = previous[landmarkIndex]
                    val result = if (prior == null) {
                        current
                    } else {
                        current.copy(
                            image = blend(prior.image, current.image, alpha),
                            world = blend(prior.world, current.world, alpha),
                        )
                    }
                    previous[landmarkIndex] = result
                    result
                }
            }

            previousTimestampMs = tracked.frame.timestampMs
            tracked.copy(frame = tracked.frame.copy(landmarks = smoothed))
        }
    }

    private fun blend(previous: Vec3?, current: Vec3?, alpha: Double): Vec3? = when {
        current == null || !current.finite() -> null
        previous == null || !previous.finite() -> current
        else -> previous + (current - previous) * alpha
    }

    private fun trackable(landmark: Landmark): Boolean = landmark.hasFiniteImage() &&
        (landmark.repaired || (landmark.visibility >= config.minVisibility && landmark.presence >= config.minPresence))

    private fun usable(landmark: Landmark): Boolean = landmark.hasFiniteImage() &&
        landmark.visibility >= config.minVisibility &&
        landmark.presence >= config.minPresence
}

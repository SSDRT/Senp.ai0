package ai.senp.motion.angles

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Joint angles from a MediaPipe 33-landmark pose.
 *
 * Ported from `backend/app/services/angle_service.py`. The math is unchanged. Two things differ:
 *
 * - Indices move from COCO-17 to MediaPipe 33. The joint semantics are identical.
 * - Input is world landmarks, not the Python pipeline's normalized output. Normalization is a
 *   translation plus a uniform scale, and a three-point angle is invariant under both, so it
 *   cannot change any value here.
 *
 * Operates on primitive arrays so it stays independent of the shared pose contracts.
 */
object AngleEngine {

    const val LANDMARK_COUNT = 33

    /** Below this, a limb vector is too short to give a meaningful direction. */
    private const val MIN_VECTOR_LENGTH = 1e-6f

    /**
     * Landmark triplets as (outer, vertex, outer) in MediaPipe 33 index space.
     * The vertex is the joint the angle is named after.
     */
    val TRIPLETS: Map<String, Triple<Int, Int, Int>> = linkedMapOf(
        "left_elbow" to Triple(11, 13, 15),
        "right_elbow" to Triple(12, 14, 16),
        "left_shoulder" to Triple(13, 11, 23),
        "right_shoulder" to Triple(14, 12, 24),
        "left_hip" to Triple(11, 23, 25),
        "right_hip" to Triple(12, 24, 26),
        "left_knee" to Triple(23, 25, 27),
        "right_knee" to Triple(24, 26, 28),
    )

    /**
     * Angle at [b] between [a] and [c], in degrees.
     *
     * @param xyz flat landmark coordinates, `[x0, y0, z0, x1, ...]`, length `LANDMARK_COUNT * 3`.
     * @return null when either limb vector is degenerate or a coordinate is missing (NaN).
     */
    fun angleDeg(xyz: FloatArray, a: Int, b: Int, c: Int): Float? {
        require(xyz.size == LANDMARK_COUNT * 3) {
            "xyz must hold $LANDMARK_COUNT landmarks (${LANDMARK_COUNT * 3} floats), was ${xyz.size}"
        }

        val v1x = xyz[a * 3] - xyz[b * 3]
        val v1y = xyz[a * 3 + 1] - xyz[b * 3 + 1]
        val v1z = xyz[a * 3 + 2] - xyz[b * 3 + 2]
        val v2x = xyz[c * 3] - xyz[b * 3]
        val v2y = xyz[c * 3 + 1] - xyz[b * 3 + 1]
        val v2z = xyz[c * 3 + 2] - xyz[b * 3 + 2]

        val n1 = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val n2 = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
        // Written as a negated `>` so that NaN, which fails every comparison, also returns null.
        if (!(n1 > MIN_VECTOR_LENGTH) || !(n2 > MIN_VECTOR_LENGTH)) return null

        val cos = ((v1x * v2x + v1y * v2y + v1z * v2z) / (n1 * n2)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos.toDouble())).toFloat()
    }

    /**
     * Every angle in [TRIPLETS] for one frame.
     *
     * Mirrors the reference gating: an angle is null when the frame is not [visible], and null
     * when any of its three landmarks falls below [minConfidence]. Null means "not trusted" and
     * is never substituted with a number — a 0 degree elbow is a real, if unlikely, value.
     *
     * @param confidence per-landmark confidence, length [LANDMARK_COUNT].
     */
    fun anglesForFrame(
        xyz: FloatArray,
        confidence: FloatArray,
        minConfidence: Float,
        visible: Boolean,
    ): Map<String, Float?> {
        require(confidence.size == LANDMARK_COUNT) {
            "confidence must hold $LANDMARK_COUNT values, was ${confidence.size}"
        }
        if (!visible) return TRIPLETS.keys.associateWith { null }

        return TRIPLETS.mapValues { (_, triplet) ->
            val (a, b, c) = triplet
            val gated = confidence[a] < minConfidence ||
                confidence[b] < minConfidence ||
                confidence[c] < minConfidence
            if (gated) null else angleDeg(xyz, a, b, c)
        }
    }
}

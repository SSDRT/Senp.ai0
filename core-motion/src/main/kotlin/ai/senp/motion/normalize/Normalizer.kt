package ai.senp.motion.normalize

import kotlin.math.sqrt

/**
 * Pelvis-centred, torso-scaled landmark frames, so two people of different size can be
 * compared position by position.
 *
 * Ported from `backend/app/services/normalize_service.py`, minus the bone-length calibration
 * and enforcement, which AGENTS.md excludes. Those exist in the reference to paper over its
 * heuristic 3D lifter, which is not being ported either.
 *
 * What is left is a translation and a uniform scale. Joint angles are invariant under both, so
 * this cannot change any angle and is deliberately not on the angle path — `AngleEngine` reads
 * world landmarks directly. Normalization is for positional and trajectory comparison.
 *
 * Operates on primitive arrays so it stays independent of the shared pose contracts.
 */
object Normalizer {

    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    /** Guards against dividing by a torso that has collapsed to a point. */
    private const val MIN_TORSO_LENGTH = 1e-4f

    /**
     * Distance from pelvis to shoulder centre, or null when an anchor landmark is missing.
     *
     * A frame without both hips and both shoulders cannot be normalized at all, which is a
     * different thing from being normalized badly — hence null rather than a NaN that would
     * quietly spread across every landmark.
     */
    fun torsoLength(xyz: FloatArray): Float? {
        val pelvis = midpoint(xyz, LEFT_HIP, RIGHT_HIP) ?: return null
        val shoulders = midpoint(xyz, LEFT_SHOULDER, RIGHT_SHOULDER) ?: return null
        val dx = shoulders[0] - pelvis[0]
        val dy = shoulders[1] - pelvis[1]
        val dz = shoulders[2] - pelvis[2]
        return maxOf(sqrt(dx * dx + dy * dy + dz * dz), MIN_TORSO_LENGTH)
    }

    /**
     * The clip's representative torso length: the median over frames that have anchors.
     *
     * Preferred over a per-frame scale because the apparent torso shortens when the subject
     * leans or turns, and a per-frame divisor turns that foreshortening into a scale
     * oscillation on every landmark. Returns null when no frame is usable.
     */
    fun clipTorsoLength(frames: Array<FloatArray>): Float? {
        val lengths = frames.mapNotNull { torsoLength(it) }
        if (lengths.isEmpty()) return null
        return lengths.sorted()[lengths.size / 2]
    }

    /** One frame, centred on its own pelvis and scaled by its own torso. */
    fun normalizeFrame(xyz: FloatArray): FloatArray? {
        val torso = torsoLength(xyz) ?: return null
        return normalizeWith(xyz, torso)
    }

    /**
     * Normalize a whole clip.
     *
     * Centring is always per frame — that is what makes the result root-relative. Only the
     * scale is shared, unless [perFrameScale] is set.
     *
     * Frames whose anchors are missing come back as null rather than being dropped, so indices
     * still line up with the timestamps the caller holds.
     */
    fun normalizeClip(frames: Array<FloatArray>, perFrameScale: Boolean = false): Array<FloatArray?> {
        if (frames.isEmpty()) return emptyArray()
        val sharedScale = if (perFrameScale) null else clipTorsoLength(frames)
        return Array(frames.size) { i ->
            val scale = sharedScale ?: torsoLength(frames[i])
            if (scale == null) null else normalizeWith(frames[i], scale)
        }
    }

    private fun normalizeWith(xyz: FloatArray, torsoLength: Float): FloatArray? {
        val pelvis = midpoint(xyz, LEFT_HIP, RIGHT_HIP) ?: return null
        val out = FloatArray(xyz.size)
        for (i in xyz.indices) {
            out[i] = (xyz[i] - pelvis[i % 3]) / torsoLength
        }
        return out
    }

    /** Midpoint of two landmarks, or null if either is missing. */
    private fun midpoint(xyz: FloatArray, a: Int, b: Int): FloatArray? {
        require(xyz.size % 3 == 0) { "xyz must be a flat list of triples, was ${xyz.size}" }
        require(a * 3 + 2 < xyz.size && b * 3 + 2 < xyz.size) {
            "landmarks $a and $b must index into ${xyz.size / 3} landmarks"
        }
        val out = FloatArray(3)
        for (c in 0 until 3) {
            val value = (xyz[a * 3 + c] + xyz[b * 3 + c]) / 2f
            if (value.isNaN()) return null
            out[c] = value
        }
        return out
    }
}

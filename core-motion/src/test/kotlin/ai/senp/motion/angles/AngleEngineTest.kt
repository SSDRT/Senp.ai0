package ai.senp.motion.angles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Expected values are the output of the reference implementation
 * (`backend/app/services/angle_service.py::_angle_deg`) on the same inputs.
 */
class AngleEngineTest {

    private val tolerance = 1e-3f

    /** Empty pose with every coordinate at the origin. */
    private fun pose() = FloatArray(AngleEngine.LANDMARK_COUNT * 3)

    private fun FloatArray.put(index: Int, x: Float, y: Float, z: Float) = apply {
        this[index * 3] = x
        this[index * 3 + 1] = y
        this[index * 3 + 2] = z
    }

    /** Places three points at the left_knee triplet indices (hip, knee, ankle). */
    private fun triplet(
        a: Triple<Float, Float, Float>,
        b: Triple<Float, Float, Float>,
        c: Triple<Float, Float, Float>,
    ) = pose()
        .put(23, a.first, a.second, a.third)
        .put(25, b.first, b.second, b.third)
        .put(27, c.first, c.second, c.third)

    private fun kneeAngle(xyz: FloatArray) = AngleEngine.angleDeg(xyz, 23, 25, 27)

    @Test
    fun `right angle`() {
        val xyz = triplet(Triple(1f, 0f, 0f), Triple(0f, 0f, 0f), Triple(0f, 1f, 0f))
        assertEquals(90f, assertNotNull(kneeAngle(xyz)), tolerance)
    }

    @Test
    fun `straight limb is 180 degrees`() {
        val xyz = triplet(Triple(1f, 0f, 0f), Triple(0f, 0f, 0f), Triple(-1f, 0f, 0f))
        assertEquals(180f, assertNotNull(kneeAngle(xyz)), tolerance)
    }

    @Test
    fun `fully folded limb is 0 degrees`() {
        val xyz = triplet(Triple(1f, 0f, 0f), Triple(0f, 0f, 0f), Triple(2f, 0f, 0f))
        assertEquals(0f, assertNotNull(kneeAngle(xyz)), tolerance)
    }

    @Test
    fun `oblique angle matches reference`() {
        val xyz = triplet(Triple(1f, 0f, 0f), Triple(0f, 0f, 0f), Triple(1f, 1f, 0f))
        assertEquals(45f, assertNotNull(kneeAngle(xyz)), tolerance)
    }

    @Test
    fun `three dimensional angle matches reference`() {
        val xyz = triplet(
            Triple(0.10f, 0.50f, 0.02f),
            Triple(0.12f, 0.10f, -0.05f),
            Triple(0.08f, -0.40f, 0.10f),
        )
        assertEquals(152.43724f, assertNotNull(kneeAngle(xyz)), tolerance)
    }

    @Test
    fun `degenerate limb has no angle`() {
        val xyz = triplet(Triple(0f, 0f, 0f), Triple(0f, 0f, 0f), Triple(1f, 0f, 0f))
        assertNull(kneeAngle(xyz))
    }

    @Test
    fun `missing landmark has no angle`() {
        val xyz = triplet(Triple(Float.NaN, 0f, 0f), Triple(0f, 0f, 0f), Triple(0f, 1f, 0f))
        assertNull(kneeAngle(xyz))
    }

    @Test
    fun `angle is invariant under translation and uniform scale`() {
        val xyz = triplet(
            Triple(0.10f, 0.50f, 0.02f),
            Triple(0.12f, 0.10f, -0.05f),
            Triple(0.08f, -0.40f, 0.10f),
        )
        val moved = triplet(
            Triple((0.10f + 3f) * 7f, (0.50f - 2f) * 7f, (0.02f + 5f) * 7f),
            Triple((0.12f + 3f) * 7f, (0.10f - 2f) * 7f, (-0.05f + 5f) * 7f),
            Triple((0.08f + 3f) * 7f, (-0.40f - 2f) * 7f, (0.10f + 5f) * 7f),
        )
        assertEquals(assertNotNull(kneeAngle(xyz)), assertNotNull(kneeAngle(moved)), 1e-2f)
    }

    @Test
    fun `low confidence suppresses only the affected angle`() {
        val xyz = pose()
            .put(23, 0f, 1f, 0f).put(25, 0f, 0f, 0f).put(27, 1f, 0f, 0f)
            .put(24, 0f, 1f, 0f).put(26, 0f, 0f, 0f).put(28, 1f, 0f, 0f)
        val confidence = FloatArray(AngleEngine.LANDMARK_COUNT) { 1f }
        confidence[27] = 0.2f // left ankle only

        val angles = AngleEngine.anglesForFrame(xyz, confidence, minConfidence = 0.5f, visible = true)

        assertNull(angles.getValue("left_knee"))
        assertEquals(90f, assertNotNull(angles.getValue("right_knee")), tolerance)
    }

    @Test
    fun `invisible frame suppresses every angle`() {
        val confidence = FloatArray(AngleEngine.LANDMARK_COUNT) { 1f }
        val angles = AngleEngine.anglesForFrame(pose(), confidence, minConfidence = 0.5f, visible = false)

        assertEquals(AngleEngine.TRIPLETS.keys, angles.keys)
        assertEquals(emptyList(), angles.values.filterNotNull())
    }

    @Test
    fun `triplets use MediaPipe indices`() {
        // Guards against an index typo silently producing plausible-looking angles.
        assertEquals(
            setOf(
                "left_elbow", "right_elbow", "left_shoulder", "right_shoulder",
                "left_hip", "right_hip", "left_knee", "right_knee",
            ),
            AngleEngine.TRIPLETS.keys,
        )
        assertEquals(Triple(11, 13, 15), AngleEngine.TRIPLETS.getValue("left_elbow"))
        assertEquals(Triple(13, 11, 23), AngleEngine.TRIPLETS.getValue("left_shoulder"))
        assertEquals(Triple(11, 23, 25), AngleEngine.TRIPLETS.getValue("left_hip"))
        assertEquals(Triple(23, 25, 27), AngleEngine.TRIPLETS.getValue("left_knee"))
    }
}

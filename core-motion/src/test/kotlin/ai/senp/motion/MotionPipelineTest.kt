package ai.senp.motion

import ai.senp.motion.angles.AngleEngine
import ai.senp.motion.normalize.Normalizer
import ai.senp.motion.quality.QualityGate
import ai.senp.motion.smoothing.Smoothing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stages run together, in the reference's order: smooth, gate, then angles.
 *
 * Each stage has its own parity tests. This one exists for the behaviour that only appears
 * once they are composed, which is where a port usually goes wrong — every piece correct, the
 * chain still not doing what anyone expected.
 */
class MotionPipelineTest {

    private val stepMs = 100L
    private val landmarks = AngleEngine.LANDMARK_COUNT
    private val minConfidence = 0.5f
    private val tau = Smoothing.tauFromAlpha(0.70f, stepMs.toFloat())
    private val maxGapMs = 500L

    /** A static standing pose, complete enough for all eight angles and for normalization. */
    private fun standingPose(): FloatArray {
        val xyz = FloatArray(landmarks * 3)
        fun put(index: Int, x: Float, y: Float, z: Float) {
            xyz[index * 3] = x; xyz[index * 3 + 1] = y; xyz[index * 3 + 2] = z
        }
        put(11, 0.10f, 1.40f, 0f); put(12, -0.10f, 1.40f, 0f) // shoulders
        put(13, 0.25f, 1.10f, 0f); put(14, -0.25f, 1.10f, 0f) // elbows
        put(15, 0.30f, 0.80f, 0f); put(16, -0.30f, 0.80f, 0f) // wrists
        put(23, 0.10f, 0.90f, 0f); put(24, -0.10f, 0.90f, 0f) // hips
        put(25, 0.10f, 0.50f, 0.05f); put(26, -0.10f, 0.50f, 0.05f) // knees
        put(27, 0.10f, 0.10f, 0f); put(28, -0.10f, 0.10f, 0f) // ankles
        return xyz
    }

    private class Analysis(
        val visible: BooleanArray,
        val angles: List<Map<String, Float?>>,
        val blindSpotsMs: List<LongRange>,
    )

    /** Smooth, gate, then take angles off the smoothed world landmarks. */
    private fun analyse(frames: Int, confidenceOf: (frame: Int, landmark: Int) -> Float): Analysis {
        val timestampsMs = LongArray(frames) { it * stepMs }
        val xyz = Array(frames) { standingPose() }
        val confidence = Array(frames) { t -> FloatArray(landmarks) { j -> confidenceOf(t, j) } }

        val smoothed = Smoothing.smoothClip(
            timestampsMs, xyz, confidence, minConfidence, maxGapMs, tau,
        )
        val quality = QualityGate.evaluate(
            timestampsMs = timestampsMs,
            confidence = smoothed.confidence,
            minConfidence = minConfidence,
            blindThreshold = 0.40f,
            usableThreshold = 0.55f,
            blindEnterMs = 800L,
            blindRecoverMs = 600L,
        )
        val angles = (0 until frames).map { t ->
            AngleEngine.anglesForFrame(
                smoothed.xyz[t], smoothed.confidence[t], minConfidence, quality.visible[t],
            )
        }
        return Analysis(quality.visible, angles, quality.blindSpotsMs)
    }

    @Test
    fun `a clean clip yields every angle on every frame`() {
        val result = analyse(frames = 20) { _, _ -> 0.9f }

        assertTrue(result.visible.all { it }, "a clean clip should be fully visible")
        assertEquals(emptyList(), result.blindSpotsMs)
        result.angles.forEachIndexed { t, frame ->
            assertEquals(AngleEngine.TRIPLETS.keys, frame.keys)
            assertTrue(frame.values.all { it != null }, "frame $t should have every angle")
        }
    }

    @Test
    fun `a repaired landmark still cannot produce an angle`() {
        // Worth knowing: gap repair caps confidence at 0.35, which is below the 0.5 an angle
        // needs. So repair buys smoothing continuity across the hole, not angles through it.
        // Both thresholds came from the reference, and this consequence is inherited with them.
        val ankle = 27
        val result = analyse(frames = 12) { t, j ->
            if (j == ankle && t in 5..6) 0.1f else 0.9f
        }

        assertTrue(result.visible[5] && result.visible[6], "one weak landmark should not blind the frame")
        assertNull(result.angles[5].getValue("left_knee"), "the repaired ankle is still not trusted")
        assertNotNull(result.angles[5].getValue("right_knee"), "the other leg is unaffected")
        assertNotNull(result.angles[4].getValue("left_knee"), "frames around the gap are unaffected")
        assertNotNull(result.angles[7].getValue("left_knee"))
    }

    @Test
    fun `a sustained whole body dropout becomes a blind spot with no angles`() {
        val result = analyse(frames = 30) { t, _ -> if (t in 10..19) 0.1f else 0.9f }

        assertEquals(listOf(1000L..1900L), result.blindSpotsMs)
        for (t in 10..19) {
            assertTrue(result.angles[t].values.all { it == null }, "frame $t should have no angles")
        }
        assertTrue(result.angles[9].values.all { it != null })
        assertTrue(result.angles[20].values.all { it != null })
    }

    @Test
    fun `normalizing the clip does not disturb the angles`() {
        // The reference normalizes before computing angles. Ours does not, and this is why
        // that reordering is safe rather than merely convenient.
        val frames = Array(8) { standingPose() }
        val normalized = Normalizer.normalizeClip(frames)

        frames.indices.forEach { t ->
            val raw = frames[t]
            val scaled = assertNotNull(normalized[t])
            AngleEngine.TRIPLETS.forEach { (name, triplet) ->
                val (a, b, c) = triplet
                val before = assertNotNull(AngleEngine.angleDeg(raw, a, b, c), "$name before")
                val after = assertNotNull(AngleEngine.angleDeg(scaled, a, b, c), "$name after")
                assertEquals(before, after, 1e-3f, "$name changed under normalization")
            }
        }
    }
}

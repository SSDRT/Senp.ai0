package ai.senp.motion.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Expected values are the output of the reference implementation
 * (`backend/app/services/quality_gate_service.py`) on the same inputs.
 *
 * The reference counts frames: 8 to enter a blind spot, 6 to recover. Sampling every 100 ms
 * here makes those 800 ms and 600 ms, so the two must agree exactly.
 */
class QualityGateTest {

    private val stepMs = 100L
    private val landmarks = 33
    private val minConfidence = 0.5f
    private val blindThreshold = 0.40f
    private val usableThreshold = 0.55f
    private val blindEnterMs = 800L
    private val blindRecoverMs = 600L

    /** One confidence per frame, applied to every landmark. */
    private fun evaluate(perFrame: FloatArray, stepMs: Long = this.stepMs) = QualityGate.evaluate(
        timestampsMs = LongArray(perFrame.size) { it * stepMs },
        confidence = Array(perFrame.size) { FloatArray(landmarks) { _ -> perFrame[it] } },
        minConfidence = minConfidence,
        blindThreshold = blindThreshold,
        usableThreshold = usableThreshold,
        blindEnterMs = blindEnterMs,
        blindRecoverMs = blindRecoverMs,
    )

    private fun QualityGate.Result.mask() = visible.joinToString("") { if (it) "1" else "0" }

    private fun run(vararg segments: Pair<Float, Int>) =
        segments.flatMap { (value, count) -> List(count) { value } }.toFloatArray()

    @Test
    fun `sustained bad quality enters a blind spot and sustained good quality recovers`() {
        val result = evaluate(run(0.9f to 10, 0.3f to 10, 0.9f to 10))
        assertEquals("111111111100000000001111111111", result.mask())
        assertEquals(0.925f, result.frameQuality[0], 1e-4f)
        assertEquals(0.225f, result.frameQuality[10], 1e-4f)
    }

    @Test
    fun `a bad run shorter than the threshold never enters the blind state`() {
        // Frames stay untrusted while quality is poor, but recovery is immediate rather than
        // waiting out the 600 ms the blind state would demand.
        val result = evaluate(run(0.9f to 10, 0.3f to 5, 0.9f to 10))
        assertEquals("1111111111000001111111111", result.mask())
    }

    @Test
    fun `a recovery shorter than the threshold stays blind`() {
        val result = evaluate(run(0.9f to 10, 0.3f to 10, 0.9f to 4, 0.3f to 6))
        assertEquals("111111111100000000000000000000", result.mask())
    }

    @Test
    fun `blind spots are reported as timestamp spans`() {
        val result = evaluate(run(0.9f to 10, 0.3f to 10, 0.9f to 10))
        assertEquals(listOf(1000L..1900L), result.blindSpotsMs)
        assertEquals(listOf(1000L..1900L), result.untrustedSpansMs)
    }

    @Test
    fun `a short bad patch is untrusted but is not a blind spot`() {
        // The reference reports this as a blind spot, which overstates a five-frame dip that
        // never triggered the blind state. Untrusted still covers it, so nothing is computed
        // on those frames either way.
        val result = evaluate(run(0.9f to 10, 0.3f to 5, 0.9f to 10))
        assertEquals(listOf(1000L..1400L), result.untrustedSpansMs)
        assertEquals(emptyList(), result.blindSpotsMs)
    }

    @Test
    fun `recovered frames leave the blind span`() {
        // Frames 20-25 are forced invisible while blind, then flipped back on recovery. They
        // must not linger in either span.
        val result = evaluate(run(0.9f to 10, 0.3f to 10, 0.9f to 10))
        assertTrue(result.blindSpotsMs.none { 2000L in it }, "recovered frames are not blind")
        assertTrue(result.untrustedSpansMs.none { 2000L in it }, "recovered frames are trusted")
    }

    @Test
    fun `the same bad duration decides the same way at either sample rate`() {
        // 600 ms of bad quality, under the 800 ms needed to go blind. At 100 ms sampling that
        // is 6 frames and at 50 ms it is 12, so a frame-counting threshold of 8 would disagree
        // with itself.
        //
        // The tell is a short good window afterwards - 300 ms, under the 600 ms recovery. If
        // no blind state began it stays visible; if one did, recovery never completes and it
        // is suppressed. A window longer than the recovery would be retroactively flipped back
        // to visible and hide the difference entirely.
        val coarse = evaluate(run(0.9f to 10, 0.3f to 6, 0.9f to 3, 0.3f to 6), stepMs = 100L)
        val fine = evaluate(run(0.9f to 20, 0.3f to 12, 0.9f to 5, 0.3f to 12), stepMs = 50L)

        assertTrue(
            (16..18).all { coarse.visible[it] },
            "coarse sampling should not have entered a blind spot: ${coarse.mask()}",
        )
        assertTrue(
            (32..36).all { fine.visible[it] },
            "fine sampling should not have entered a blind spot: ${fine.mask()}",
        )
    }

    @Test
    fun `frame quality matches the reference weighting`() {
        val high = FloatArray(landmarks) { 0.9f }
        val low = FloatArray(landmarks) { 0.3f }
        val weights = QualityGate.Weights()

        assertEquals(0.925f, QualityGate.frameQuality(high, minConfidence, weights), 1e-4f)
        assertEquals(0.225f, QualityGate.frameQuality(low, minConfidence, weights), 1e-4f)
    }

    @Test
    fun `focus landmarks carry their own weight`() {
        val weights = QualityGate.Weights()
        val confidence = FloatArray(landmarks) { 1f }
        weights.focusLandmarks.forEach { confidence[it] = 0f }

        // 27 of 33 landmarks confident, but the six that matter are gone.
        val expected = 0.35f * (27f / 33f) + 0.40f * 0f + 0.25f * (27f / 33f)
        assertEquals(expected, QualityGate.frameQuality(confidence, minConfidence, weights), 1e-4f)
    }

    @Test
    fun `focus landmarks are configurable per movement`() {
        val legs = QualityGate.Weights(focusLandmarks = listOf(23, 24, 25, 26, 27, 28))
        val confidence = FloatArray(landmarks) { 1f }
        legs.focusLandmarks.forEach { confidence[it] = 0f }

        val armFocused = QualityGate.frameQuality(confidence, minConfidence, QualityGate.Weights())
        val legFocused = QualityGate.frameQuality(confidence, minConfidence, legs)
        assertTrue(legFocused < armFocused, "leg weighting should punish missing legs harder")
    }

    @Test
    fun `empty clip is handled`() {
        val result = QualityGate.evaluate(
            LongArray(0), emptyArray(), minConfidence,
            blindThreshold, usableThreshold, blindEnterMs, blindRecoverMs,
        )
        assertEquals(0, result.visible.size)
        assertEquals(emptyList(), result.untrustedSpansMs)
        assertEquals(emptyList(), result.blindSpotsMs)
    }
}

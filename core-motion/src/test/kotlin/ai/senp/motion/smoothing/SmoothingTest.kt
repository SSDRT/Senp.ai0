package ai.senp.motion.smoothing

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Expected values are the output of the reference implementation
 * (`backend/app/services/smooth_service.py`) on the same inputs.
 *
 * The reference works in frames at 30 FPS. To compare against it the clips here are sampled
 * every 100 ms with a time constant equivalent to its `alpha = 0.70`, and a gap limit
 * equivalent to its `max_interpolation_gap = 4`. Under uniform sampling the two must agree
 * exactly; that equivalence is the whole point of expressing the thresholds in milliseconds.
 */
class SmoothingTest {

    private val tolerance = 1e-4f
    private val stepMs = 100L
    private val tau = Smoothing.tauFromAlpha(0.70f, stepMs.toFloat())
    private val maxGapMs = 500L

    private fun timestamps(count: Int) = LongArray(count) { it * stepMs }

    /** One landmark, value carried on x, at [confidence]. */
    private fun clip(values: FloatArray, confidence: FloatArray) = Smoothing.smoothClip(
        timestampsMs = timestamps(values.size),
        xyz = Array(values.size) { floatArrayOf(values[it], 0f, 0f) },
        confidence = Array(values.size) { floatArrayOf(confidence[it]) },
        minConfidence = 0.5f,
        maxGapMs = maxGapMs,
        tauMs = tau,
    )

    private fun assertMissing(actual: Float, at: Int) =
        assertTrue(actual.isNaN(), "expected sample $at to be missing, was $actual")

    @Test
    fun `matches reference across repairable and unrepairable gaps`() {
        val values = FloatArray(20) { it.toFloat() }
        val confidence = FloatArray(20) { 1f }
        intArrayOf(0, 3, 4, 8, 9, 10, 11, 12, 19).forEach { confidence[it] = 0.1f }

        val result = clip(values, confidence)
        val x = FloatArray(20) { result.xyz[it][0] }

        assertMissing(x[0], 0) // leading gap: not extrapolated
        assertEquals(1.0f, x[1], tolerance)
        assertEquals(1.7f, x[2], tolerance)
        assertEquals(2.61f, x[3], tolerance) // repaired, then smoothed
        assertEquals(3.583f, x[4], tolerance)
        assertEquals(4.5749f, x[5], tolerance)
        assertEquals(5.57247f, x[6], tolerance)
        assertEquals(6.571741f, x[7], tolerance)
        for (t in 8..12) assertMissing(x[t], t) // five-sample gap: too long to repair
        assertEquals(13.0f, x[13], tolerance) // continuity reset, not smoothed from sample 7
        assertEquals(13.700001f, x[14], tolerance)
        assertEquals(14.610001f, x[15], tolerance)
        assertEquals(15.583f, x[16], tolerance)
        assertEquals(16.5749f, x[17], tolerance)
        assertEquals(17.57247f, x[18], tolerance)
        assertMissing(x[19], 19) // trailing gap: not extrapolated
    }

    @Test
    fun `confidence follows repair outcome`() {
        val values = FloatArray(20) { it.toFloat() }
        val confidence = FloatArray(20) { 1f }
        intArrayOf(0, 3, 4, 8, 9, 10, 11, 12, 19).forEach { confidence[it] = 0.1f }

        val result = clip(values, confidence)
        val conf = FloatArray(20) { result.confidence[it][0] }

        assertEquals(0f, conf[0], tolerance) // unrepairable
        assertEquals(1f, conf[1], tolerance) // untouched
        assertEquals(0.1f, conf[3], tolerance) // repaired, already below the ceiling
        for (t in 8..12) assertEquals(0f, conf[t], tolerance)
        assertEquals(0f, conf[19], tolerance)
    }

    @Test
    fun `repaired samples are capped at the confidence ceiling`() {
        val values = FloatArray(8) { it.toFloat() }
        val confidence = FloatArray(8) { 1f }
        confidence[3] = 0.45f // gated, but above the ceiling
        confidence[4] = 0.45f

        val result = clip(values, confidence)
        val x = FloatArray(8) { result.xyz[it][0] }

        assertEquals(Smoothing.REPAIRED_CONFIDENCE_CEILING, result.confidence[3][0], tolerance)
        assertEquals(Smoothing.REPAIRED_CONFIDENCE_CEILING, result.confidence[4][0], tolerance)
        assertEquals(0.0f, x[0], tolerance)
        assertEquals(0.7f, x[1], tolerance)
        assertEquals(1.61f, x[2], tolerance)
        assertEquals(2.583f, x[3], tolerance)
        assertEquals(3.5749f, x[4], tolerance)
        assertEquals(6.571523f, x[7], tolerance)
    }

    @Test
    fun `gap is measured between the samples that bracket it`() {
        val t = longArrayOf(0, 100, 200, 900, 1000)
        val track = floatArrayOf(0f, 1f, 2f, Float.NaN, 4f)
        // The lone missing sample sits in an 800 ms hole, well past the limit.
        val repaired = Smoothing.repairShortGaps(t, track, maxGapMs)
        assertMissing(repaired[3], 3)
    }

    @Test
    fun `repair interpolates in time not in sample count`() {
        val t = longArrayOf(0, 100, 400, 500)
        val track = floatArrayOf(0f, 0f, Float.NaN, 10f)
        val repaired = Smoothing.repairShortGaps(t, track, maxGapMs)
        // Three quarters of the way from 100 ms to 500 ms, not half way by sample count.
        assertEquals(7.5f, repaired[2], tolerance)
    }

    @Test
    fun `time constant round trips through alpha`() {
        assertEquals(0.70f, Smoothing.alphaFor(stepMs, tau), tolerance)
        assertEquals(0.55f, Smoothing.alphaFor(33L, Smoothing.tauFromAlpha(0.55f, 33f)), tolerance)
    }

    @Test
    fun `halving the sample rate raises alpha rather than the smoothing`() {
        // The trap a literal port falls into: reusing 0.70 at 15 FPS doubles the smoothing.
        val tau30 = Smoothing.tauFromAlpha(0.70f, 33.333f)
        val alpha15 = Smoothing.alphaFor(67L, tau30)
        assertTrue(alpha15 > 0.90f, "expected alpha above 0.90 at 15 FPS, was $alpha15")
    }

    @Test
    fun `smoothing depends on elapsed time not on sample count`() {
        val tauMs = 500f
        val expected = 1f - exp(-1000f / tauMs) // step response after 1000 ms

        fun settle(times: LongArray): Float {
            val track = FloatArray(times.size) { if (it == 0) 0f else 1f }
            return Smoothing.emaTimeAware(times, track, tauMs).last()
        }

        val uniformCoarse = settle(LongArray(11) { it * 100L })
        val uniformFine = settle(LongArray(21) { it * 50L })
        val irregular = settle(longArrayOf(0, 30, 130, 200, 450, 700, 1000))

        assertEquals(expected, uniformCoarse, tolerance)
        assertEquals(expected, uniformFine, tolerance)
        assertEquals(expected, irregular, tolerance)
    }

    @Test
    fun `empty clip is handled`() {
        val result = Smoothing.smoothClip(LongArray(0), emptyArray(), emptyArray(), 0.5f, maxGapMs, tau)
        assertEquals(0, result.xyz.size)
        assertEquals(0, result.confidence.size)
    }

    @Test
    fun `a landmark missing for the whole clip stays missing`() {
        val values = FloatArray(6) { Float.NaN }
        val confidence = FloatArray(6) { 1f }
        val result = clip(values, confidence)
        for (t in 0 until 6) {
            assertMissing(result.xyz[t][0], t)
            assertEquals(0f, result.confidence[t][0], tolerance)
        }
    }
}

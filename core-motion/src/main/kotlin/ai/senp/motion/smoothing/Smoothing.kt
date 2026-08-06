package ai.senp.motion.smoothing

import kotlin.math.exp
import kotlin.math.ln

/**
 * Temporal smoothing of landmark tracks: confidence gating, short-gap repair, EMA.
 *
 * Ported from `backend/app/services/smooth_service.py`. The stages and their order are
 * unchanged. What differs is that every temporal threshold is expressed in milliseconds and
 * read off the sample timestamps, rather than in frames at an assumed 30 FPS.
 *
 * That matters: the reference tuned `alpha = 0.70` per frame at 30 FPS. Reusing that number
 * on a 15 FPS stream applies roughly twice the intended smoothing. Callers pass a time
 * constant instead, and the per-sample alpha follows from the actual gap. See [tauFromAlpha].
 *
 * Missing values are `NaN` throughout. The reference collapses them to `0.0`, which is
 * indistinguishable from a landmark legitimately sitting at the origin.
 *
 * Operates on primitive arrays so it stays independent of the shared pose contracts.
 */
object Smoothing {

    /** Repaired samples never claim more confidence than this. */
    const val REPAIRED_CONFIDENCE_CEILING = 0.35f

    /**
     * The time constant equivalent to a fixed per-sample [alpha] at [sampleIntervalMs].
     *
     * Use this to carry the reference's tuned alphas across: at the backend's 30 FPS
     * (33.333 ms), 0.55 / 0.70 / 0.85 become roughly 42 / 28 / 18 ms.
     */
    fun tauFromAlpha(alpha: Float, sampleIntervalMs: Float): Float {
        require(alpha > 0f && alpha < 1f) { "alpha must be in (0,1), was $alpha" }
        require(sampleIntervalMs > 0f) { "sampleIntervalMs must be positive, was $sampleIntervalMs" }
        return -sampleIntervalMs / ln(1f - alpha)
    }

    /** The EMA weight for a sample arriving [dtMs] after the previous one. */
    fun alphaFor(dtMs: Long, tauMs: Float): Float {
        require(tauMs > 0f) { "tauMs must be positive, was $tauMs" }
        return 1f - exp(-dtMs.toFloat() / tauMs)
    }

    /**
     * Fills gaps that are short enough to be trusted, by linear interpolation in time.
     *
     * [maxGapMs] is the elapsed time between the two known samples bracketing a gap, not the
     * duration of the gap itself — that is the quantity the interpolation error actually
     * scales with. 167 ms matches the reference's four-frame allowance at 30 FPS.
     *
     * A gap running off either end of the clip is left alone: this interpolates, it never
     * extrapolates.
     */
    fun repairShortGaps(timestampsMs: LongArray, track: FloatArray, maxGapMs: Long): FloatArray {
        require(timestampsMs.size == track.size) {
            "timestamps and track must be the same length, were ${timestampsMs.size} and ${track.size}"
        }
        val out = track.copyOf()
        var i = 0
        while (i < out.size) {
            if (!out[i].isNaN()) {
                i++
                continue
            }
            var last = i
            while (last + 1 < out.size && out[last + 1].isNaN()) last++

            val before = i - 1
            val after = last + 1
            if (before >= 0 && after < out.size) {
                val spanMs = timestampsMs[after] - timestampsMs[before]
                if (spanMs in 1..maxGapMs) {
                    val t0 = timestampsMs[before]
                    val v0 = out[before]
                    val v1 = out[after]
                    for (k in i..last) {
                        val fraction = (timestampsMs[k] - t0).toFloat() / spanMs.toFloat()
                        out[k] = v0 + fraction * (v1 - v0)
                    }
                }
            }
            i = last + 1
        }
        return out
    }

    /**
     * Exponential moving average with the weight derived from each sample's actual gap.
     *
     * Causal, so it lags by roughly [tauMs]. Analysis is offline and a forward-backward pass
     * would remove that lag, but it also breaks numeric parity with the reference.
     * ponytail: causal EMA, revisit if angle timing measurably lags the reference.
     *
     * Continuity resets across a gap, so a run of missing samples is never smoothed over.
     */
    fun emaTimeAware(timestampsMs: LongArray, track: FloatArray, tauMs: Float): FloatArray {
        require(timestampsMs.size == track.size) {
            "timestamps and track must be the same length, were ${timestampsMs.size} and ${track.size}"
        }
        val out = track.copyOf()
        var previous = Float.NaN
        var previousMs = 0L
        for (i in out.indices) {
            val current = out[i]
            if (current.isNaN()) {
                previous = Float.NaN
                continue
            }
            previous = if (previous.isNaN()) {
                current
            } else {
                val alpha = alphaFor(timestampsMs[i] - previousMs, tauMs)
                alpha * current + (1f - alpha) * previous
            }
            previousMs = timestampsMs[i]
            out[i] = previous
        }
        return out
    }

    /** Smoothed coordinates and the confidences that go with them. */
    class Result(val xyz: Array<FloatArray>, val confidence: Array<FloatArray>)

    /**
     * Gate, repair and smooth every landmark track in a clip.
     *
     * @param xyz `[frame][landmark * 3]` flat coordinates.
     * @param confidence `[frame][landmark]`.
     *
     * A landmark below [minConfidence] is dropped before smoothing. If a short enough gap lets
     * it be repaired it comes back with its confidence capped at [REPAIRED_CONFIDENCE_CEILING];
     * otherwise it stays `NaN` with zero confidence.
     */
    fun smoothClip(
        timestampsMs: LongArray,
        xyz: Array<FloatArray>,
        confidence: Array<FloatArray>,
        minConfidence: Float,
        maxGapMs: Long,
        tauMs: Float,
    ): Result {
        val frames = timestampsMs.size
        require(xyz.size == frames && confidence.size == frames) {
            "xyz and confidence must have one entry per timestamp ($frames)"
        }
        if (frames == 0) return Result(emptyArray(), emptyArray())

        val landmarks = confidence[0].size
        require(xyz.all { it.size == landmarks * 3 }) { "each xyz frame must hold ${landmarks * 3} floats" }
        require(confidence.all { it.size == landmarks }) { "each confidence frame must hold $landmarks values" }

        val outXyz = Array(frames) { xyz[it].copyOf() }
        val outConfidence = Array(frames) { confidence[it].copyOf() }

        // A landmark is dropped when it is untrusted or was never detected.
        val dropped = Array(frames) { t ->
            BooleanArray(landmarks) { j ->
                confidence[t][j] < minConfidence ||
                    xyz[t][j * 3].isNaN() || xyz[t][j * 3 + 1].isNaN() || xyz[t][j * 3 + 2].isNaN()
            }
        }
        for (t in 0 until frames) {
            for (j in 0 until landmarks) {
                if (dropped[t][j]) {
                    outXyz[t][j * 3] = Float.NaN
                    outXyz[t][j * 3 + 1] = Float.NaN
                    outXyz[t][j * 3 + 2] = Float.NaN
                }
            }
        }

        val track = FloatArray(frames)
        for (j in 0 until landmarks) {
            for (c in 0 until 3) {
                val slot = j * 3 + c
                for (t in 0 until frames) track[t] = outXyz[t][slot]
                val smoothed = emaTimeAware(
                    timestampsMs,
                    repairShortGaps(timestampsMs, track, maxGapMs),
                    tauMs,
                )
                for (t in 0 until frames) outXyz[t][slot] = smoothed[t]
            }
        }

        for (t in 0 until frames) {
            for (j in 0 until landmarks) {
                if (!dropped[t][j]) continue
                if (outXyz[t][j * 3].isNaN()) {
                    outConfidence[t][j] = 0f
                } else {
                    outConfidence[t][j] = minOf(outConfidence[t][j], REPAIRED_CONFIDENCE_CEILING)
                }
            }
        }

        return Result(outXyz, outConfidence)
    }
}

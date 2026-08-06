package ai.senp.motion.quality

/**
 * Per-frame reliability scoring and blind-spot detection.
 *
 * Ported from `backend/app/services/quality_gate_service.py`. The scoring formula and the
 * hysteresis behaviour are unchanged. What differs is that the two run-length thresholds are
 * durations in milliseconds rather than frame counts, so a clip sampled at 15 FPS and one
 * sampled at 30 FPS produce the same blind spans in wall-clock time.
 *
 * Operates on primitive arrays so it stays independent of the shared pose contracts.
 */
object QualityGate {

    /**
     * How the per-frame score is composed.
     *
     * The defaults are the reference's, whose [focusLandmarks] are the arms because its clips
     * were bicep curls. Callers analysing another movement should pass the landmarks that
     * matter for it — a squat cares about hips, knees and ankles.
     *
     * ponytail: a plain weights object, not a profile loader. Exercise profiles belong to the
     * compare lane; this only needs to accept what they decide.
     */
    data class Weights(
        val mean: Float = 0.35f,
        val focus: Float = 0.40f,
        val coverage: Float = 0.25f,
        val focusLandmarks: List<Int> = listOf(11, 12, 13, 14, 15, 16),
    )

    class Result(
        val frameQuality: FloatArray,
        val visible: BooleanArray,
        /**
         * Every span the pose is not trusted over, inclusive, for any reason.
         *
         * Includes brief dips that were never sustained enough to trigger the blind state.
         * This is what the reference calls blind spots; it is the superset.
         */
        val untrustedSpansMs: List<LongRange>,
        /**
         * The spans where bad quality actually sustained long enough to enter the blind state.
         *
         * A subset of [untrustedSpansMs]. Use this to tell the user the pose was lost; use
         * [untrustedSpansMs] to decide which frames to compute on. The reference conflates the
         * two, which reads as "the pose was lost" for what may be a single bad frame.
         */
        val blindSpotsMs: List<LongRange>,
    )

    /** Weighted reliability of one frame, in `[0,1]`. */
    fun frameQuality(confidence: FloatArray, minConfidence: Float, weights: Weights): Float {
        require(confidence.isNotEmpty()) { "confidence must not be empty" }
        require(weights.focusLandmarks.all { it in confidence.indices }) {
            "focusLandmarks must index into ${confidence.size} landmarks, were ${weights.focusLandmarks}"
        }

        var total = 0f
        var covered = 0
        for (c in confidence) {
            total += c
            if (c >= minConfidence) covered++
        }
        val mean = total / confidence.size
        val coverage = covered.toFloat() / confidence.size

        var focusTotal = 0f
        for (index in weights.focusLandmarks) focusTotal += confidence[index]
        val focus = if (weights.focusLandmarks.isEmpty()) 0f else focusTotal / weights.focusLandmarks.size

        return (weights.mean * mean + weights.focus * focus + weights.coverage * coverage)
            .coerceIn(0f, 1f)
    }

    /**
     * Score every frame, then decide which are trustworthy.
     *
     * The hysteresis is deliberately asymmetric: it takes [blindEnterMs] of sustained bad
     * quality to stop trusting the pose, and [blindRecoverMs] of sustained good quality to
     * start again. Both decisions apply retroactively across the run that triggered them, so
     * this is an offline pass over a whole clip and cannot be made streaming without losing
     * that.
     *
     * @param confidence `[frame][landmark]`.
     */
    fun evaluate(
        timestampsMs: LongArray,
        confidence: Array<FloatArray>,
        minConfidence: Float,
        blindThreshold: Float,
        usableThreshold: Float,
        blindEnterMs: Long,
        blindRecoverMs: Long,
        weights: Weights = Weights(),
    ): Result {
        val frames = timestampsMs.size
        require(confidence.size == frames) {
            "confidence must have one entry per timestamp ($frames), had ${confidence.size}"
        }
        if (frames == 0) return Result(FloatArray(0), BooleanArray(0), emptyList(), emptyList())

        val quality = FloatArray(frames) { frameQuality(confidence[it], minConfidence, weights) }
        val visible = BooleanArray(frames)
        val blind = BooleanArray(frames)

        // A run starting at the first sample has no preceding gap to measure, so it borrows
        // the clip's typical one.
        val nominalGapMs = medianGapMs(timestampsMs)

        var inBlind = false
        var lowRunMs = 0L
        var lowRunStart = -1
        var highRunMs = 0L
        var highRunStart = -1

        for (i in 0 until frames) {
            val gapMs = if (i == 0) nominalGapMs else timestampsMs[i] - timestampsMs[i - 1]
            val q = quality[i]

            if (!inBlind) {
                if (q < blindThreshold) {
                    if (lowRunStart < 0) lowRunStart = i
                    lowRunMs += gapMs
                } else {
                    lowRunStart = -1
                    lowRunMs = 0L
                }

                if (lowRunStart >= 0 && lowRunMs >= blindEnterMs) {
                    inBlind = true
                    // Marking the run invisible is inert while usableThreshold sits above
                    // blindThreshold, since every frame in a low run already failed the usable
                    // test. Kept because recalibrating the two for MediaPipe could cross them,
                    // at which point this becomes load-bearing.
                    for (k in lowRunStart..i) {
                        visible[k] = false
                        blind[k] = true
                    }
                    highRunStart = -1
                    highRunMs = 0L
                } else {
                    visible[i] = q >= usableThreshold
                }
            } else {
                visible[i] = false
                blind[i] = true
                if (q >= usableThreshold) {
                    if (highRunStart < 0) highRunStart = i
                    highRunMs += gapMs
                } else {
                    highRunStart = -1
                    highRunMs = 0L
                }

                if (highRunStart >= 0 && highRunMs >= blindRecoverMs) {
                    for (k in highRunStart..i) {
                        visible[k] = true
                        blind[k] = false
                    }
                    inBlind = false
                    lowRunStart = -1
                    lowRunMs = 0L
                    highRunStart = -1
                    highRunMs = 0L
                }
            }
        }

        return Result(
            frameQuality = quality,
            visible = visible,
            untrustedSpansMs = spansWhere(timestampsMs) { !visible[it] },
            blindSpotsMs = spansWhere(timestampsMs) { blind[it] },
        )
    }

    private fun medianGapMs(timestampsMs: LongArray): Long {
        if (timestampsMs.size < 2) return 0L
        val gaps = LongArray(timestampsMs.size - 1) { timestampsMs[it + 1] - timestampsMs[it] }
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /** Contiguous runs of frames satisfying [predicate], as inclusive timestamp spans. */
    private fun spansWhere(timestampsMs: LongArray, predicate: (Int) -> Boolean): List<LongRange> {
        val spans = mutableListOf<LongRange>()
        var start = -1
        for (i in timestampsMs.indices) {
            if (predicate(i) && start < 0) {
                start = i
            } else if (!predicate(i) && start >= 0) {
                spans += timestampsMs[start]..timestampsMs[i - 1]
                start = -1
            }
        }
        if (start >= 0) spans += timestampsMs[start]..timestampsMs[timestampsMs.size - 1]
        return spans
    }
}

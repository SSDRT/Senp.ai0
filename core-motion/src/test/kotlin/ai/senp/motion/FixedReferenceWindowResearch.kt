package ai.senp.motion

/** Offline research only. Original measurements/context/timebase, never a live fallback. */
internal object FixedReferenceWindowResearch {
    const val WINDOW_MS = 16_000L
    const val STRIDE_MS = 8_000L
    const val MINIMUM_WINDOW_MS = 8_000L
    data class Window(val startMs: Long, val endExclusiveMs: Long, val sequence: SpatialSequenceAnalysis)

    fun mayScreen(result: QualifiedRepCompilation): Boolean = result is QualifiedRepCompilation.Unavailable &&
        result.reason !in setOf(RepReferenceFailure.MISSING_PROVENANCE, RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT)

    fun windows(source: SpatialSequenceAnalysis): List<Window> {
        if (source.frames.isEmpty()) return emptyList()
        val first = source.frames.first().timestamp.value
        val end = source.frames.last().timestamp.value + 1
        return generateSequence(first) { it + STRIDE_MS }.takeWhile { end - it >= MINIMUM_WINDOW_MS }.map { start ->
            val stop = minOf(start + WINDOW_MS, end)
            val frames = source.frames.filter { it.timestamp.value >= start && it.timestamp.value < stop }
            // Keeping the original duration and frame objects also retains segment/identity
            // changes, absent features, raw confidences and gaps. No warm-up is borrowed.
            Window(start, stop, source.copy(frames = frames))
        }.toList()
    }
}

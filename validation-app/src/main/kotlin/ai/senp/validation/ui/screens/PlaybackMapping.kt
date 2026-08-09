package ai.senp.validation.ui.screens

import ai.senp.core.contracts.MotionUnitCorrespondence
import ai.senp.core.contracts.SynchronizationResult
import ai.senp.core.contracts.TimestampCorrespondence
import kotlin.math.roundToLong

internal data class PlaybackPoint(
    val sourceMs: Long,
    val referenceMs: Long,
)

internal data class PlaybackSpan(
    val points: List<PlaybackPoint>,
) {
    init {
        require(points.isNotEmpty()) { "playback span must contain at least one matched timestamp" }
        require(points.zipWithNext().all { (left, right) -> left.sourceMs < right.sourceMs }) {
            "playback span source timestamps must be strictly increasing"
        }
        require(points.zipWithNext().all { (left, right) -> left.referenceMs <= right.referenceMs }) {
            "playback span reference timestamps must be monotonic"
        }
    }
}

internal data class PlaybackMapping(
    val spans: List<PlaybackSpan>,
) {
    val pointCount: Int = spans.sumOf { it.points.size }

    fun sourceToReference(sourceMs: Long): Long? = uniqueSupportedMapping(sourceMs) { span ->
        span.points.map { it.sourceMs to it.referenceMs }
    }

    fun referenceToSource(referenceMs: Long): Long? = uniqueSupportedMapping(referenceMs) { span ->
        span.points.map { it.referenceMs to it.sourceMs }
    }

    fun supportsSource(sourceMs: Long): Boolean = sourceToReference(sourceMs) != null

    private fun uniqueSupportedMapping(
        timestampMs: Long,
        coordinates: (PlaybackSpan) -> List<Pair<Long, Long>>,
    ): Long? {
        val candidates = spans.mapNotNull { span ->
            interpolateWithinSupportedSpan(timestampMs, coordinates(span))
        }.distinct()
        return candidates.singleOrNull()
    }
}

internal fun SynchronizationResult.playbackMapping(): PlaybackMapping = playbackMapping(correspondences)

internal fun playbackMapping(correspondences: List<MotionUnitCorrespondence>): PlaybackMapping {
    val spans = buildList {
        correspondences.filterIsInstance<MotionUnitCorrespondence.MatchedUnit>().forEach { unit ->
            var current = mutableListOf<PlaybackPoint>()

            fun flush() {
                if (current.isNotEmpty()) {
                    add(PlaybackSpan(current.toList()))
                    current = mutableListOf()
                }
            }

            unit.timeline.forEach { decision ->
                when (decision) {
                    is TimestampCorrespondence.Matched -> current += PlaybackPoint(
                        sourceMs = decision.sourceTimestamp.value,
                        referenceMs = decision.referenceTimestamp.value,
                    )
                    is TimestampCorrespondence.UnmatchedSource -> flush()
                }
            }
            flush()
        }
    }.sortedBy { it.points.first().sourceMs }

    return PlaybackMapping(spans)
}

private fun interpolateWithinSupportedSpan(
    timestampMs: Long,
    coordinates: List<Pair<Long, Long>>,
): Long? {
    if (coordinates.isEmpty()) return null
    val ordered = coordinates.sortedBy { it.first }
    if (timestampMs < ordered.first().first || timestampMs > ordered.last().first) return null

    val exact = ordered.asSequence()
        .filter { it.first == timestampMs }
        .map { it.second }
        .distinct()
        .toList()
    if (exact.isNotEmpty()) return exact.singleOrNull()

    val before = ordered.lastOrNull { it.first < timestampMs } ?: return null
    val after = ordered.firstOrNull { it.first > timestampMs } ?: return null
    val ratio = (timestampMs - before.first).toDouble() / (after.first - before.first).toDouble()
    return (before.second + (after.second - before.second) * ratio).roundToLong()
}

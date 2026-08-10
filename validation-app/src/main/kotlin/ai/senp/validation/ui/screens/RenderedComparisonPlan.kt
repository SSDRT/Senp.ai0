package ai.senp.validation.ui.screens

import kotlin.math.abs

internal data class RenderedComparisonSegment(
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val referenceStartMs: Long,
    val referenceEndMs: Long,
    val outputStartMs: Long,
) {
    init {
        require(sourceStartMs >= 0L && referenceStartMs >= 0L)
        require(sourceEndMs > sourceStartMs)
        require(referenceEndMs > referenceStartMs)
        require(outputStartMs >= 0L)
    }

    val outputDurationMs: Long = sourceEndMs - sourceStartMs
    val referenceSpeed: Float =
        ((referenceEndMs - referenceStartMs).toDouble() / outputDurationMs.toDouble()).toFloat()
}

internal data class RenderedComparisonPlan(
    val segments: List<RenderedComparisonSegment>,
) {
    val durationMs: Long = segments.sumOf(RenderedComparisonSegment::outputDurationMs)
}

internal fun PlaybackMapping.renderedComparisonPlan(
    maximumLinearErrorMs: Long = 40L,
): RenderedComparisonPlan {
    require(maximumLinearErrorMs >= 0L)
    val rendered = mutableListOf<RenderedComparisonSegment>()
    var outputStartMs = 0L

    spans.forEach { span ->
        val points = span.points
        if (points.size < 2) return@forEach
        var startIndex = 0
        while (startIndex < points.lastIndex) {
            val immediate = points[startIndex + 1]
            if (immediate.referenceMs <= points[startIndex].referenceMs) {
                startIndex += 1
                continue
            }

            var endIndex = startIndex + 1
            while (endIndex < points.lastIndex) {
                val candidateEnd = endIndex + 1
                if (points[candidateEnd].referenceMs <= points[endIndex].referenceMs) break
                if (maximumLinearError(points, startIndex, candidateEnd) > maximumLinearErrorMs) break
                endIndex = candidateEnd
            }

            val start = points[startIndex]
            val end = points[endIndex]
            if (end.sourceMs > start.sourceMs && end.referenceMs > start.referenceMs) {
                rendered += RenderedComparisonSegment(
                    sourceStartMs = start.sourceMs,
                    sourceEndMs = end.sourceMs,
                    referenceStartMs = start.referenceMs,
                    referenceEndMs = end.referenceMs,
                    outputStartMs = outputStartMs,
                )
                outputStartMs += end.sourceMs - start.sourceMs
            }
            startIndex = endIndex
        }
    }

    return RenderedComparisonPlan(rendered)
}

private fun maximumLinearError(
    points: List<PlaybackPoint>,
    startIndex: Int,
    endIndex: Int,
): Long {
    val start = points[startIndex]
    val end = points[endIndex]
    val sourceDuration = end.sourceMs - start.sourceMs
    if (sourceDuration <= 0L) return Long.MAX_VALUE
    val referenceDuration = end.referenceMs - start.referenceMs
    if (referenceDuration <= 0L) return Long.MAX_VALUE

    var maximum = 0L
    for (index in (startIndex + 1) until endIndex) {
        val point = points[index]
        val ratio = (point.sourceMs - start.sourceMs).toDouble() / sourceDuration.toDouble()
        val expected = start.referenceMs + referenceDuration.toDouble() * ratio
        maximum = maxOf(maximum, abs(point.referenceMs - expected).toLong())
    }
    return maximum
}

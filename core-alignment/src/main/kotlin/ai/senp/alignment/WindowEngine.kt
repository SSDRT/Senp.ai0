package ai.senp.alignment

import kotlin.math.max

internal class WindowEngine(private val config: AlignmentConfig) {
    fun detect(
        points: List<MappingPoint>,
        repBoundariesMs: List<Long>,
    ): List<ProblemWindow> {
        if (points.isEmpty()) return emptyList()

        val rawError = BooleanArray(points.size) { index ->
            val point = points[index]
            point.active && !point.blind && point.rawDifference >= config.errorThreshold
        }
        val genuine = BooleanArray(points.size) { index ->
            val point = points[index]
            rawError[index] &&
                point.alignmentConfidence >= config.confidentThreshold &&
                point.weightedDifference >= config.errorThreshold
        }

        val repCount = max(0, repBoundariesMs.size - 1)
        if (repCount <= 2) {
            for (index in points.indices) {
                val point = points[index]
                if (
                    rawError[index] &&
                    point.maximumDifference >= config.errorThreshold * config.singleMotionPeakMultiplier &&
                    point.commonCoverage >= config.minimumCommonFeatureCoverage
                ) {
                    genuine[index] = true
                }
            }
        } else {
            val consensus = repConsensus(points, rawError, repBoundariesMs)
            for (index in points.indices) genuine[index] = genuine[index] || consensus[index]
        }

        val uncertain = BooleanArray(points.size) { index ->
            val confidence = points[index].alignmentConfidence
            rawError[index] &&
                !genuine[index] &&
                confidence >= config.uncertainConfidenceFloor &&
                confidence < config.confidentThreshold
        }

        val genuineWindows = windows(points, genuine, WindowKind.GENUINE_FORM_ERROR)
        val uncertainWindows = windows(points, uncertain, WindowKind.UNCERTAIN_ALIGNMENT)
            .filter { uncertainWindow ->
                genuineWindows.none { genuineWindow ->
                    uncertainWindow.userStartMs <= genuineWindow.userEndMs &&
                        uncertainWindow.userEndMs >= genuineWindow.userStartMs
                }
            }
        return (genuineWindows + uncertainWindows)
            .sortedWith(compareBy<ProblemWindow> { it.userStartMs }.thenBy { it.kind.name })
    }

    private fun repConsensus(
        points: List<MappingPoint>,
        rawError: BooleanArray,
        repBoundariesMs: List<Long>,
    ): BooleanArray {
        val repCount = repBoundariesMs.size - 1
        if (repCount < 2) return BooleanArray(points.size)

        val highByRepAndBin = Array(repCount) { BooleanArray(config.repConsensusPhaseBins) }
        for (repIndex in 0 until repCount) {
            val startMs = repBoundariesMs[repIndex]
            val endMs = repBoundariesMs[repIndex + 1]
            if (endMs <= startMs) continue
            for (bin in 0 until config.repConsensusPhaseBins) {
                val binStartMs = startMs + (endMs - startMs) * bin / config.repConsensusPhaseBins
                val binEndMs = startMs + (endMs - startMs) * (bin + 1) / config.repConsensusPhaseBins
                val indices = points.indices.filter { index ->
                    val timestamp = points[index].userTimestampMs
                    timestamp >= binStartMs &&
                        (timestamp < binEndMs || (bin == config.repConsensusPhaseBins - 1 && timestamp <= binEndMs))
                }
                if (indices.isEmpty()) continue
                val differences = indices.map { points[it].rawDifference }
                val errorFraction = indices.count { rawError[it] }.toDouble() / indices.size
                val percentile75 = quantile(differences, 0.75)
                highByRepAndBin[repIndex][bin] =
                    errorFraction >= 0.25 &&
                    differences.average() >= config.errorThreshold * 0.85 &&
                    percentile75 >= config.errorThreshold
            }
        }

        val consensusBins = BooleanArray(config.repConsensusPhaseBins) { bin ->
            val highRepCount = (0 until repCount).count { highByRepAndBin[it][bin] }
            highRepCount >= 2 && highRepCount.toDouble() / repCount >= config.repConsensusFraction
        }
        val output = BooleanArray(points.size)
        for (index in points.indices) {
            if (!rawError[index]) continue
            val timestamp = points[index].userTimestampMs
            val repIndex = (0 until repCount).firstOrNull { rep ->
                timestamp in repBoundariesMs[rep]..repBoundariesMs[rep + 1]
            } ?: continue
            val repStart = repBoundariesMs[repIndex]
            val repEnd = repBoundariesMs[repIndex + 1]
            if (repEnd <= repStart) continue
            val fraction = (timestamp - repStart).toDouble() / (repEnd - repStart).toDouble()
            val bin = (fraction * config.repConsensusPhaseBins)
                .toInt()
                .coerceIn(0, config.repConsensusPhaseBins - 1)
            output[index] = consensusBins[bin]
        }
        return output
    }

    private fun windows(
        points: List<MappingPoint>,
        mask: BooleanArray,
        kind: WindowKind,
    ): List<ProblemWindow> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (index in 0..mask.size) {
            if (index < mask.size && mask[index]) {
                if (start < 0) start = index
            } else if (start >= 0) {
                val end = index - 1
                if (
                    points[end].userTimestampMs - points[start].userTimestampMs >=
                    config.minimumErrorDurationMs
                ) {
                    runs += start..end
                }
                start = -1
            }
        }

        val padded = runs.map { run ->
            val paddedStartMs = points[run.first].userTimestampMs - config.windowPaddingMs
            val paddedEndMs = points[run.last].userTimestampMs + config.windowPaddingMs
            val paddedStart = points.indexOfFirst { it.userTimestampMs >= paddedStartMs }
                .let { if (it < 0) 0 else it }
            val paddedEnd = points.indexOfLast { it.userTimestampMs <= paddedEndMs }
                .coerceAtLeast(run.last)
            paddedStart..paddedEnd
        }

        val merged = mutableListOf<IntRange>()
        for (run in padded) {
            val previous = merged.lastOrNull()
            if (
                previous != null &&
                points[run.first].userTimestampMs - points[previous.last].userTimestampMs <= config.mergeGapMs
            ) {
                merged[merged.lastIndex] = previous.first..max(previous.last, run.last)
            } else {
                merged += run
            }
        }

        return merged.map { run ->
            val segment = points.slice(run)
            ProblemWindow(
                kind = kind,
                userStartMs = segment.first().userTimestampMs,
                userEndMs = segment.last().userTimestampMs,
                referenceStartMs = segment.first().referenceTimestampMs,
                referenceEndMs = segment.last().referenceTimestampMs,
                peakDifference = segment.maxOf { it.maximumDifference },
                meanDifference = segment.map { it.rawDifference }.averageOrZero(),
                meanConfidence = segment.map { it.alignmentConfidence }.averageOrZero(),
            )
        }
    }
}

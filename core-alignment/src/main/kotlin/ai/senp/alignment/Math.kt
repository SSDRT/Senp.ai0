package ai.senp.alignment

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class FeatureDistance(
    val rawDifference: Double,
    val maximumDifference: Double,
    val normalizedDifference: Double,
    val coverage: Double,
    val commonFeatureCount: Int,
)

internal fun quantile(values: List<Double>, q: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
    val lower = position.toInt()
    val upper = min(sorted.lastIndex, lower + 1)
    val fraction = position - lower
    return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
}

internal fun median(values: List<Double>): Double = quantile(values, 0.5)

internal fun rms(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else sqrt(values.sumOf { it * it } / values.size)

internal fun compareFrames(
    first: MotionFrame,
    second: MotionFrame,
    profile: ExerciseProfile,
): FeatureDistance {
    var weightedRaw = 0.0
    var weightedNormalized = 0.0
    var commonWeight = 0.0
    var totalWeight = 0.0
    var maximumDifference = 0.0
    var commonCount = 0

    for ((name, rule) in profile.orderedRules) {
        if (rule.weight <= 0.0) continue
        totalWeight += rule.weight
        val firstFeature = first.features[name]
        val secondFeature = second.features[name]
        val firstValue = firstFeature?.value
        val secondValue = secondFeature?.value
        if (
            firstValue == null || secondValue == null ||
            firstFeature.confidence < profile.minimumFeatureConfidence ||
            secondFeature.confidence < profile.minimumFeatureConfidence
        ) {
            continue
        }

        val difference = abs(firstValue - secondValue)
        maximumDifference = max(maximumDifference, difference)
        commonWeight += rule.weight
        weightedRaw += difference * rule.weight
        weightedNormalized += (difference / rule.distanceScale) * rule.weight
        commonCount += 1
    }

    if (commonWeight <= 0.0 || totalWeight <= 0.0) {
        return FeatureDistance(0.0, 0.0, 0.0, 0.0, 0)
    }
    return FeatureDistance(
        rawDifference = weightedRaw / commonWeight,
        maximumDifference = maximumDifference,
        normalizedDifference = weightedNormalized / commonWeight,
        coverage = commonWeight / totalWeight,
        commonFeatureCount = commonCount,
    )
}

internal fun interpolateFeature(
    track: MotionTrack,
    featureName: String,
    timestampMs: Long,
    minimumConfidence: Double,
    maximumGapMs: Long,
): FeatureSample {
    if (track.frames.isEmpty()) return FeatureSample(null, 0.0)
    val exact = track.frames.binarySearchBy(timestampMs) { it.timestampMs }
    if (exact >= 0) return track.frames[exact].features[featureName] ?: FeatureSample(null, 0.0)

    val insertion = -exact - 1
    if (insertion <= 0 || insertion >= track.frames.size) return FeatureSample(null, 0.0)
    val left = track.frames[insertion - 1]
    val right = track.frames[insertion]
    if (right.timestampMs - left.timestampMs > maximumGapMs) return FeatureSample(null, 0.0)

    val leftFeature = left.features[featureName] ?: return FeatureSample(null, 0.0)
    val rightFeature = right.features[featureName] ?: return FeatureSample(null, 0.0)
    val leftValue = leftFeature.value
    val rightValue = rightFeature.value
    if (
        leftValue == null || rightValue == null ||
        leftFeature.confidence < minimumConfidence ||
        rightFeature.confidence < minimumConfidence
    ) {
        return FeatureSample(null, 0.0)
    }

    val fraction = (timestampMs - left.timestampMs).toDouble() /
        (right.timestampMs - left.timestampMs).toDouble()
    return FeatureSample(
        value = leftValue + fraction * (rightValue - leftValue),
        confidence = min(leftFeature.confidence, rightFeature.confidence),
    )
}

internal fun interpolateFrame(
    track: MotionTrack,
    timestampMs: Long,
    profile: ExerciseProfile,
    config: AlignmentConfig,
): MotionFrame = MotionFrame(
    timestampMs = timestampMs,
    features = profile.orderedRules.associate { (name, _) ->
        name to interpolateFeature(
            track = track,
            featureName = name,
            timestampMs = timestampMs,
            minimumConfidence = profile.minimumFeatureConfidence,
            maximumGapMs = config.maximumInterpolationGapMs,
        )
    },
)

internal fun nearestIndex(track: MotionTrack, timestampMs: Long): Int {
    if (track.frames.isEmpty()) return -1
    val exact = track.frames.binarySearchBy(timestampMs) { it.timestampMs }
    if (exact >= 0) return exact
    val insertion = -exact - 1
    if (insertion <= 0) return 0
    if (insertion >= track.frames.size) return track.frames.lastIndex
    val left = track.frames[insertion - 1].timestampMs
    val right = track.frames[insertion].timestampMs
    return if (timestampMs - left <= right - timestampMs) insertion - 1 else insertion
}

internal fun indexAtOrAfter(track: MotionTrack, timestampMs: Long): Int {
    if (track.frames.isEmpty()) return -1
    val exact = track.frames.binarySearchBy(timestampMs) { it.timestampMs }
    if (exact >= 0) return exact
    return (-exact - 1).coerceIn(0, track.frames.lastIndex)
}

internal fun indexAtOrBefore(track: MotionTrack, timestampMs: Long): Int {
    if (track.frames.isEmpty()) return -1
    val exact = track.frames.binarySearchBy(timestampMs) { it.timestampMs }
    if (exact >= 0) return exact
    return (-exact - 2).coerceIn(0, track.frames.lastIndex)
}

internal fun medianTimestampDelta(track: MotionTrack): Long =
    median(track.frames.zipWithNext().map { (a, b) -> (b.timestampMs - a.timestampMs).toDouble() })
        .toLong()
        .coerceAtLeast(1L)

internal fun interpolateSeries(
    timestamps: List<Long>,
    values: List<Double?>,
    timestampMs: Long,
    maximumGapMs: Long,
): Double? {
    if (timestamps.isEmpty()) return null
    val exact = timestamps.binarySearch(timestampMs)
    if (exact >= 0) return values[exact]
    val insertion = -exact - 1
    if (insertion <= 0 || insertion >= timestamps.size) return null
    val leftValue = values[insertion - 1] ?: return null
    val rightValue = values[insertion] ?: return null
    val leftTimestamp = timestamps[insertion - 1]
    val rightTimestamp = timestamps[insertion]
    if (rightTimestamp - leftTimestamp > maximumGapMs) return null
    val fraction = (timestampMs - leftTimestamp).toDouble() / (rightTimestamp - leftTimestamp).toDouble()
    return leftValue + fraction * (rightValue - leftValue)
}

internal fun normalizeFinite(values: List<Double?>): List<Double?> {
    val finite = values.filterNotNull()
    if (finite.isEmpty()) return List(values.size) { null }
    val mean = finite.average()
    val standardDeviation = sqrt(finite.sumOf { (it - mean) * (it - mean) } / finite.size)
    if (standardDeviation < 1e-9) return values.map { it?.let { 0.0 } }
    return values.map { it?.let { value -> (value - mean) / standardDeviation } }
}

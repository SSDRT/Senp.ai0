package ai.senp.motion

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

internal data class ActionSample(
    val timestampMs: Long,
    val values: Map<String, Double>,
    val confidence: Double,
)

internal fun robustDistribution(
    values: List<Double>,
    floorScale: Double,
    widenSingleSample: Boolean = false,
): RobustDistribution {
    require(values.isNotEmpty())
    require(floorScale.isFinite() && floorScale > 0.0)
    val sorted = values.sorted()
    val median = quantileSorted(sorted, 0.50)
    val deviations = sorted.map { abs(it - median) }.sorted()
    val mad = quantileSorted(deviations, 0.50)
    val scaledMad = 1.4826 * mad
    val q25 = quantileSorted(sorted, 0.25)
    val q75 = quantileSorted(sorted, 0.75)
    val iqrScale = (q75 - q25) / 1.349
    val scale = max(floorScale, max(scaledMad, iqrScale))
    val cutoff = max(floorScale * 3.0, scale * 3.5)
    val inliers = sorted.filter { abs(it - median) <= cutoff }.ifEmpty { listOf(median) }
    val lower: Double
    val upper: Double
    if (widenSingleSample && values.size == 1) {
        val allowance = max(abs(median) * 0.35, floorScale * 3.0)
        lower = median - allowance
        upper = median + allowance
    } else {
        val inlierSorted = inliers.sorted()
        val robustAllowance = max(floorScale, scaledMad * 2.8)
        lower = min(quantileSorted(inlierSorted, 0.10), median - robustAllowance)
        upper = max(quantileSorted(inlierSorted, 0.90), median + robustAllowance)
    }
    return RobustDistribution(
        median = median,
        lower = lower,
        upper = upper,
        mad = mad,
        inlierCount = inliers.size,
        sampleCount = values.size,
        outlierFraction = ((values.size - inliers.size).toDouble() / values.size.toDouble()).coerceIn(0.0, 1.0),
    )
}

internal fun robustScale(values: List<Double>, featureName: String): Double {
    if (values.isEmpty()) return semanticFeatureFloor(featureName)
    val distribution = robustDistribution(values, semanticFeatureFloor(featureName))
    val qRange = distribution.upper - distribution.lower
    return max(semanticFeatureFloor(featureName), max(1.4826 * distribution.mad, qRange / 3.0))
}

/**
 * Recurrence detection must not let one corrupted joint dominate an otherwise repeated body pose.
 * Compute normalized per-feature spatial distances, then trim the largest fifth when enough
 * independent features are present. This remains deterministic while resisting one odd landmark.
 */
internal fun robustActionDescriptorDistance(
    left: Map<String, Double>,
    right: Map<String, Double>,
): Double? {
    val distances = left.keys.intersect(right.keys).mapNotNull { feature ->
        descriptorDistance(
            left = mapOf(feature to left.getValue(feature)),
            right = mapOf(feature to right.getValue(feature)),
            mirrorRight = false,
        )?.distance
    }.sorted()
    if (distances.isEmpty()) return null
    val trimCount = if (distances.size >= 5) max(1, distances.size / 5) else 0
    val kept = distances.dropLast(trimCount).ifEmpty { distances }
    return kotlin.math.sqrt(kept.sumOf { it * it } / kept.size.toDouble())
}

internal fun semanticFeatureFloor(featureName: String): Double = when {
    featureName.startsWith("angle.") -> 2.0
    featureName.startsWith("ratio.") -> 0.015
    else -> 0.015
}

internal fun quantile(values: List<Double>, probability: Double): Double = quantileSorted(values.sorted(), probability)

internal fun quantileSorted(sorted: List<Double>, probability: Double): Double {
    require(sorted.isNotEmpty())
    require(probability in 0.0..1.0)
    if (sorted.size == 1) return sorted.single()
    val position = probability * (sorted.size - 1).toDouble()
    val lowerIndex = position.toInt()
    val upperIndex = min(sorted.lastIndex, lowerIndex + 1)
    val fraction = position - lowerIndex.toDouble()
    return sorted[lowerIndex] * (1.0 - fraction) + sorted[upperIndex] * fraction
}

internal fun median(values: List<Double>): Double = quantile(values, 0.50)

internal fun actionTrend(
    previous: Map<String, Double>?,
    current: Map<String, Double>,
    featureScales: Map<String, Double>,
): Map<String, Double> {
    if (previous == null) return emptyMap()
    return current.mapNotNull { (name, value) ->
        val prior = previous[name] ?: return@mapNotNull null
        val scale = featureScales[name] ?: return@mapNotNull null
        val normalized = (value - prior) / scale
        val trend = when {
            abs(normalized) < 0.035 -> 0.0
            else -> sign(normalized)
        }
        name to trend
    }.toMap()
}

internal fun weightedMean(values: List<Pair<Double, Double>>): Double {
    var weighted = 0.0
    var totalWeight = 0.0
    values.forEach { (value, weight) ->
        if (value.isFinite() && weight.isFinite() && weight > 0.0) {
            weighted += value * weight
            totalWeight += weight
        }
    }
    return if (totalWeight > 0.0) weighted / totalWeight else 0.0
}

internal fun gaussianSimilarity(normalizedDistance: Double): Double {
    if (!normalizedDistance.isFinite()) return 0.0
    val bounded = normalizedDistance.coerceAtLeast(0.0).coerceAtMost(8.0)
    return exp(-0.5 * bounded * bounded)
}

internal fun normalizedOutsideRange(value: Double, distribution: RobustDistribution, scale: Double): Double {
    val outside = when {
        value < distribution.lower -> distribution.lower - value
        value > distribution.upper -> value - distribution.upper
        else -> 0.0
    }
    return outside / max(scale, 1e-9)
}

internal fun List<Double>.averageOrZeroAction(): Double = if (isEmpty()) 0.0 else average()

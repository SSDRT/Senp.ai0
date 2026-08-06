package ai.senp.alignment

import kotlin.math.max

internal data class FeatureSample(
    val value: Double?,
    val confidence: Double = if (value == null) 0.0 else 1.0,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0, 1]" }
        require(value == null || value.isFinite()) { "feature values must be finite" }
    }

    val isPresent: Boolean get() = value != null
}

internal data class MotionFrame(
    val timestampMs: Long,
    val features: Map<String, FeatureSample>,
) {
    init {
        require(timestampMs >= 0L) { "timestamps must be non-negative milliseconds" }
    }
}

internal data class MotionTrack(val frames: List<MotionFrame>) {
    init {
        require(frames.zipWithNext().all { (a, b) -> b.timestampMs > a.timestampMs }) {
            "timestamps must be strictly increasing"
        }
    }

    val durationMs: Long
        get() = if (frames.size < 2) 0L else frames.last().timestampMs - frames.first().timestampMs
}

internal data class FeatureRule(
    val weight: Double,
    val distanceScale: Double = 30.0,
    val phaseWeight: Double = 0.0,
    val minimumMotionRange: Double = 8.0,
) {
    init {
        require(weight >= 0.0) { "feature weight must be non-negative" }
        require(distanceScale > 0.0) { "distance scale must be positive" }
        require(phaseWeight >= 0.0) { "phase weight must be non-negative" }
        require(minimumMotionRange > 0.0) { "minimum motion range must be positive" }
    }
}

internal data class ExerciseProfile(
    val id: String,
    val featureRules: Map<String, FeatureRule>,
    val minimumFeatureConfidence: Double = 0.45,
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(featureRules.isNotEmpty()) { "at least one feature rule is required" }
        require(featureRules.values.any { it.weight > 0.0 }) { "at least one comparison feature is required" }
        require(featureRules.values.any { it.phaseWeight > 0.0 }) { "at least one phase signal is required" }
        require(minimumFeatureConfidence in 0.0..1.0)
    }

    internal val orderedRules: List<Pair<String, FeatureRule>>
        get() = featureRules.toSortedMap().toList()

    val primaryPhaseFeature: String
        get() = orderedRules
            .filter { it.second.phaseWeight > 0.0 }
            .maxWith(compareBy<Pair<String, FeatureRule>> { it.second.phaseWeight }.thenByDescending { it.first })
            .first

    companion object {
        fun singlePhase(
            id: String,
            primaryFeature: String,
            featureWeights: Map<String, Double>,
            minimumFeatureConfidence: Double = 0.45,
            distanceScale: Double = 30.0,
            minimumMotionRange: Double = 8.0,
        ): ExerciseProfile {
            require(primaryFeature in featureWeights)
            return ExerciseProfile(
                id = id,
                featureRules = featureWeights.mapValues { (name, weight) ->
                    FeatureRule(
                        weight = weight,
                        distanceScale = distanceScale,
                        phaseWeight = if (name == primaryFeature) 1.0 else 0.0,
                        minimumMotionRange = minimumMotionRange,
                    )
                },
                minimumFeatureConfidence = minimumFeatureConfidence,
            )
        }
    }
}

internal data class AlignmentConfig(
    val activeVelocityQuantile: Double = 0.65,
    val activeVelocityMultiplier: Double = 0.55,
    val activeSmoothingWindowMs: Long = 120,
    val activePaddingMs: Long = 350,
    val minimumActiveDurationMs: Long = 800,
    val maximumPhaseShiftFraction: Double = 0.35,
    val minimumPhaseOverlapMs: Long = 900,
    val phaseResampleStepMs: Long = 40,
    val minimumRepDurationMs: Long = 450,
    val minimumTurningGapMs: Long = 220,
    val turningNeighborhoodMs: Long = 320,
    val turningProminenceNormalized: Double = 0.003,
    val repNormalizationSamples: Int = 48,
    val dtwBandFraction: Double = 0.22,
    val minimumCommonFeatureCoverage: Double = 0.45,
    val missingFeaturePenalty: Double = 0.40,
    val blindCellPenalty: Double = 1.50,
    val maximumInterpolationGapMs: Long = 220,
    val maximumConfidentBlindSpanMs: Long = 500,
    val maximumFrozenMappingMs: Long = 700,
    val slopeWindowMs: Long = 350,
    val slopeConfidenceLambda: Double = 1.8,
    val slopeConfidenceFloor: Double = 0.10,
    val confidentThreshold: Double = 0.60,
    val errorThreshold: Double = 18.0,
    val singleMotionPeakMultiplier: Double = 1.10,
    val minimumErrorDurationMs: Long = 280,
    val windowPaddingMs: Long = 180,
    val mergeGapMs: Long = 220,
    val repConsensusFraction: Double = 0.50,
    val repConsensusPhaseBins: Int = 16,
    val uncertainConfidenceFloor: Double = 0.30,
) {
    init {
        require(activeVelocityQuantile in 0.0..1.0)
        require(activeVelocityMultiplier > 0.0)
        require(activeSmoothingWindowMs >= 0L)
        require(activePaddingMs >= 0L)
        require(minimumActiveDurationMs >= 0L)
        require(maximumPhaseShiftFraction in 0.0..0.5)
        require(minimumPhaseOverlapMs >= 0L)
        require(phaseResampleStepMs > 0L)
        require(minimumRepDurationMs > 0L)
        require(minimumTurningGapMs > 0L)
        require(turningNeighborhoodMs > 0L)
        require(turningProminenceNormalized > 0.0)
        require(repNormalizationSamples >= 8)
        require(dtwBandFraction in 0.0..1.0)
        require(minimumCommonFeatureCoverage in 0.0..1.0)
        require(missingFeaturePenalty >= 0.0)
        require(blindCellPenalty >= 0.0)
        require(maximumInterpolationGapMs >= 0L)
        require(maximumConfidentBlindSpanMs >= 0L)
        require(maximumFrozenMappingMs > 0L)
        require(slopeWindowMs > 0L)
        require(slopeConfidenceLambda > 0.0)
        require(slopeConfidenceFloor in 0.0..1.0)
        require(confidentThreshold in 0.0..1.0)
        require(errorThreshold > 0.0)
        require(singleMotionPeakMultiplier >= 1.0)
        require(minimumErrorDurationMs >= 0L)
        require(windowPaddingMs >= 0L)
        require(mergeGapMs >= 0L)
        require(repConsensusFraction in 0.0..1.0)
        require(repConsensusPhaseBins >= 4)
        require(uncertainConfidenceFloor in 0.0..confidentThreshold)
    }
}

internal enum class AlignmentMode {
    REP_NORMALIZED,
    ANCHOR_CONSTRAINED_DTW,
    BANDED_GLOBAL_DTW,
    LINEAR_INSUFFICIENT_MOTION,
    EMPTY,
}

internal enum class WindowKind {
    GENUINE_FORM_ERROR,
    UNCERTAIN_ALIGNMENT,
}

internal data class MappingPoint(
    val userTimestampMs: Long,
    val referenceTimestampMs: Long,
    val alignmentConfidence: Double,
    val commonCoverage: Double,
    val pathSlope: Double,
    val rawDifference: Double,
    val maximumDifference: Double,
    val weightedDifference: Double,
    val blind: Boolean,
    val active: Boolean,
)

internal data class ProblemWindow(
    val kind: WindowKind,
    val userStartMs: Long,
    val userEndMs: Long,
    val referenceStartMs: Long,
    val referenceEndMs: Long,
    val peakDifference: Double,
    val meanDifference: Double,
    val meanConfidence: Double,
)

internal data class PhaseDiagnostics(
    val activeStartMs: Long?,
    val activeEndMs: Long?,
    val phaseShiftMs: Long,
    val repBoundariesMs: List<Long>,
    val anchorsMs: List<Long>,
    val insufficientMotion: Boolean,
    val motionStrength: Double,
) {
    val repCount: Int get() = max(0, repBoundariesMs.size - 1)
}

internal data class AlignmentResult(
    val mode: AlignmentMode,
    val mapping: List<MappingPoint>,
    val windows: List<ProblemWindow>,
    val userPhase: PhaseDiagnostics,
    val referencePhase: PhaseDiagnostics,
) {
    val meanAlignmentConfidence: Double
        get() = mapping.map { it.alignmentConfidence }.averageOrZero()

    fun referenceTimestampFor(userTimestampMs: Long): Long? {
        if (mapping.isEmpty()) return null
        val exact = mapping.binarySearchBy(userTimestampMs) { it.userTimestampMs }
        if (exact >= 0) return mapping[exact].referenceTimestampMs
        val insertion = -exact - 1
        if (insertion <= 0) return mapping.first().referenceTimestampMs
        if (insertion >= mapping.size) return mapping.last().referenceTimestampMs
        val left = mapping[insertion - 1]
        val right = mapping[insertion]
        val fraction = (userTimestampMs - left.userTimestampMs).toDouble() /
            (right.userTimestampMs - left.userTimestampMs).toDouble()
        return (left.referenceTimestampMs + fraction * (right.referenceTimestampMs - left.referenceTimestampMs)).toLong()
    }
}

internal fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

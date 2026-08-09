package ai.senp.motion

import ai.senp.core.contracts.TimestampMs

object ReferenceActionVersions {
    const val PROFILE: String = "reference-action/1"
}

enum class ActionFeatureKind {
    GEOMETRY,
    TRAJECTORY,
}

data class RobustDistribution(
    val median: Double,
    val lower: Double,
    val upper: Double,
    val mad: Double,
    val inlierCount: Int,
    val sampleCount: Int,
    val outlierFraction: Double,
) {
    init {
        require(listOf(median, lower, upper, mad, outlierFraction).all(Double::isFinite))
        require(lower <= median && median <= upper)
        require(mad >= 0.0)
        require(inlierCount in 0..sampleCount)
        require(outlierFraction in 0.0..1.0)
    }
}

data class ActionFeatureProfile(
    val name: String,
    val kind: ActionFeatureKind,
    val reference: RobustDistribution,
    val scale: Double,
    val repeatability: Double,
    val motionRelevance: Double,
    val observability: Double,
    val stateDiscrimination: Double,
    val importance: Double,
    val confidence: Double,
) {
    init {
        require(name.isNotBlank())
        require(scale.isFinite() && scale > 0.0)
        listOf(repeatability, motionRelevance, observability, stateDiscrimination, importance, confidence).forEach {
            requireActionProbability(it)
        }
    }
}

data class ActionStateProfile(
    val id: String,
    val index: Int,
    val phaseStart: Double,
    val phaseEndExclusive: Double,
    val durationMs: RobustDistribution,
    val features: List<ActionFeatureProfile>,
    val confidence: Double,
) {
    init {
        require(id.isNotBlank())
        require(index >= 0)
        require(phaseStart in 0.0..1.0)
        require(phaseEndExclusive in 0.0..1.0)
        require(phaseEndExclusive > phaseStart)
        require(features.map(ActionFeatureProfile::name).distinct().size == features.size)
        requireActionProbability(confidence)
    }
}

data class ActionTransitionProfile(
    val fromStateIndex: Int,
    val toStateIndex: Int,
    val cyclicWrap: Boolean,
    val confidence: Double,
) {
    init {
        require(fromStateIndex >= 0 && toStateIndex >= 0)
        require(fromStateIndex != toStateIndex)
        requireActionProbability(confidence)
    }
}

data class ActionProfileValidation(
    val reconstructionAccuracy: Double,
    val transitionCoverage: Double,
    val meanRecognitionConfidence: Double,
    val analyzableFraction: Double,
    val referenceOutlierFraction: Double,
) {
    init {
        listOf(
            reconstructionAccuracy,
            transitionCoverage,
            meanRecognitionConfidence,
            analyzableFraction,
            referenceOutlierFraction,
        ).forEach(::requireActionProbability)
    }
}

data class ActionProfile(
    val version: String,
    val cyclic: Boolean,
    val cyclicityConfidence: Double,
    val referenceRepetitions: Int,
    val states: List<ActionStateProfile>,
    val transitions: List<ActionTransitionProfile>,
    val cycleDurationMs: RobustDistribution?,
    val featureScales: Map<String, Double>,
    val confidence: Double,
    val validation: ActionProfileValidation,
) {
    init {
        require(version.isNotBlank())
        requireActionProbability(cyclicityConfidence)
        require(referenceRepetitions >= 1)
        require(states.size >= 2)
        require(states.mapIndexed { index, state -> state.index == index }.all { it })
        require(states.zipWithNext().all { (left, right) -> left.phaseEndExclusive <= right.phaseStart })
        require(states.first().phaseStart == 0.0)
        require(states.last().phaseEndExclusive == 1.0)
        require(transitions.all { it.fromStateIndex in states.indices && it.toStateIndex in states.indices })
        require((cycleDurationMs != null) == cyclic)
        require(featureScales.isNotEmpty() && featureScales.values.all { it.isFinite() && it > 0.0 })
        requireActionProbability(confidence)
    }
}

enum class ReferenceActionCompilationFailureReason {
    INSUFFICIENT_DURATION,
    INSUFFICIENT_ANALYZABLE_FRAMES,
    INSUFFICIENT_FEATURES,
    LOW_REFERENCE_CONFIDENCE,
}

sealed interface ReferenceActionCompilation {
    data class Success(val profile: ActionProfile) : ReferenceActionCompilation

    data class Failure(
        val reason: ReferenceActionCompilationFailureReason,
        val message: String,
    ) : ReferenceActionCompilation {
        init {
            require(message.isNotBlank())
        }
    }
}

enum class ActionTrackingStatus {
    NO_ACTION,
    POSSIBLE_ENTRY,
    TRACKING,
    LOST,
    COMPLETED,
}

enum class ActionMirrorMode {
    DIRECT,
    MIRRORED,
    UNKNOWN,
}

enum class PhaseTimingClass {
    FASTER,
    WITHIN_REFERENCE_RANGE,
    SLOWER,
}

data class PhaseTimingComparison(
    val stateId: String,
    val observedDurationMs: Long,
    val referenceDurationMs: RobustDistribution,
    val relativeToReferenceMedian: Double,
    val classification: PhaseTimingClass,
    val confidence: Double,
) {
    init {
        require(stateId.isNotBlank())
        require(observedDurationMs >= 0L)
        require(relativeToReferenceMedian.isFinite() && relativeToReferenceMedian >= 0.0)
        requireActionProbability(confidence)
    }
}

data class ActionStateEstimate(
    val timestamp: TimestampMs,
    val status: ActionTrackingStatus,
    val stateId: String?,
    val stateIndex: Int?,
    val confidence: Double,
    val featureCoverage: Double,
    val mirrorMode: ActionMirrorMode,
    val completedRepetitions: Int,
    val timing: PhaseTimingComparison? = null,
) {
    init {
        require((stateId == null) == (stateIndex == null))
        stateIndex?.let { require(it >= 0) }
        requireActionProbability(confidence)
        requireActionProbability(featureCoverage)
        require(completedRepetitions >= 0)
    }
}

data class ActionRecognitionResult(
    val estimates: List<ActionStateEstimate>,
    val finalStatus: ActionTrackingStatus,
    val completedRepetitions: Int,
    val repetitionDeltaFromReference: Int,
    val trackedFraction: Double,
) {
    init {
        require(estimates.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp })
        require(completedRepetitions >= 0)
        requireActionProbability(trackedFraction)
    }
}

data class ReferenceDeviationMeasurement(
    val timestamp: TimestampMs,
    val stateId: String,
    val feature: String,
    val referenceRange: ClosedFloatingPointRange<Double>,
    val referenceMedian: Double,
    val userValue: Double,
    val signedDeltaOutsideRange: Double,
    val normalizedDeviation: Double,
    val confidence: Double,
    val persistenceCandidate: Boolean,
) {
    init {
        require(stateId.isNotBlank() && feature.isNotBlank())
        require(referenceRange.start.isFinite() && referenceRange.endInclusive.isFinite())
        require(referenceRange.start <= referenceRange.endInclusive)
        require(listOf(referenceMedian, userValue, signedDeltaOutsideRange, normalizedDeviation).all(Double::isFinite))
        require(normalizedDeviation >= 0.0)
        requireActionProbability(confidence)
    }
}

internal fun requireActionProbability(value: Double) {
    require(value.isFinite() && value in 0.0..1.0) { "probability must be finite and in [0, 1]" }
}

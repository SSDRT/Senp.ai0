package ai.senp.core.contracts

import kotlinx.serialization.Serializable

@Serializable
data class FeatureSample(
    val timestamp: TimestampMs,
    val values: Map<String, Double?>,
    val validity: FrameValidity,
) {
    init {
        require(values.keys.all(String::isNotBlank)) { "feature names must not be blank" }
        values.forEach { (name, value) ->
            if (value != null) requireFinite(value, "feature '$name'")
        }
    }
}

@Serializable
data class JointAngle(
    val timestamp: TimestampMs,
    val joint: String,
    val degrees: Double,
    val confidence: Double,
) {
    init {
        require(joint.isNotBlank()) { "joint name must not be blank" }
        requireFinite(degrees, "joint angle")
        requireProbability(confidence, "joint-angle confidence")
    }
}

@Serializable
data class MotionSeries(
    val role: VideoRole,
    val features: List<FeatureSample>,
    val angles: List<JointAngle>,
) {
    init {
        require(features.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp }) {
            "feature sample timestamps must be strictly increasing"
        }
        require(angles.zipWithNext().all { (left, right) -> left.timestamp <= right.timestamp }) {
            "joint angles must be timestamp ordered"
        }
    }
}

@Serializable
data class PhaseSegment(
    val name: String,
    val start: TimestampMs,
    val endExclusive: TimestampMs,
    val repetitionIndex: Int,
    val confidence: Double,
) {
    init {
        require(name.isNotBlank()) { "phase name must not be blank" }
        require(endExclusive > start) { "phase end must be after phase start" }
        require(repetitionIndex >= 0) { "repetition index must be non-negative" }
        requireProbability(confidence, "phase confidence")
    }
}

@Serializable
data class PhaseSeries(
    val role: VideoRole,
    val phases: List<PhaseSegment>,
) {
    init {
        require(phases.zipWithNext().all { (left, right) -> left.endExclusive <= right.start }) {
            "phase segments must be ordered and non-overlapping"
        }
    }
}

@Serializable
data class AlignmentPoint(
    val sourceTimestamp: TimestampMs,
    val referenceTimestamp: TimestampMs,
    val localCost: Double,
    val confidence: Double,
) {
    init {
        requireFinite(localCost, "alignment local cost")
        require(localCost >= 0.0) { "alignment local cost must be non-negative" }
        requireProbability(confidence, "alignment confidence")
    }
}

@Serializable
data class AlignmentResult(
    val mode: String,
    val points: List<AlignmentPoint>,
    val aggregateConfidence: Double,
) {
    init {
        require(mode.isNotBlank()) { "alignment mode must not be blank" }
        require(points.zipWithNext().all { (left, right) ->
            left.sourceTimestamp <= right.sourceTimestamp &&
                left.referenceTimestamp <= right.referenceTimestamp &&
                (left.sourceTimestamp < right.sourceTimestamp || left.referenceTimestamp < right.referenceTimestamp)
        }) { "alignment path must be monotonic and cannot repeat the same point" }
        requireProbability(aggregateConfidence, "aggregate alignment confidence")
    }
}

@Serializable
enum class ProblemCertainty {
    GENUINE,
    UNCERTAIN,
}

@Serializable
data class ProblemWindow(
    val sourceStart: TimestampMs,
    val sourceEndExclusive: TimestampMs,
    val referenceStart: TimestampMs?,
    val referenceEndExclusive: TimestampMs?,
    val label: String,
    val metric: String,
    val meanDeviation: Double,
    val peakDeviation: Double,
    val severity: Double,
    val alignmentConfidence: Double,
    val certainty: ProblemCertainty,
) {
    init {
        require(sourceEndExclusive > sourceStart) { "problem-window source end must be after start" }
        require((referenceStart == null) == (referenceEndExclusive == null)) {
            "problem-window reference bounds must both be present or both be absent"
        }
        if (referenceStart != null && referenceEndExclusive != null) {
            require(referenceEndExclusive > referenceStart) { "problem-window reference end must be after start" }
        }
        require(label.isNotBlank()) { "problem-window label must not be blank" }
        require(metric.isNotBlank()) { "problem-window metric must not be blank" }
        requireFinite(meanDeviation, "problem-window mean deviation")
        requireFinite(peakDeviation, "problem-window peak deviation")
        require(meanDeviation >= 0.0) { "problem-window mean deviation must be non-negative" }
        require(peakDeviation >= 0.0) { "problem-window peak deviation must be non-negative" }
        require(peakDeviation >= meanDeviation) { "peak deviation must be at least mean deviation" }
        requireProbability(severity, "problem-window severity")
        requireProbability(alignmentConfidence, "problem-window alignment confidence")
    }
}

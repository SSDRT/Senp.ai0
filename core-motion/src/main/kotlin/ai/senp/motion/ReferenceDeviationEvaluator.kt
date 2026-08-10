package ai.senp.motion

import kotlin.math.abs

class ReferenceDeviationEvaluator(
    private val profile: ActionProfile,
    private val config: ReferenceDeviationEvaluatorConfig = ReferenceDeviationEvaluatorConfig(),
) {
    private data class Persistence(
        val startTimestampMs: Long,
        val sign: Int,
    )

    private val persistence = mutableMapOf<String, Persistence>()

    fun reset() {
        persistence.clear()
    }

    fun evaluate(
        frame: SpatialObservationFrame,
        estimate: ActionStateEstimate,
    ): List<ReferenceDeviationMeasurement> {
        if (
            estimate.status != ActionTrackingStatus.TRACKING &&
            estimate.status != ActionTrackingStatus.COMPLETED
        ) {
            resetPersistence()
            return emptyList()
        }
        val stateIndex = estimate.stateIndex ?: return emptyList()
        if (stateIndex !in profile.states.indices) return emptyList()
        if (
            estimate.confidence < config.minimumStateConfidence ||
            estimate.featureCoverage < config.minimumStateFeatureCoverage ||
            frame.intrinsicDescriptor.confidence < config.minimumFrameConfidence
        ) {
            resetPersistence()
            return emptyList()
        }
        val state = profile.states[stateIndex]
        val activePersistenceKeys = mutableSetOf<String>()
        val deviations = state.features.mapNotNull { feature ->
            if (
                feature.kind != ActionFeatureKind.GEOMETRY ||
                feature.importance < config.minimumFeatureImportance ||
                feature.confidence < config.minimumFeatureConfidence
            ) {
                return@mapNotNull null
            }
            val lookupKey = when (estimate.mirrorMode) {
                ActionMirrorMode.MIRRORED -> mirroredSpatialKey(feature.name)
                ActionMirrorMode.DIRECT, ActionMirrorMode.UNKNOWN -> feature.name
            }
            val userValue = frame.intrinsicDescriptor.values[lookupKey] ?: return@mapNotNull null
            val reference = feature.reference
            val signedDelta = when {
                userValue < reference.lower -> userValue - reference.lower
                userValue > reference.upper -> userValue - reference.upper
                else -> 0.0
            }
            val normalizedDeviation = abs(signedDelta) / feature.scale
            if (normalizedDeviation < config.minimumNormalizedDeviation) return@mapNotNull null
            val confidence = minOf(
                estimate.confidence,
                frame.intrinsicDescriptor.confidence,
                feature.confidence,
            ).coerceIn(0.0, 1.0)
            if (confidence < config.minimumDeviationConfidence) return@mapNotNull null

            val sign = if (signedDelta < 0.0) -1 else 1
            val persistenceKey = feature.name
            activePersistenceKeys += persistenceKey
            val existing = persistence[persistenceKey]
            val current = if (existing == null || existing.sign != sign) {
                Persistence(frame.timestamp.value, sign).also { persistence[persistenceKey] = it }
            } else {
                existing
            }
            ReferenceDeviationMeasurement(
                timestamp = frame.timestamp,
                stateId = state.id,
                feature = feature.name,
                referenceRange = reference.lower..reference.upper,
                referenceMedian = reference.median,
                userValue = userValue,
                signedDeltaOutsideRange = signedDelta,
                normalizedDeviation = normalizedDeviation,
                confidence = confidence,
                persistenceCandidate = frame.timestamp.value - current.startTimestampMs >= config.persistenceDurationMs,
            )
        }
        persistence.keys
            .filter { it !in activePersistenceKeys }
            .forEach(persistence::remove)
        return deviations.sortedByDescending { it.normalizedDeviation * it.confidence }
    }

    private fun resetPersistence() {
        persistence.clear()
    }
}

data class ReferenceDeviationEvaluatorConfig(
    val minimumStateConfidence: Double = 0.42,
    val minimumStateFeatureCoverage: Double = 0.50,
    val minimumFrameConfidence: Double = 0.40,
    val minimumFeatureImportance: Double = 0.08,
    val minimumFeatureConfidence: Double = 0.45,
    val minimumNormalizedDeviation: Double = 0.45,
    val minimumDeviationConfidence: Double = 0.30,
    val persistenceDurationMs: Long = 160L,
) {
    init {
        listOf(
            minimumStateConfidence,
            minimumStateFeatureCoverage,
            minimumFrameConfidence,
            minimumFeatureImportance,
            minimumFeatureConfidence,
            minimumDeviationConfidence,
        ).forEach(::requireActionProbability)
        require(minimumNormalizedDeviation.isFinite() && minimumNormalizedDeviation >= 0.0)
        require(persistenceDurationMs >= 0L)
    }
}

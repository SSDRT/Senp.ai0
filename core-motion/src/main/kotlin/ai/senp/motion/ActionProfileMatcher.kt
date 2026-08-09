package ai.senp.motion

import kotlin.math.abs
import kotlin.math.max

internal data class ActionStateMatch(
    val stateIndex: Int,
    val score: Double,
    val coverage: Double,
    val mirrorMode: ActionMirrorMode,
)

internal fun matchActionStates(
    profile: ActionProfile,
    geometry: Map<String, Double>,
    trajectory: Map<String, Double>,
    candidateStates: List<Int>,
    mirrorModes: List<ActionMirrorMode> = listOf(ActionMirrorMode.DIRECT, ActionMirrorMode.MIRRORED),
): List<ActionStateMatch> = candidateStates
    .filter { it in profile.states.indices }
    .flatMap { stateIndex ->
        mirrorModes.mapNotNull { mirrorMode ->
            scoreActionState(profile.states[stateIndex], geometry, trajectory, mirrorMode)
        }
    }
    .groupBy(ActionStateMatch::stateIndex)
    .mapNotNull { (_, matches) -> matches.maxByOrNull(ActionStateMatch::score) }
    .sortedByDescending(ActionStateMatch::score)

private fun scoreActionState(
    state: ActionStateProfile,
    geometry: Map<String, Double>,
    trajectory: Map<String, Double>,
    mirrorMode: ActionMirrorMode,
): ActionStateMatch? {
    if (mirrorMode == ActionMirrorMode.UNKNOWN) return null
    val geometryFeatures = state.features.filter { it.kind == ActionFeatureKind.GEOMETRY && it.importance >= 0.035 }
    val trajectoryFeatures = state.features.filter { it.kind == ActionFeatureKind.TRAJECTORY && it.importance >= 0.035 }
    val totalGeometryWeight = geometryFeatures.sumOf { max(it.importance, 0.035) }
    var observedGeometryWeight = 0.0
    var geometryWeightedScore = 0.0
    for (feature in geometryFeatures) {
        val lookupKey = if (mirrorMode == ActionMirrorMode.MIRRORED) mirroredSpatialKey(feature.name) else feature.name
        val value = geometry[lookupKey] ?: continue
        val weight = max(feature.importance, 0.035)
        observedGeometryWeight += weight
        val reference = feature.reference
        val halfWidth = max(feature.scale, (reference.upper - reference.lower) / 2.0)
        val centerDistance = abs(value - reference.median) / max(halfWidth, 1e-9)
        val outsideDistance = normalizedOutsideRange(value, reference, feature.scale)
        val normalizedDistance = if (outsideDistance > 0.0) 1.0 + outsideDistance else centerDistance * 0.65
        geometryWeightedScore += gaussianSimilarity(normalizedDistance) * weight
    }
    if (totalGeometryWeight <= 0.0 || observedGeometryWeight <= 0.0) return null
    val coverage = (observedGeometryWeight / totalGeometryWeight).coerceIn(0.0, 1.0)
    val geometryScore = geometryWeightedScore / observedGeometryWeight

    var observedTrajectoryWeight = 0.0
    var trajectoryWeightedScore = 0.0
    val totalTrajectoryWeight = trajectoryFeatures.sumOf { max(it.importance, 0.035) }
    for (feature in trajectoryFeatures) {
        val baseName = feature.name.removePrefix("trajectory.")
        val lookupKey = if (mirrorMode == ActionMirrorMode.MIRRORED) mirroredSpatialKey(baseName) else baseName
        val value = trajectory[lookupKey] ?: continue
        val weight = max(feature.importance, 0.035)
        observedTrajectoryWeight += weight
        val expected = feature.reference.median
        val score = when {
            abs(expected) < 0.5 && abs(value) < 0.5 -> 1.0
            abs(expected) < 0.5 || abs(value) < 0.5 -> 0.55
            expected * value > 0.0 -> 1.0
            else -> 0.0
        }
        trajectoryWeightedScore += score * weight
    }
    val trajectoryCoverage = if (totalTrajectoryWeight > 0.0) {
        (observedTrajectoryWeight / totalTrajectoryWeight).coerceIn(0.0, 1.0)
    } else {
        0.0
    }
    val trajectoryScore = if (observedTrajectoryWeight > 0.0) {
        trajectoryWeightedScore / observedTrajectoryWeight
    } else {
        0.0
    }
    val score = if (trajectoryCoverage >= 0.20) {
        geometryScore * 0.78 + trajectoryScore * 0.22
    } else {
        geometryScore
    }
    return ActionStateMatch(
        stateIndex = state.index,
        score = (score * (0.70 + 0.30 * coverage) * (0.70 + 0.30 * state.confidence)).coerceIn(0.0, 1.0),
        coverage = coverage,
        mirrorMode = mirrorMode,
    )
}

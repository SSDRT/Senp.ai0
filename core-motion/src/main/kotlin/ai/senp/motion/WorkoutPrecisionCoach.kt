package ai.senp.motion

import kotlin.math.abs
import kotlin.math.max

enum class WorkoutReferenceMode { AUTO, MOVEMENT, STATIC_HOLD }
enum class WorkoutMeasurementUnit { DEGREES, TORSO_UNITS, RATIO }
enum class WorkoutFeatureStatus {
    MONITORED, CONTEXT_ONLY, UNSUPPORTED, WEAK_REFERENCE, MISSING_OBSERVATION,
    WEAK_OBSERVATION, INCOMPATIBLE_PROPORTIONS, INVALID_MEASUREMENT,
}
enum class WorkoutAbstention {
    NONE, ACTION_NOT_CONFIRMED, WEAK_ACTION, WEAK_FRAME, WEAK_REFERENCE, UNKNOWN_MIRROR,
    UNTYPED_REFERENCE, MIXED_REFERENCE_SPACE, INCOMPATIBLE_SPACE, NO_RELIABLE_FEATURES,
}

data class WorkoutTolerance(val enter: Double, val exit: Double) {
    init { require(enter.isFinite() && exit.isFinite() && enter > 0.0 && exit >= 0.0 && exit < enter) }
}

enum class WorkoutTargetOrigin { REFERENCE_LEARNED, SOURCE_GUIDANCE, ENGINEERING_OVERRIDE }
data class WorkoutTargetRange(val lower: Double, val upper: Double, val origin: WorkoutTargetOrigin, val ruleId: String) {
    init { require(lower.isFinite() && upper.isFinite() && lower >= 0 && lower <= upper && ruleId.isNotBlank()) }
}

/** Distances are in torso lengths; angle tolerances are degrees outside the learned range.
 * These are configurable reference-matching tolerances, not clinical or expert form standards.
 */
data class WorkoutPrecisionConfig(
    val angularTolerance: WorkoutTolerance = WorkoutTolerance(10.0, 6.0),
    val distanceTolerance: WorkoutTolerance = WorkoutTolerance(0.12, 0.07),
    val featureOverrides: Map<String, WorkoutTolerance> = emptyMap(),
    val stateFeatureOverrides: Map<String, Map<String, WorkoutTolerance>> = emptyMap(),
    val referenceNoiseMultiplier: Double = 2.0,
    val minimumStateConfidence: Double = 0.55,
    val minimumStateCoverage: Double = 0.60,
    val minimumFeatureConfidence: Double = 0.65,
    val minimumReferenceConfidence: Double = 0.55,
    val minimumReferenceSamples: Int = 5,
    val confirmationMs: Long = 240,
    val releaseMs: Long = 160,
    val maximumEvidenceGapMs: Long = 250,
    val repeatCooldownMs: Long = 3000,
    val maximumProportionDifference: Double = 0.25,
    val minimumProportionFeatures: Int = 4,
    val allowProjected2d: Boolean = false,
    val relationTolerance: WorkoutTolerance = WorkoutTolerance(0.15, 0.08),
    val stateTargetRanges: Map<String, Map<String, WorkoutTargetRange>> = emptyMap(),
) {
    init {
        require(featureOverrides.keys.all { it.startsWith("angle.") || it.startsWith("distance.") || it.startsWith("relation.") })
        require(stateFeatureOverrides.values.all { features -> features.keys.all { it.startsWith("angle.") || it.startsWith("distance.") || it.startsWith("relation.") } })
        require(referenceNoiseMultiplier.isFinite() && referenceNoiseMultiplier >= 0.0)
        listOf(minimumStateConfidence, minimumStateCoverage, minimumFeatureConfidence, minimumReferenceConfidence).forEach(::requireActionProbability)
        require(minimumReferenceSamples >= 2 && minimumProportionFeatures >= 1)
        require(confirmationMs >= 0 && releaseMs >= 0 && maximumEvidenceGapMs > 0 && repeatCooldownMs >= 0)
        require(maximumProportionDifference.isFinite() && maximumProportionDifference > 0.0)
    }
}

data class WorkoutFeatureDecision(
    val feature: String,
    val status: WorkoutFeatureStatus,
    val unit: WorkoutMeasurementUnit? = null,
    val measured: Double? = null,
    val referenceLower: Double? = null,
    val referenceUpper: Double? = null,
    val signedOutsideRange: Double? = null,
    val enterThreshold: Double? = null,
    val exitThreshold: Double? = null,
    val confidence: Double = 0.0,
    val persistenceMs: Long = 0,
    val observedFeature: String? = null,
    val targetOrigin: WorkoutTargetOrigin = WorkoutTargetOrigin.REFERENCE_LEARNED,
    val targetRuleId: String? = null,
)

data class WorkoutSuggestion(
    val key: String,
    val feature: String,
    val stateId: String,
    val label: String,
    val measurement: WorkoutFeatureDecision,
    val activeSinceMs: Long,
)

data class WorkoutPrecisionResult(
    val timestampMs: Long,
    val abstention: WorkoutAbstention,
    val decisions: List<WorkoutFeatureDecision>,
    val activeSuggestions: List<WorkoutSuggestion>,
    val triggers: List<WorkoutSuggestion>,
)

/** Exercise-name-independent, causal suggestion gate over any calibrated reference action/hold.
 * No runtime adaptation of target ranges: a sustained mistake must never become the learned target.
 */
class WorkoutPrecisionCoach(
    private val profile: ActionProfile,
    private val config: WorkoutPrecisionConfig = WorkoutPrecisionConfig(),
) {
    init {
        val featureNames = profile.states.flatMap { state -> state.features.map(ActionFeatureProfile::name) }.toSet()
        require(config.featureOverrides.keys.all { it in featureNames }) { "threshold overrides must name a calibrated reference feature" }
        require(config.stateFeatureOverrides.all { (stateId, overrides) ->
            val state = profile.states.firstOrNull { it.id == stateId }
            state != null && overrides.keys.all { name -> state.features.any { it.name == name } }
        }) { "state overrides must name a calibrated state and feature" }
        require(config.stateTargetRanges.all { (stateId, targets) ->
            val state = profile.states.firstOrNull { it.id == stateId }
            state != null && targets.all { (name, range) -> state.features.any { it.name == name } &&
                (name.startsWith("angle.") || name.startsWith("distance.") || name.startsWith("relation.")) &&
                (!name.startsWith("angle.") || range.upper <= 180.0) }
        }) { "target overrides must name a calibrated measurement and valid range" }
    }
    private data class Track(val sign: Int, val beganMs: Long, var activeMs: Long? = null, var releaseMs: Long? = null)
    private data class Context(val subject: String?, val mirror: ActionMirrorMode, val space: SpatialEvidenceKind, val segment: Int?)
    private val tracks = mutableMapOf<String, Track>()
    private val lastTriggers = mutableMapOf<String, Long>()
    private var previousMs: Long? = null
    private var previousState: Int? = null
    private var previousContext: Context? = null

    @Synchronized fun reset() {
        tracks.clear(); lastTriggers.clear(); previousMs = null; previousState = null; previousContext = null
    }

    @Synchronized fun evaluate(frame: SpatialObservationFrame, estimate: ActionStateEstimate): WorkoutPrecisionResult {
        val now = frame.timestamp.value
        require(estimate.timestamp == frame.timestamp) { "pose and action estimate must share capture timestamp" }
        val before = previousMs
        require(before == null || now > before) { "precision timestamps must strictly increase" }
        val context = Context(frame.selectedSubjectId, estimate.mirrorMode, frame.evidenceKind, frame.spatialSegmentId)
        val stateIndex = estimate.stateIndex
        // A new phase has its own target range/tolerance. Evidence against the old target
        // cannot satisfy confirmation against a newly entered target, even on a legal transition.
        if ((before != null && now - before > config.maximumEvidenceGapMs) || previousContext != context ||
            previousState != stateIndex
        ) tracks.clear()
        if (previousContext != context) lastTriggers.clear()
        previousMs = now; previousContext = context; previousState = stateIndex
        fun abstain(reason: WorkoutAbstention): WorkoutPrecisionResult {
            tracks.clear()
            return WorkoutPrecisionResult(now, reason, emptyList(), emptyList(), emptyList())
        }
        if (estimate.status !in setOf(ActionTrackingStatus.TRACKING, ActionTrackingStatus.COMPLETED) ||
            stateIndex == null || stateIndex !in profile.states.indices || estimate.stateId != profile.states[stateIndex].id
        ) return abstain(WorkoutAbstention.ACTION_NOT_CONFIRMED)
        if (estimate.confidence < config.minimumStateConfidence || estimate.featureCoverage < config.minimumStateCoverage)
            return abstain(WorkoutAbstention.WEAK_ACTION)
        if (profile.confidence < config.minimumReferenceConfidence || profile.states[stateIndex].confidence < config.minimumReferenceConfidence ||
            profile.validation.analyzableFraction < config.minimumStateCoverage
        ) return abstain(WorkoutAbstention.WEAK_REFERENCE)
        if (frame.intrinsicDescriptor.confidence < config.minimumStateConfidence) return abstain(WorkoutAbstention.WEAK_FRAME)
        if (estimate.mirrorMode == ActionMirrorMode.UNKNOWN) return abstain(WorkoutAbstention.UNKNOWN_MIRROR)
        if (profile.version != ReferenceActionVersions.PROFILE || profile.referenceEvidenceKinds.isEmpty()) return abstain(WorkoutAbstention.UNTYPED_REFERENCE)
        if (profile.referenceEvidenceKinds.size != 1) return abstain(WorkoutAbstention.MIXED_REFERENCE_SPACE)
        if (frame.evidenceKind !in profile.referenceEvidenceKinds ||
            (frame.evidenceKind != SpatialEvidenceKind.THREE_D && !(config.allowProjected2d && frame.evidenceKind == SpatialEvidenceKind.IMAGE_2D))
        ) return abstain(WorkoutAbstention.INCOMPATIBLE_SPACE)
        val state = profile.states[stateIndex]
        fun lookup(name: String) = if (estimate.mirrorMode == ActionMirrorMode.MIRRORED) mirroredSpatialKey(name) else name
        val proportionErrors = state.features.filter { it.kind == ActionFeatureKind.GEOMETRY && it.name.startsWith("ratio.") }
            .mapNotNull { feature ->
                val key = lookup(feature.name)
                val value = frame.intrinsicDescriptor.values[key] ?: return@mapNotNull null
                val confidence = frame.intrinsicDescriptor.featureConfidences[key] ?: return@mapNotNull null
                if (confidence < config.minimumFeatureConfidence || feature.confidence < config.minimumReferenceConfidence ||
                    feature.reference.inlierCount < config.minimumReferenceSamples || feature.reference.outlierFraction > 0.25
                ) return@mapNotNull null
                abs(value - feature.reference.median) / max(abs(feature.reference.median), 1e-6)
            }
        val comparableProportions = proportionErrors.size >= config.minimumProportionFeatures &&
            proportionErrors.count { it > config.maximumProportionDifference } <= proportionErrors.size / 5
        val active = mutableListOf<WorkoutSuggestion>()
        val events = mutableListOf<WorkoutSuggestion>()
        val monitoredKeys = mutableSetOf<String>()
        val decisions = state.features.map { feature ->
            val name = feature.name
            val unit = when {
                name.startsWith("angle.") -> WorkoutMeasurementUnit.DEGREES
                name.startsWith("distance.") -> WorkoutMeasurementUnit.TORSO_UNITS
                name.startsWith("relation.") -> WorkoutMeasurementUnit.RATIO
                else -> null
            }
            fun skip(status: WorkoutFeatureStatus): WorkoutFeatureDecision { tracks.remove(name); return WorkoutFeatureDecision(name, status, unit) }
            if (feature.kind == ActionFeatureKind.TRAJECTORY || name.startsWith("ratio.") || name.startsWith("signed.")) return@map skip(WorkoutFeatureStatus.CONTEXT_ONLY)
            if (unit == null) return@map skip(WorkoutFeatureStatus.UNSUPPORTED)
            if (feature.reference.inlierCount < config.minimumReferenceSamples || feature.confidence < config.minimumReferenceConfidence ||
                feature.observability < config.minimumReferenceConfidence || feature.reference.outlierFraction > 0.25
            ) return@map skip(WorkoutFeatureStatus.WEAK_REFERENCE)
            val key = lookup(name)
            val value = frame.intrinsicDescriptor.values[key] ?: return@map skip(WorkoutFeatureStatus.MISSING_OBSERVATION)
            val confidence = minOf(estimate.confidence, feature.confidence, frame.intrinsicDescriptor.confidence, frame.intrinsicDescriptor.featureConfidences[key] ?: 0.0)
            if (confidence < config.minimumFeatureConfidence) return@map skip(WorkoutFeatureStatus.WEAK_OBSERVATION)
            if (unit == WorkoutMeasurementUnit.TORSO_UNITS && !comparableProportions) return@map skip(WorkoutFeatureStatus.INCOMPATIBLE_PROPORTIONS)
            if ((unit == WorkoutMeasurementUnit.DEGREES && value !in 0.0..180.0) ||
                (unit != WorkoutMeasurementUnit.DEGREES && value < 0.0)
            ) return@map skip(WorkoutFeatureStatus.INVALID_MEASUREMENT)
            val tolerance = config.stateFeatureOverrides[state.id]?.get(name) ?: config.featureOverrides[name] ?:
                when (unit) {
                    WorkoutMeasurementUnit.DEGREES -> config.angularTolerance
                    WorkoutMeasurementUnit.TORSO_UNITS -> config.distanceTolerance
                    WorkoutMeasurementUnit.RATIO -> config.relationTolerance
                }
            val noise = 1.4826 * feature.reference.mad * config.referenceNoiseMultiplier
            val enter = max(tolerance.enter, noise)
            val exit = minOf(max(tolerance.exit, noise * 0.6), enter * 0.9)
            val target = config.stateTargetRanges[state.id]?.get(name)
            val targetLower = target?.lower ?: feature.reference.lower
            val targetUpper = target?.upper ?: feature.reference.upper
            val lower = if (unit == WorkoutMeasurementUnit.DEGREES) targetLower.coerceIn(0.0, 180.0) else targetLower.coerceAtLeast(0.0)
            val upper = if (unit == WorkoutMeasurementUnit.DEGREES) targetUpper.coerceIn(0.0, 180.0) else targetUpper.coerceAtLeast(lower)
            val outside = when { value < lower -> value - lower; value > upper -> value - upper; else -> 0.0 }
            var track = tracks[name]
            val sign = when { outside < 0.0 -> -1; outside > 0.0 -> 1; else -> track?.sign ?: 1 }
            if (track != null && track.sign != sign) { tracks.remove(name); track = null }
            if (abs(outside) > enter) {
                if (track == null) { track = Track(sign, now); tracks[name] = track }
                track.releaseMs = null
                if (now - track.beganMs >= config.confirmationMs && track.activeMs == null) track.activeMs = now
            } else if (track?.activeMs != null) {
                if (abs(outside) <= exit) {
                    if (track.releaseMs == null) track.releaseMs = now
                    if (now - requireNotNull(track.releaseMs) >= config.releaseMs) { tracks.remove(name); track = null }
                } else track.releaseMs = null
            } else { tracks.remove(name); track = null }
            monitoredKeys += name
            val decision = WorkoutFeatureDecision(name, WorkoutFeatureStatus.MONITORED, unit, value, lower, upper,
                outside, enter, exit, confidence, track?.let { now - it.beganMs } ?: 0L, key,
                target?.origin ?: WorkoutTargetOrigin.REFERENCE_LEARNED, target?.ruleId)
            val activeSince = track?.activeMs
            if (activeSince != null) {
                val eventKey = "$name|${track.sign}"
                val suggestion = WorkoutSuggestion(eventKey, name, state.id, suggestionLabel(key, track.sign), decision, activeSince)
                active += suggestion
                val last = lastTriggers[eventKey]
                if (abs(outside) > enter && (last == null || now - last >= config.repeatCooldownMs)) {
                    lastTriggers[eventKey] = now; events += suggestion
                }
            }
            decision
        }
        tracks.keys.filter { it !in monitoredKeys }.forEach(tracks::remove)
        val ordered = active.sortedByDescending { abs(it.measurement.signedOutsideRange ?: 0.0) / (it.measurement.enterThreshold ?: 1.0) * it.measurement.confidence }
        val abstention = if (decisions.none { it.status == WorkoutFeatureStatus.MONITORED }) WorkoutAbstention.NO_RELIABLE_FEATURES else WorkoutAbstention.NONE
        return WorkoutPrecisionResult(now, abstention, decisions, ordered, events)
    }

    private fun suggestionLabel(name: String, sign: Int): String {
        val subject = name.substringAfter('.').replace('_', ' ')
        if (name.startsWith("angle.")) {
            val action = when {
                name.endsWith("elbow") || name.endsWith("knee") -> if (sign > 0) "Bend" else "Straighten"
                else -> "Adjust"
            }
            return "$action your $subject toward the demonstrated angle"
        }
        val action = if (sign > 0) "Reduce" else "Increase"
        return "$action $subject toward the demonstrated spacing"
    }
}

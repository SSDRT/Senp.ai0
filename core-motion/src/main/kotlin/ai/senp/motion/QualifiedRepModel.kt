package ai.senp.motion

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

enum class RepPolicyMode { REFERENCE_MATCH, REFERENCE_WITH_TECHNIQUE_GUARDS }
enum class RepObservedSide { LEFT, RIGHT }
enum class RepVerdict { CORRECT, ACCEPTABLE, REJECTED, UNASSESSABLE }
enum class RepStage { SYNCHRONIZING, READY, OUTBOUND, RETURNING, UNAVAILABLE }
enum class RepReason {
    CLEAR_FORM_DEVIATION, EARLY_REVERSAL, INSUFFICIENT_RANGE, MISSING_CRITICAL_EVIDENCE,
    INSUFFICIENT_COVERAGE, AMBIGUOUS_FORM, EVIDENCE_GAP, CONTEXT_CHANGED,
    MODEL_MISMATCH, PREPROCESSING_MISMATCH, ATTEMPT_TIMEOUT, INTERRUPTED, INSUFFICIENT_PATH_EVIDENCE,
}

/** Identity of the source reference and the raw coordinate pipeline. Declared view is not camera verification. */
data class RepEvidenceFingerprint(
    val referenceVideoSha256: String,
    val poseModelSha256: String,
    val preprocessingId: String = RAW_WORLD_PIPELINE,
    val declaredView: String = "visible-side",
) {
    init {
        require(referenceVideoSha256.matches(Regex("[a-f0-9]{64}")))
        require(poseModelSha256.matches(Regex("[a-f0-9]{64}")))
        require(preprocessingId.isNotBlank() && declaredView.isNotBlank())
    }
    companion object { const val RAW_WORLD_PIPELINE = "mp33-raw-world-workout/1" }
}

/** Engineering defaults for an experimental reference comparator, not validated exercise standards. */
data class QualifiedRepConfig(
    val side: RepObservedSide = RepObservedSide.LEFT,
    val mirrorMode: ActionMirrorMode = ActionMirrorMode.DIRECT,
    val mode: RepPolicyMode = RepPolicyMode.REFERENCE_MATCH,
    val minimumConfidence: Double = 0.65,
    val minimumReferenceConfidence: Double = 0.55,
    val minimumReferenceExcursionDegrees: Double = 20.0,
    val nominalAllowanceDegrees: Double = 5.0,
    val acceptableAllowanceDegrees: Double = 12.0,
    val rejectAllowanceDegrees: Double = 30.0,
    val noiseMultiplier: Double = 2.0,
    val readyProgress: Double = 0.10,
    val departureProgress: Double = 0.20,
    val requiredProgress: Double = 0.85,
    val nominalProgress: Double = 0.95,
    val clearlyIncompleteProgress: Double = 0.65,
    val reversalExcursion: Double = 0.12,
    val readyConfirmationMs: Long = 100,
    val milestoneConfirmationMs: Long = 60,
    val reversalConfirmationMs: Long = 80,
    val faultConfirmationMs: Long = 160,
    val maximumEvidenceGapMs: Long = 250,
    val maximumAttemptMs: Long = 30_000,
    val minimumVisibleFraction: Double = 0.90,
    val maximumAmbiguousFraction: Double = 0.10,
    val minimumReferenceCycles: Int = 3,
    val maximumReferenceMadDegrees: Double = 12.0,
    val maximumReferenceBandWidthDegrees: Double = 60.0,
    val minimumReferenceReconstruction: Double = 0.80,
    val referenceCurveNodes: Int = 6,
    val requireObservedPath: Boolean = true,
) {
    init {
        require(mirrorMode != ActionMirrorMode.UNKNOWN)
        listOf(minimumConfidence,minimumReferenceConfidence,minimumVisibleFraction,maximumAmbiguousFraction,minimumReferenceReconstruction).forEach(::requireActionProbability)
        require(minimumReferenceExcursionDegrees.isFinite() && minimumReferenceExcursionDegrees > 0)
        require(listOf(nominalAllowanceDegrees,acceptableAllowanceDegrees,rejectAllowanceDegrees,noiseMultiplier).all(Double::isFinite))
        require(nominalAllowanceDegrees >= 0 && nominalAllowanceDegrees < acceptableAllowanceDegrees && acceptableAllowanceDegrees < rejectAllowanceDegrees)
        require(noiseMultiplier >= 0)
        require(readyProgress > 0 && readyProgress < departureProgress && departureProgress < clearlyIncompleteProgress && clearlyIncompleteProgress < requiredProgress && requiredProgress <= 1)
        require(nominalProgress >= requiredProgress && nominalProgress <= 1)
        require(reversalExcursion.isFinite() && reversalExcursion > 0 && reversalExcursion < clearlyIncompleteProgress)
        require(listOf(readyConfirmationMs,milestoneConfirmationMs,reversalConfirmationMs,faultConfirmationMs).all { it > 0 })
        require(maximumEvidenceGapMs > 0 && maximumAttemptMs > maximumEvidenceGapMs)
        require(minimumReferenceCycles >= 1)
        require(maximumReferenceMadDegrees.isFinite() && maximumReferenceMadDegrees > 0)
        require(maximumReferenceBandWidthDegrees.isFinite() && maximumReferenceBandWidthDegrees > 0 && maximumReferenceBandWidthDegrees <= 180)
        require(referenceCurveNodes in setOf(6,10))
    }
}

data class RepFault(
    val reason: RepReason, val feature: String?, val firstEvidenceMs: Long,
    val confirmedMs: Long, val deviationDegrees: Double? = null, val ruleId: String? = null,
)
data class RepAttemptResult(
    val attemptId: Long, val startedMs: Long, val completedMs: Long, val verdict: RepVerdict,
    val reasons: Set<RepReason>, val faults: List<RepFault>, val maximumProgress: Double,
    val visibleFraction: Double, val ambiguousFraction: Double,
    val outboundPathObserved: Boolean = false, val returnPathObserved: Boolean = false,
)
data class QualifiedRepCounts(val correct: Int = 0, val acceptable: Int = 0, val rejected: Int = 0, val unassessable: Int = 0) {
    val qualified: Int get() = correct + acceptable
    val finalized: Int get() = qualified + rejected + unassessable
}
data class QualifiedRepOutput(
    val timestampMs: Long, val referenceKey: String, val exerciseId: String, val stage: RepStage,
    val counts: QualifiedRepCounts, val activeAttemptId: Long? = null, val progress: Double? = null,
    val activeDisqualified: Boolean = false, val newFaults: List<RepFault> = emptyList(),
    val finalizedAttempts: List<RepAttemptResult> = emptyList(), val unavailableReason: RepReason? = null,
    val releaseQualified: Boolean = false,
    val observedSide: RepObservedSide = RepObservedSide.LEFT,
    val policyMode: RepPolicyMode = RepPolicyMode.REFERENCE_MATCH,
)

enum class RepReferenceFailure { MISSING_PROVENANCE, UNKNOWN_EXERCISE, UNSUPPORTED_EXERCISE, STATIC_OR_NONCYCLIC, WEAK_REFERENCE, MISSING_FEATURE, INSUFFICIENT_EXCURSION, INSUFFICIENT_REFERENCE_CYCLES, LOW_SELF_RECONSTRUCTION, UNRESOLVED_GUIDANCE }
sealed interface QualifiedRepCompilation {
    data class Ready(val reference: QualifiedRepReference) : QualifiedRepCompilation
    data class Unavailable(val reason: RepReferenceFailure, val detail: String) : QualifiedRepCompilation
}

internal data class RepExerciseSpec(val id: String, val driver: String, val readyAtMaximum: Boolean, val quality: List<String>)
internal val repExerciseSpecs = listOf(
    RepExerciseSpec("bodyweight_squat","knee",true,listOf("hip")),
    RepExerciseSpec("biceps_curl","elbow",true,listOf("shoulder")),
    RepExerciseSpec("push_up","elbow",true,listOf("body_line")),
    RepExerciseSpec("overhead_press","shoulder",false,listOf("elbow")),
    RepExerciseSpec("glute_bridge","hip",false,listOf("knee")),
    RepExerciseSpec("lateral_raise","shoulder",false,listOf("elbow")),
).associateBy { it.id }

data class RepReferenceCalibration(val observedCycles: Int, val rejectedCandidates: Int, val sourceFrames: Int,
    val selectedFrames: Int, val firstSelectedMs: Long, val lastSelectedMs: Long,
    val acceptedSelfCycles: Int, val selfAcceptanceFraction: Double)

class QualifiedRepReference internal constructor(
    val profile: ActionProfile, val fingerprint: RepEvidenceFingerprint, val config: QualifiedRepConfig,
    internal val spec: RepExerciseSpec, val driverFeature: String, val requiredFeatures: Set<String>,
    internal val readyAngle: Double, internal val farAngle: Double,
    internal val nodes: List<Node>, internal val sourceTargets: Map<String,Map<String,WorkoutTargetRange>>,
    internal val sourceTolerances: Map<String,Map<String,WorkoutTolerance>>,
    val calibration: RepReferenceCalibration? = null,
) {
    internal data class Node(val state: ActionStateProfile, val progress: Double, val outbound: Boolean, val returning: Boolean)
    val exerciseId: String get() = spec.id
    val schema: String get() = "qualified-reference-reps/3"
    val referenceKey: String = run {
        val canonical = buildString {
            append(schema).append('|').append(fingerprint).append('|').append(config).append('|').append(profile.version)
            append('|').append(profile.cyclic).append('|').append(profile.referenceRepetitions).append('|').append(profile.confidence).append('|').append(profile.validation)
            append('|').append(profile.referenceEvidenceKinds.sortedBy { it.name }).append('|').append(spec)
            profile.states.forEach { state ->
                append('|').append(state.id).append(':').append(state.phaseStart).append(':').append(state.phaseEndExclusive).append(':').append(state.confidence)
                state.features.sortedBy { it.name }.forEach { append('|').append(it) }
            }
            sourceTargets.toSortedMap().forEach { (state,targets) ->
                targets.toSortedMap().forEach { (feature,target) -> append('|').append(state).append(':').append(feature).append(':').append(target) }
            }
            sourceTolerances.toSortedMap().forEach { (state,tolerances) ->
                tolerances.toSortedMap().forEach { (feature,tolerance) -> append('|').append(state).append(':').append(feature).append(':').append(tolerance) }
            }
        }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    fun newEngine(): QualifiedRepEngine = QualifiedRepEngine(this)
}

object QualifiedRepCompiler {
    val supportedExerciseIds: Set<String> = repExerciseSpecs.keys
    fun compile(sequence: SpatialSequenceAnalysis, exerciseId: String, fingerprint: RepEvidenceFingerprint,
        config: QualifiedRepConfig = QualifiedRepConfig()): QualifiedRepCompilation =
        compileRepCycles(sequence,exerciseId,fingerprint,config)
    fun compile(profile: ActionProfile, exerciseId: String, fingerprint: RepEvidenceFingerprint,
        config: QualifiedRepConfig = QualifiedRepConfig(), catalog: ExerciseTechniqueCatalog = ExerciseTechniqueCatalog.builtIn): QualifiedRepCompilation {
        fun fail(reason: RepReferenceFailure, detail: String) = QualifiedRepCompilation.Unavailable(reason,detail)
        val exercise = catalog.find(exerciseId) ?: return fail(RepReferenceFailure.UNKNOWN_EXERCISE,"Select an explicit exercise variant")
        val spec = repExerciseSpecs[exercise.id] ?: return fail(RepReferenceFailure.UNSUPPORTED_EXERCISE,"No qualified-rep policy for ${exercise.id}")
        if (profile.staticHold || !profile.cyclic) return fail(RepReferenceFailure.STATIC_OR_NONCYCLIC,"Rep acceptance requires a cyclic movement reference; holds keep duration semantics")
        if (profile.referenceRepetitions < config.minimumReferenceCycles) return fail(RepReferenceFailure.INSUFFICIENT_REFERENCE_CYCLES,"Too few observed reference repetitions")
        if (profile.version != ReferenceActionVersions.PROFILE || profile.referenceEvidenceKinds != setOf(SpatialEvidenceKind.THREE_D) ||
            profile.confidence < config.minimumReferenceConfidence || profile.validation.analyzableFraction < config.minimumVisibleFraction) {
            return fail(RepReferenceFailure.WEAK_REFERENCE,"A reliable, typed 3D reference is required")
        }
        val side = config.side.name.lowercase()
        val driver = "angle.${side}_${spec.driver}"
        val required = (spec.quality.map { "angle.${side}_$it" } + driver).toMutableSet()
        val sourceTargets: Map<String,Map<String,WorkoutTargetRange>>
        val sourceTolerances: Map<String,Map<String,WorkoutTolerance>>
        if (config.mode == RepPolicyMode.REFERENCE_WITH_TECHNIQUE_GUARDS) {
            val technique = ExerciseTechniqueCoach(profile,exercise.id,catalog = catalog)
            val sourceRuleIds = exercise.rules.filter { it.mode == TechniqueRuleMode.SOURCE_TARGET && it.features.any { f -> f.startsWith("angle.${side}_") } }.map { it.id }.toSet()
            if (technique.bindings.any { it.ruleId in sourceRuleIds && it.status in setOf(TechniqueBindingStatus.PHASE_UNRESOLVED,TechniqueBindingStatus.MISSING_REFERENCE_FEATURE,TechniqueBindingStatus.CONFLICTING_TARGETS) }) {
                return fail(RepReferenceFailure.UNRESOLVED_GUIDANCE,"Selected-side source guidance cannot be resolved against this reference")
            }
            sourceTargets = technique.compiledConfig.stateTargetRanges.mapValues { (_,values) -> values.filterKeys { it.startsWith("angle.${side}_") } }
            sourceTolerances = technique.compiledConfig.stateFeatureOverrides
            required += sourceTargets.values.flatMap { it.keys }
        } else { sourceTargets = emptyMap(); sourceTolerances = emptyMap() }
        for (state in profile.states) for (name in required) {
            val feature = state.features.firstOrNull { it.name == name && it.kind == ActionFeatureKind.GEOMETRY }
                ?: return fail(RepReferenceFailure.MISSING_FEATURE,"Missing $name in ${state.id}")
            if (state.confidence < config.minimumReferenceConfidence || feature.confidence < config.minimumReferenceConfidence ||
                feature.observability < config.minimumReferenceConfidence || feature.reference.inlierCount < 5 || feature.reference.outlierFraction > 0.25 ||
                feature.reference.lower < 0 || feature.reference.upper > 180 || feature.reference.mad > config.maximumReferenceMadDegrees ||
                feature.reference.upper-feature.reference.lower > config.maximumReferenceBandWidthDegrees) return fail(RepReferenceFailure.WEAK_REFERENCE,"Unreliable/noisy angle range for $name in ${state.id}")
        }
        val anchors = profile.states.map { state -> state.features.first { it.name == driver }.reference.median }
        val lo = anchors.min(); val hi = anchors.max()
        if (hi-lo < config.minimumReferenceExcursionDegrees) return fail(RepReferenceFailure.INSUFFICIENT_EXCURSION,"The selected-side driver lacks reliable movement excursion")
        val ready = if (spec.readyAtMaximum) hi else lo; val far = if (spec.readyAtMaximum) lo else hi
        val progress = anchors.map { (it-ready)/(far-ready) }
        val nodes = profile.states.mapIndexed { index,state ->
            val p = progress[index]; val slope = progress[(index+1)%progress.size] - progress[(index-1+progress.size)%progress.size]
            val endpoint = p <= 0.10 || p >= 0.90
            QualifiedRepReference.Node(state,p,endpoint || slope >= -0.01,endpoint || slope <= 0.01)
        }
        if (nodes.count { it.outbound } < 2 || nodes.count { it.returning } < 2) return fail(RepReferenceFailure.INSUFFICIENT_EXCURSION,"Cannot resolve outbound/return reference branches")
        return QualifiedRepCompilation.Ready(QualifiedRepReference(profile,fingerprint,config,spec,driver,required.toSet(),ready,far,nodes,sourceTargets,sourceTolerances))
    }
}

internal fun RepObservedSide.observedFeature(feature: String, mirror: ActionMirrorMode): String =
    if (mirror == ActionMirrorMode.MIRRORED) mirroredSpatialKey(feature) else feature

internal data class RepAngleBand(val lower: Double, val upper: Double, val nominal: Double, val acceptable: Double, val reject: Double, val ruleId: String?)
internal fun QualifiedRepReference.band(feature: String, progress: Double, returning: Boolean): RepAngleBand {
    val branch = nodes.filter { if (returning) it.returning else it.outbound }.sortedBy { it.progress }
    val p = progress.coerceIn(0.0,1.0)
    val a = branch.lastOrNull { it.progress <= p } ?: branch.first()
    val b = branch.firstOrNull { it.progress >= p } ?: branch.last()
    val closest = if (abs(p-a.progress) <= abs(b.progress-p)) a else b
    val target = sourceTargets[closest.state.id]?.get(feature)
    val sourceTolerance = sourceTolerances[closest.state.id]?.get(feature)
    val fa = a.state.features.first { it.name == feature }; val fb = b.state.features.first { it.name == feature }
    val alpha = if (b.progress-a.progress > 1e-8) (p-a.progress)/(b.progress-a.progress) else 0.0
    fun lerp(x: Double,y: Double) = x+(y-x)*alpha
    val noise = 1.4826*max(fa.reference.mad,fb.reference.mad)*config.noiseMultiplier
    val nominal = max(config.nominalAllowanceDegrees,noise*0.5)
    val acceptable = max(max(config.acceptableAllowanceDegrees,sourceTolerance?.enter ?: 0.0),max(noise,nominal+1))
    return RepAngleBand(target?.lower ?: lerp(fa.reference.lower,fb.reference.lower),
        target?.upper ?: lerp(fa.reference.upper,fb.reference.upper),nominal,acceptable,
        max(config.rejectAllowanceDegrees,acceptable*1.75),target?.ruleId)
}

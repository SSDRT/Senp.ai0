package ai.senp.motion

enum class TechniqueBindingStatus { APPLIED_REFERENCE, APPLIED_SOURCE_TARGET, UNOBSERVABLE, MISSING_REFERENCE_FEATURE, PHASE_UNRESOLVED, CONFLICTING_TARGETS }
data class TechniqueRuleBinding(val ruleId: String, val feature: String?, val stateId: String?, val status: TechniqueBindingStatus,
    val summary: String, val sourceIds: List<String>, val limitation: String)
data class ExerciseTechniqueResult(val exerciseId: String, val catalogVersion: String, val precision: WorkoutPrecisionResult,
    val bindings: List<TechniqueRuleBinding>)

/** Selected exercise is explicit. A four-state sample classifier cannot prescribe arbitrary techniques. */
class ExerciseTechniqueCoach(
    reference: ActionProfile,
    exerciseId: String,
    config: WorkoutPrecisionConfig = WorkoutPrecisionConfig(),
    catalog: ExerciseTechniqueCatalog = ExerciseTechniqueCatalog.builtIn,
) {
    val exercise = catalog.requireExercise(exerciseId)
    val catalogVersion = catalog.version
    val bindings: List<TechniqueRuleBinding>
    private val cues = mutableMapOf<Pair<String, String>, String>()
    private val coach: WorkoutPrecisionCoach
    init {
        WorkoutPrecisionCoach(reference, config) // Validate caller state/feature names before filtering the catalogue scope.
        require(config.stateTargetRanges.isEmpty()) { "Selected source policies own target ranges; use margin overrides or a different documented variant" }
        val audit = mutableListOf<TechniqueRuleBinding>()
        val selected = mutableMapOf<String, MutableSet<String>>()
        val targets = mutableMapOf<String, MutableMap<String, WorkoutTargetRange>>()
        val tolerances = mutableMapOf<String, MutableMap<String, WorkoutTolerance>>()
        val blocked = mutableSetOf<Pair<String,String>>()
        for (rule in exercise.rules) {
            fun record(status: TechniqueBindingStatus, feature: String? = null, state: String? = null) {
                audit += TechniqueRuleBinding(rule.id, feature, state, status, rule.summary, rule.sourceIds, rule.limitation)
            }
            if (rule.mode == TechniqueRuleMode.UNOBSERVABLE) { record(TechniqueBindingStatus.UNOBSERVABLE); continue }
            val phaseStates: Set<String>? = if (rule.phase == TechniquePhase.ALL) reference.states.map { it.id }.toSet() else {
                val anchors = reference.states.mapNotNull { state ->
                    state.features.firstOrNull { it.name == rule.anchor }?.let { state.id to it.reference.median }
                }
                val minimum = anchors.minOfOrNull { it.second }; val maximum = anchors.maxOfOrNull { it.second }
                val neededSpan = if (rule.anchor?.startsWith("angle.") == true) 4.0 else 0.04
                if (minimum == null || maximum == null || maximum - minimum < neededSpan) null else anchors.filter {
                    if (rule.phase == TechniquePhase.MINIMUM) it.second <= minimum + (maximum - minimum) * 0.25
                    else it.second >= maximum - (maximum - minimum) * 0.25
                }.map { it.first }.toSet()
            }
            if (phaseStates == null) { record(TechniqueBindingStatus.PHASE_UNRESOLVED); continue }
            for (state in reference.states.filter { it.id in phaseStates }) for (feature in rule.features) {
                val key = state.id to feature
                if (state.features.none { it.name == feature && it.kind == ActionFeatureKind.GEOMETRY }) {
                    record(TechniqueBindingStatus.MISSING_REFERENCE_FEATURE, feature, state.id); continue
                }
                if (key in blocked) continue
                selected.getOrPut(state.id) { mutableSetOf() } += feature
                if (rule.mode == TechniqueRuleMode.SOURCE_TARGET) {
                    val range = WorkoutTargetRange(requireNotNull(rule.lower), requireNotNull(rule.upper), WorkoutTargetOrigin.SOURCE_GUIDANCE, rule.id)
                    val previous = targets[state.id]?.get(feature)
                    if (previous != null && (previous.lower != range.lower || previous.upper != range.upper)) {
                        selected[state.id]?.remove(feature); targets[state.id]?.remove(feature); cues.remove(key); blocked += key
                        for (index in audit.indices) {
                            val item = audit[index]
                            if (item.stateId == state.id && item.feature == feature) audit[index] = item.copy(status = TechniqueBindingStatus.CONFLICTING_TARGETS)
                        }
                        tolerances[state.id]?.remove(feature)
                        record(TechniqueBindingStatus.CONFLICTING_TARGETS, feature, state.id); continue
                    }
                    targets.getOrPut(state.id) { mutableMapOf() }[feature] = range
                    tolerances.getOrPut(state.id) { mutableMapOf() }[feature] = config.featureOverrides[feature] ?: requireNotNull(rule.tolerance)
                }
                // A direct source target takes cue precedence over generic reference range matching.
                if (rule.mode == TechniqueRuleMode.SOURCE_TARGET || key !in cues) cues[key] = rule.cue
                record(if (rule.mode == TechniqueRuleMode.SOURCE_TARGET) TechniqueBindingStatus.APPLIED_SOURCE_TARGET else TechniqueBindingStatus.APPLIED_REFERENCE, feature, state.id)
            }
        }
        bindings = audit.toList()
        val coachingProfile = reference.copy(states = reference.states.map { state ->
            state.copy(features = state.features.filter { it.name in selected[state.id].orEmpty() || it.name.startsWith("ratio.") })
        })
        val stateNames = coachingProfile.states.associate { it.id to it.features.map { f -> f.name }.toSet() }
        val allNames = stateNames.values.flatten().toSet()
        // Caller thresholds can tune margins; published targets cannot silently become learned mistakes.
        val mergedTolerances = tolerances.mapValues { (stateId, values) -> values + config.stateFeatureOverrides[stateId].orEmpty().filterKeys { it in stateNames.getValue(stateId) } } +
            config.stateFeatureOverrides.filterKeys { it !in tolerances }.mapValues { (stateId, values) -> values.filterKeys { it in stateNames[stateId].orEmpty() } }
        coach = WorkoutPrecisionCoach(coachingProfile, config.copy(
            featureOverrides = config.featureOverrides.filterKeys { it in allNames }, stateFeatureOverrides = mergedTolerances,
            stateTargetRanges = targets.mapValues { it.value.toMap() },
        ))
    }

    fun reset() = coach.reset()
    fun evaluate(frame: SpatialObservationFrame, estimate: ActionStateEstimate): ExerciseTechniqueResult {
        val result = coach.evaluate(frame, estimate)
        fun label(suggestion: WorkoutSuggestion): WorkoutSuggestion {
            val template = cues[suggestion.stateId to suggestion.feature] ?: return suggestion
            val observed = suggestion.measurement.observedFeature.orEmpty()
            val side = when { observed.contains("left_") -> "left"; observed.contains("right_") -> "right"; else -> "" }
            return suggestion.copy(label = template.replace("{side}", side).replace("  ", " "))
        }
        return ExerciseTechniqueResult(exercise.id, catalogVersion,
            result.copy(activeSuggestions = result.activeSuggestions.map(::label), triggers = result.triggers.map(::label)), bindings)
    }
}

package ai.senp.motion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

enum class TechniqueRuleMode { REFERENCE_RANGE, SOURCE_TARGET, UNOBSERVABLE }
enum class TechniquePhase { ALL, MINIMUM, MAXIMUM }

data class TechniqueSource(val id: String, val publisher: String, val title: String, val url: String, val retrieved: String)
data class ExerciseTechniqueRule(
    val id: String,
    val mode: TechniqueRuleMode,
    val features: List<String>,
    val phase: TechniquePhase,
    val anchor: String?,
    val lower: Double?,
    val upper: Double?,
    val tolerance: WorkoutTolerance?,
    val summary: String,
    val cue: String,
    val sourceIds: List<String>,
    val limitation: String,
)
data class ExerciseTechnique(
    val id: String, val name: String, val family: String, val aliases: List<String>,
    val rules: List<ExerciseTechniqueRule>, val limitations: List<String>,
)

/** Original compact fact summaries and measurement mappings; no full source articles. */
class ExerciseTechniqueCatalog private constructor(
    val version: String, val sources: List<TechniqueSource>, val exercises: List<ExerciseTechnique>,
) {
    private val byName: Map<String, ExerciseTechnique>
    init {
        require(version == "exercise-techniques/1")
        require(sources.map { it.id }.distinct().size == sources.size)
        require(sources.all { it.url.startsWith("https://") && it.retrieved.isNotBlank() })
        require(exercises.map { it.id }.distinct().size == exercises.size)
        val sourceIds = sources.map { it.id }.toSet()
        val names = exercises.flatMap { exercise ->
            require(exercise.rules.isNotEmpty() && exercise.limitations.isNotEmpty())
            require(exercise.rules.map { it.id }.distinct().size == exercise.rules.size)
            exercise.rules.forEach { rule ->
                require(rule.sourceIds.isNotEmpty() && rule.sourceIds.all { it in sourceIds })
                require(rule.summary.isNotBlank() && rule.cue.isNotBlank())
                require(rule.features.all { it in coachableFeatures }) { "Unknown measurement in ${exercise.id}/${rule.id}" }
                require(rule.anchor == null || rule.anchor in coachableFeatures)
                if (rule.mode == TechniqueRuleMode.SOURCE_TARGET) {
                    require(rule.lower != null && rule.upper != null && rule.lower.isFinite() && rule.upper.isFinite() && rule.lower <= rule.upper)
                    require(rule.features.isNotEmpty() && rule.tolerance != null)
                    require(rule.lower >= 0 && (rule.features.none { it.startsWith("angle.") } || rule.upper <= 180.0))
                }
                if (rule.mode != TechniqueRuleMode.UNOBSERVABLE) require(rule.features.isNotEmpty())
                if (rule.phase != TechniquePhase.ALL) require(rule.anchor != null)
            }
            (exercise.aliases + exercise.id).map { normalize(it) to exercise }
        }
        require(names.groupBy { it.first }.values.all { matches -> matches.map { it.second.id }.distinct().size == 1 }) { "Ambiguous exercise alias" }
        require(names.all { it.first.isNotBlank() })
        byName = names.toMap()
    }

    fun find(idOrAlias: String): ExerciseTechnique? = byName[normalize(idOrAlias)]
    fun requireExercise(idOrAlias: String): ExerciseTechnique = requireNotNull(find(idOrAlias)) { "Unknown exercise '$idOrAlias'; select a documented variant" }

    companion object {
        private fun normalize(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val coachableFeatures: Set<String> = buildSet {
            for (side in listOf("left", "right")) {
                for (joint in listOf("shoulder", "elbow", "hip", "knee", "ankle", "body_line")) add("angle.${side}_$joint")
                for (point in listOf("shoulder", "hip")) add("distance.${side}_hand_to_$point")
                add("distance.${side}_elbow_to_hip")
            }
            for (point in listOf("hand", "foot", "knee", "elbow")) add("distance.${point}_separation")
            addAll(listOf("relation.hand_width_over_shoulders", "relation.foot_width_over_hips", "relation.knee_width_over_feet"))
        }
        val builtIn: ExerciseTechniqueCatalog by lazy {
            val input = requireNotNull(ExerciseTechniqueCatalog::class.java.getResourceAsStream("/ai/senp/motion/exercise-techniques.json"))
            input.bufferedReader().use { parse(it.readText()) }
        }
        fun parse(text: String): ExerciseTechniqueCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
            fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }
            fun JsonObject.number(key: String) = get(key)?.jsonPrimitive?.double
            val sources = root.getValue("sources").jsonArray.map { element ->
                element.jsonObject.run { TechniqueSource(string("id"), string("publisher"), string("title"), string("url"), string("retrieved")) }
            }
            val exercises = root.getValue("exercises").jsonArray.map { element -> element.jsonObject.run {
                ExerciseTechnique(string("id"), string("name"), string("family"), strings("aliases"),
                    getValue("rules").jsonArray.map { item -> item.jsonObject.run {
                        ExerciseTechniqueRule(string("id"), TechniqueRuleMode.valueOf(string("mode")), strings("features"),
                            TechniquePhase.valueOf(string("phase")), get("anchor")?.jsonPrimitive?.content,
                            number("lower"), number("upper"), number("enter")?.let { WorkoutTolerance(it, requireNotNull(number("exit"))) },
                            string("summary"), string("cue"), strings("sources"), string("limitation"))
                    } }, strings("limitations"))
            } }
            return ExerciseTechniqueCatalog(root.string("version"), sources, exercises)
        }
    }
}

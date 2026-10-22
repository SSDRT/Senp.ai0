package ai.senp.motion

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthetic rule-composition tests with oracle phases; never exercise/video accuracy. */
object ExerciseTechniqueBenchmark {
    private fun profile(): ActionProfile {
        val original = WorkoutPrecisionTestData.profile()
        val additional = (ExerciseTechniqueCatalog.coachableFeatures - original.states.first().features.map { it.name }.toSet()).map(WorkoutPrecisionTestData::feature)
        return original.copy(states = original.states.map { state -> state.copy(features = (state.features + additional).map { feature ->
            val median = when (feature.name) {
                "angle.left_elbow", "angle.right_elbow" -> 60.0 + state.index * 50.0
                "angle.left_shoulder", "angle.right_shoulder" -> 20.0 + state.index * 70.0
                "angle.left_hip", "angle.right_hip" -> 70.0 + state.index * 50.0
                else -> feature.reference.median
            }
            feature.copy(reference = feature.reference.copy(median = median,lower = median-2.0,upper = median+2.0))
        }) })
    }
    @JvmStatic fun main(args: Array<String>) {
        val profile = profile()
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (exercise in ExerciseTechniqueCatalog.builtIn.exercises) for (state in profile.states.indices) for (fps in listOf(10,15,30,60)) {
            val coach = ExerciseTechniqueCoach(profile,exercise.id)
            fun frame(ms: Long, changes: Map<String,Double>) = WorkoutPrecisionTestData.frame(ms,profile,changes)
            fun estimate(ms: Long) = WorkoutPrecisionTestData.estimate(ms,state)
            val decisions = coach.evaluate(frame(0,emptyMap()),estimate(0)).precision.decisions.filter { it.status == WorkoutFeatureStatus.MONITORED }
            check(decisions.isNotEmpty()) { "No measured rules for ${exercise.id}/$state" }
            val ideal = decisions.associate { it.feature to (requireNotNull(it.referenceLower)+requireNotNull(it.referenceUpper))/2.0 }
            val chosen = decisions.firstOrNull { it.targetOrigin == WorkoutTargetOrigin.SOURCE_GUIDANCE } ?: decisions.first()
            val enter = requireNotNull(chosen.enterThreshold)
            val low = requireNotNull(chosen.referenceLower); val high = requireNotNull(chosen.referenceUpper)
            val fault = if (chosen.unit != WorkoutMeasurementUnit.DEGREES || high+enter+8 < 180.0) high+enter+8 else low-enter-8
            check(fault >= 0)
            coach.reset()
            val step = (1000.0/fps).toLong()
            var falseEvents = 0
            var trueEvents = 0
            var firstTrigger: Long? = null
            var lastMs = 0L
            for (ms in 0L..1000L step step) falseEvents += coach.evaluate(frame(ms,ideal),estimate(ms)).precision.triggers.size
            for (ms in 1200L..2200L step step) {
                lastMs = ms
                val result = coach.evaluate(frame(ms,ideal + (chosen.feature to fault)),estimate(ms)).precision
                for (trigger in result.triggers) {
                    if (trigger.feature == chosen.feature) { trueEvents++; if (firstTrigger == null) firstTrigger = ms } else falseEvents++
                }
            }
            val weakMs = lastMs + step
            val weak = frame(weakMs,ideal + (chosen.feature to fault)).let { observed ->
                observed.copy(intrinsicDescriptor = observed.intrinsicDescriptor.copy(featureConfidences = observed.intrinsicDescriptor.featureConfidences + (chosen.feature to 0.1)))
            }
            val weakActive = coach.evaluate(weak,estimate(weakMs)).precision.activeSuggestions.count { it.feature == chosen.feature }
            check(falseEvents == 0 && trueEvents == 1 && weakActive == 0) { "${exercise.id}/$state/$fps: false=$falseEvents true=$trueEvents weak=$weakActive" }
            rows += buildJsonObject {
                put("exercise",exercise.id);put("state",state);put("fps",fps);put("feature",chosen.feature)
                put("target_origin",chosen.targetOrigin.name);put("false_events",falseEvents);put("true_events",trueEvents)
                put("trigger_delay_ms",requireNotNull(firstTrigger)-1200);put("weak_active",weakActive)
            }
        }
        val output = buildJsonObject {
            put("kind","synthetic-exercise-policy-composition-with-oracle-phases-not-human-accuracy")
            put("exercise_profiles",ExerciseTechniqueCatalog.builtIn.exercises.size);put("scenario_count",rows.size)
            put("false_events",rows.sumOf { it.getValue("false_events").toString().toInt() })
            put("true_events",rows.sumOf { it.getValue("true_events").toString().toInt() })
            put("rows",JsonArray(rows))
        }
        val destination = File(args.single());destination.parentFile.mkdirs()
        destination.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),output)+"\n")
        println("${rows.size} exercise-policy mechanism scenarios passed; report: $destination")
    }
}

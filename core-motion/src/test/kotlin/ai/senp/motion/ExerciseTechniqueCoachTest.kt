package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExerciseTechniqueCoachTest {
    private val data = WorkoutPrecisionTestData
    private fun profile(): ActionProfile {
        val base = data.profile()
        val extras = (ExerciseTechniqueCatalog.coachableFeatures - base.states.first().features.map { it.name }.toSet()).map(data::feature)
        return base.copy(states = base.states.map { state ->
            state.copy(features = (state.features + extras).map { f ->
                val median = when {
                    f.name.endsWith("elbow") -> listOf(50.0,100.0,150.0)[state.index]
                    f.name.endsWith("shoulder") -> listOf(20.0,90.0,160.0)[state.index]
                    f.name.endsWith("hip") && f.name.startsWith("angle.") -> listOf(70.0,120.0,170.0)[state.index]
                    else -> f.reference.median
                }
                f.copy(reference = f.reference.copy(median = median, lower = median - 2.0, upper = median + 2.0))
            })
        })
    }
    private fun feed(coach: ExerciseTechniqueCoach, ms: Long, state: Int = 0, changes: Map<String,Double> = emptyMap(), mirror: ActionMirrorMode = ActionMirrorMode.DIRECT) =
        coach.evaluate(data.frame(ms, profile(), changes), data.estimate(ms, state, mirror))

    @Test fun allDocumentedVariantsHaveTraceableRulesAndCompleteBindingAudits() {
        val catalog = ExerciseTechniqueCatalog.builtIn
        assertEquals(54, catalog.exercises.size)
        assertEquals(41, catalog.sources.size)
        assertEquals(25, ExerciseTechniqueCatalog.coachableFeatures.size)
        for (exercise in catalog.exercises) {
            val coach = ExerciseTechniqueCoach(profile(), exercise.id)
            assertTrue(coach.bindings.any { it.status == TechniqueBindingStatus.UNOBSERVABLE }, exercise.id)
            assertTrue(coach.bindings.any { it.status == TechniqueBindingStatus.APPLIED_REFERENCE || it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET }, exercise.id)
            assertTrue(coach.bindings.none { it.status == TechniqueBindingStatus.MISSING_REFERENCE_FEATURE || it.status == TechniqueBindingStatus.PHASE_UNRESOLVED }, exercise.id)
            assertTrue(coach.bindings.all { binding -> binding.sourceIds.all { id -> catalog.sources.any { it.id == id } } })
        }
    }

    @Test fun aliasesAreExplicitAndUnknownExercisesFailInsteadOfGuessing() {
        assertEquals("push_up", ExerciseTechniqueCatalog.builtIn.requireExercise("Push ups").id)
        assertEquals("bodyweight_squat", ExerciseTechniqueCatalog.builtIn.requireExercise("AIR SQUAT").id)
        assertNull(ExerciseTechniqueCatalog.builtIn.find("any random workout"))
        assertFailsWith<IllegalArgumentException> { ExerciseTechniqueCoach(profile(), "unknown") }
        assertFailsWith<IllegalArgumentException> { ExerciseTechniqueCoach(profile(), "plank", WorkoutPrecisionConfig(featureOverrides = mapOf("angle.typo" to WorkoutTolerance(15.0,9.0)))) }
    }

    @Test fun sourceTargetsDoNotLearnAnIncorrectDemonstrationAsTheRequiredBodyLine() {
        val original = profile()
        val coach = ExerciseTechniqueCoach(original, "straight_arm_plank")
        var result = feed(coach,0)
        for (time in listOf(80L,160L,240L)) result = feed(coach,time)
        val decision = result.precision.decisions.first { it.feature == "angle.left_body_line" }
        assertEquals(180.0, decision.referenceLower)
        assertEquals(WorkoutTargetOrigin.SOURCE_GUIDANCE, decision.targetOrigin)
        assertEquals("body_alignment", decision.targetRuleId)
        assertTrue(result.precision.triggers.any { it.feature == "angle.left_body_line" })
        assertEquals(90.0, original.states.first().features.first { it.name == "angle.left_body_line" }.reference.median)
    }

    @Test fun modifiedAndPikePushupsDoNotReceiveStandardAnkleLineConstraints() {
        val modified = ExerciseTechniqueCoach(profile(), "modified_push_up")
        assertTrue(modified.bindings.any { it.feature == "angle.left_hip" && it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
        assertTrue(modified.bindings.none { it.feature == "angle.left_body_line" && it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
        val pike = ExerciseTechniqueCoach(profile(), "pike_push_up")
        assertTrue(pike.bindings.none { it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
        val shallow = ExerciseTechniqueCoach(profile(), "shallow_wall_sit")
        assertTrue(shallow.bindings.none { it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
        assertTrue(ExerciseTechniqueCoach(profile(), "bent_knee_leg_raise").bindings.none { it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
    }

    @Test fun sourceTargetsApplyOnlyToTheRecognizedReferencePhase() {
        val coach = ExerciseTechniqueCoach(profile(), "glute_bridge")
        val low = feed(coach,0).precision.decisions.first { it.feature == "angle.left_hip" }
        assertEquals(WorkoutTargetOrigin.REFERENCE_LEARNED, low.targetOrigin)
        val high = feed(coach,80,2).precision.decisions.first { it.feature == "angle.left_hip" }
        assertEquals(WorkoutTargetOrigin.SOURCE_GUIDANCE, high.targetOrigin)
        assertEquals(180.0, high.referenceUpper)
    }

    @Test fun ambiguousPhasesAndAbsentFeaturesAreReportedWithoutInventingTargets() {
        val constant = data.profile()
        val coach = ExerciseTechniqueCoach(constant,"push_up")
        assertTrue(coach.bindings.any { it.status == TechniqueBindingStatus.PHASE_UNRESOLVED })
        val noLine = constant.copy(states = constant.states.map { state -> state.copy(features = state.features.filterNot { it.name.endsWith("body_line") }) })
        assertTrue(ExerciseTechniqueCoach(noLine,"plank").bindings.any { it.status == TechniqueBindingStatus.MISSING_REFERENCE_FEATURE })
    }

    @Test fun missingLocalConfidenceAndLostTrackingSuppressSourceBasedCorrections() {
        val coach = ExerciseTechniqueCoach(profile(),"plank")
        for (time in listOf(0L,80L,160L,240L)) feed(coach,time)
        val weak = data.frame(320, profile()).let { it.copy(intrinsicDescriptor = it.intrinsicDescriptor.copy(featureConfidences = emptyMap())) }
        val missing = coach.evaluate(weak,data.estimate(320)).precision
        assertTrue(missing.activeSuggestions.isEmpty())
        val lost = coach.evaluate(data.frame(400,profile()), data.estimate(400).copy(status = ActionTrackingStatus.LOST)).precision
        assertEquals(WorkoutAbstention.ACTION_NOT_CONFIRMED,lost.abstention)
    }

    @Test fun mirroredCuesNameTheObservedSideAndMarginsCanBeTuned() {
        val config = WorkoutPrecisionConfig(featureOverrides = mapOf("angle.left_body_line" to WorkoutTolerance(30.0,20.0)))
        val coach = ExerciseTechniqueCoach(profile(),"plank",config)
        var result = feed(coach,0,mirror = ActionMirrorMode.MIRRORED)
        for (time in listOf(80L,160L,240L)) result = feed(coach,time,mirror = ActionMirrorMode.MIRRORED)
        val cue = result.precision.triggers.first { it.feature == "angle.left_body_line" }
        assertTrue(cue.label.contains("right"))
        assertEquals(30.0,cue.measurement.enterThreshold)
        coach.reset()
        assertTrue(feed(coach,0).precision.triggers.isEmpty())
    }

    @Test fun malformedCatalogueMeasurementsAndDuplicateExerciseIdsAreRejected() {
        val text = requireNotNull(javaClass.getResourceAsStream("/ai/senp/motion/exercise-techniques.json")).bufferedReader().use { it.readText() }
        assertFailsWith<IllegalArgumentException> { ExerciseTechniqueCatalog.parse(text.replace("angle.left_body_line","angle.not_a_measurement")) }
        assertFailsWith<IllegalArgumentException> { ExerciseTechniqueCatalog.parse(text.replace("\"id\": \"modified_push_up\"","\"id\": \"push_up\"")) }
        assertNotNull(ExerciseTechniqueCatalog.builtIn.find("bench press"))
    }

    @Test fun contradictorySourceTargetsAreAuditedAndNeverTriggerCorrections() {
        val text = requireNotNull(javaClass.getResourceAsStream("/ai/senp/motion/exercise-techniques.json")).bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text).jsonObject
        val exercises = root.getValue("exercises").jsonArray.map { element ->
            val exercise = element.jsonObject
            if (exercise.getValue("id").jsonPrimitive.content != "straight_arm_plank") element else {
                val rules = exercise.getValue("rules").jsonArray
                val original = rules.first { it.jsonObject.getValue("id").jsonPrimitive.content == "body_alignment" }.jsonObject
                val conflict = JsonObject(original + mapOf("id" to JsonPrimitive("conflicting_alignment"),"lower" to JsonPrimitive(120.0),"upper" to JsonPrimitive(120.0)))
                JsonObject(exercise + ("rules" to JsonArray(rules + conflict)))
            }
        }
        val catalog = ExerciseTechniqueCatalog.parse(JsonObject(root + ("exercises" to JsonArray(exercises))).toString())
        val coach = ExerciseTechniqueCoach(profile(),"straight_arm_plank",catalog = catalog)
        assertTrue(coach.bindings.any { it.status == TechniqueBindingStatus.CONFLICTING_TARGETS })
        assertTrue(coach.bindings.none { it.status == TechniqueBindingStatus.APPLIED_SOURCE_TARGET })
        for (time in 0L..1000L step 80) assertTrue(feed(coach,time).precision.triggers.isEmpty())
    }
}

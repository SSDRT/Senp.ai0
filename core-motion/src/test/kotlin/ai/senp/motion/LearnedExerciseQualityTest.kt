package ai.senp.motion

import ai.senp.core.contracts.TimestampMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

class LearnedExerciseQualityTest {
    private val model by lazy { LearnedExerciseQualityModel.builtIn }
    private val fixtures by lazy {
        requireNotNull(javaClass.getResourceAsStream("/ai/senp/motion/learned-quality-parity.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
    }
    private fun frame(time: Long, values: List<Double?>, confidence: Double = 0.99, subject: String = "person", segment: Int = 0): SpatialObservationFrame {
        val entries = model.featureNames.zip(values).mapNotNull { (key,value) -> value?.let { key to it } }.toMap()
        return SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
            SpatialIntrinsicDescriptor(entries,confidence,entries.mapValues { confidence }),0.99,subject,segment)
    }
    private fun nullableValues(array: kotlinx.serialization.json.JsonArray): List<Double?> =
        array.map { if (it is JsonNull) null else it.jsonPrimitive.double }

    @Test fun deployedWindowScoresMatchPythonForTwelveHeldOutVideoWindows() {
        assertEquals(12,fixtures.size)
        assertEquals(6,model.exerciseIds.size)
        assertFalse(model.releaseApproved)
        for (fixture in fixtures) {
            val row = fixture.jsonObject
            val engine = model.newEngine(row.getValue("exercise_id").jsonPrimitive.content)
            var result: LearnedQualityResult? = null
            for (item in row.getValue("frames").jsonArray) {
                val f = item.jsonObject
                result = engine.evaluate(frame(f.getValue("timestamp_ms").jsonPrimitive.long,
                    nullableValues(f.getValue("features").jsonArray)),model.poseModelSha256)
            }
            val actual = assertNotNull(result)
            val outliers = row.getValue("outlier_fraction").jsonPrimitive.double
            assertEquals(outliers,assertNotNull(actual.outlierFraction),1e-12)
            if (outliers > 0.10) {
                assertEquals(LearnedQualityStatus.OUT_OF_DISTRIBUTION,actual.status)
                assertFalse(actual.needsReview)
            } else {
                assertEquals(LearnedQualityStatus.SCORED,actual.status)
                assertEquals(row.getValue("score").jsonPrimitive.double,assertNotNull(actual.uncalibratedBadFormScore),1e-9)
                assertFalse(actual.needsReview,"Research-only models cannot request production review")
            }
        }
    }

    @Test fun missingEvidenceModelMismatchAndUnsupportedExercisesAbstain() {
        val f = frame(0,List(25) { 90.0 })
        assertEquals(LearnedQualityStatus.UNSUPPORTED_EXERCISE,model.newEngine("plank").evaluate(f,model.poseModelSha256).status)
        val engine = model.newEngine("bodyweight_squat")
        assertEquals(LearnedQualityStatus.MODEL_MISMATCH,engine.evaluate(f,null).status)
        assertEquals(LearnedQualityStatus.MODEL_MISMATCH,engine.evaluate(f.copy(timestamp = TimestampMs(1)),"wrong").status)
        assertEquals(LearnedQualityStatus.WEAK_POSE,engine.evaluate(f.copy(timestamp = TimestampMs(2),evidenceKind = SpatialEvidenceKind.IMAGE_2D),model.poseModelSha256).status)
        assertEquals(LearnedQualityStatus.WEAK_POSE,engine.evaluate(frame(3,List(25) { 90.0 },confidence = 0.64),model.poseModelSha256).status)
        assertFailsWith<IllegalArgumentException> { engine.evaluate(f.copy(timestamp = TimestampMs(3)),model.poseModelSha256) }
        engine.reset()
        assertEquals(LearnedQualityStatus.COLLECTING,engine.evaluate(f,model.poseModelSha256).status)
    }

    @Test fun gapsSubjectChangesAndSegmentChangesClearHistoryAndThrottlingIsBounded() {
        val engine = model.newEngine("bodyweight_squat")
        val values = nullableValues(fixtures.first { it.jsonObject.getValue("exercise_id").jsonPrimitive.content == "bodyweight_squat" }
            .jsonObject.getValue("frames").jsonArray.first().jsonObject.getValue("features").jsonArray)
        var result: LearnedQualityResult? = null
        for (time in 0L..100_000L step 20) {
            result = engine.evaluate(frame(time,values),model.poseModelSha256)
            assertTrue(result.sampleCount <= 6)
            if (time >= 2000) assertTrue(result.status != LearnedQualityStatus.COLLECTING)
        }
        assertNotNull(result)
        assertEquals(1,engine.evaluate(frame(101_000,values),model.poseModelSha256).sampleCount)
        assertEquals(1,engine.evaluate(frame(101_500,values,subject = "another"),model.poseModelSha256).sampleCount)
        assertEquals(1,engine.evaluate(frame(102_000,values,subject = "another",segment = 1),model.poseModelSha256).sampleCount)
        assertEquals(LearnedQualityStatus.WEAK_POSE,engine.evaluate(frame(102_100,List(25) { null },subject = "another",segment = 1),model.poseModelSha256).status)
        assertEquals(1,engine.evaluate(frame(102_200,values,subject = "another",segment = 1),model.poseModelSha256).sampleCount)
    }

    @Test fun extremeFeatureInputsAreRejectedAndIncompleteCoverageNeverProducesReviewFlags() {
        val engine = model.newEngine("bodyweight_squat")
        var result: LearnedQualityResult? = null
        for (time in 0L..2500L step 500) result = engine.evaluate(frame(time,List(25) { 1e9 }),model.poseModelSha256)
        assertEquals(LearnedQualityStatus.OUT_OF_DISTRIBUTION,result?.status)
        assertFalse(assertNotNull(result).needsReview)
        engine.reset()
        for (time in 0L..2500L step 500) result = engine.evaluate(frame(time,List(25) { if (it < 5) 90.0 else null }),model.poseModelSha256)
        assertEquals(LearnedQualityStatus.WEAK_POSE,result?.status)
        assertFalse(assertNotNull(result).needsReview)
    }
}

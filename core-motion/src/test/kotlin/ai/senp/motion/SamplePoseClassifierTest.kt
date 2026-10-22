package ai.senp.motion

import ai.senp.core.contracts.FrameValidity
import ai.senp.core.contracts.ImageLandmark
import ai.senp.core.contracts.PoseFrame
import ai.senp.core.contracts.PoseLandmark
import ai.senp.core.contracts.PoseLandmarkId
import ai.senp.core.contracts.TimestampMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

class SamplePoseClassifierTest {
    private val fixtures by lazy {
        requireNotNull(javaClass.getResourceAsStream("/ai/senp/motion/sample-classifier-parity.json")).bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
    }
    @Test fun realHoldoutLandmarksReproduceTrainingFeaturesAndDeployedDecisions() {
        for (item in fixtures) {
            val row = item.jsonObject
            val points = row.getValue("points").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
            val calculated = assertNotNull(SamplePoseClassifier.features(points))
            val expected = row.getValue("features").jsonArray.map { it.jsonPrimitive.double }
            calculated.zip(expected).forEach { (a,b) -> assertEquals(a,b,1e-9) }
            val output = SamplePoseClassifier.builtIn.classifyFeatures(calculated)
            val prediction = row.getValue("prediction").jsonObject
            assertEquals(prediction.getValue("nearest_label").jsonPrimitive.content,output.nearestLabel)
            assertEquals(prediction.getValue("normalized_distance").jsonPrimitive.double,assertNotNull(output.normalizedDistance),1e-7)
            val expectedLabel = prediction.getValue("label").jsonPrimitive.content.takeUnless { it == "null" }
            assertEquals(expectedLabel != null, output.status == SamplePoseStatus.MATCHED)
        }
    }
    @Test fun degenerateAndNonfiniteInputsCannotBeClassified() {
        assertNull(SamplePoseClassifier.features(List(33) { listOf(0.0,0.0,0.0) }))
        assertNull(SamplePoseClassifier.features(List(32) { listOf(0.0,0.0,0.0) }))
        assertEquals(SamplePoseStatus.INVALID_INPUT,SamplePoseClassifier.builtIn.classifyFeatures(List(20) { Double.NaN }).status)
        assertEquals(SamplePoseStatus.INVALID_INPUT,SamplePoseClassifier.builtIn.classifyFeatures(emptyList()).status)
    }
    @Test fun imageGeometryEmbeddingIsInvariantToUniformScaleTranslationAndReflection() {
        val points = fixtures.first().jsonObject.getValue("points").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
        val baseline = assertNotNull(SamplePoseClassifier.features(points))
        val transformed = points.map { listOf(-it[0] * 2.0 + 100,it[1] * 2.0 - 200,it[2] * 2.0 + 50) }
        baseline.zip(assertNotNull(SamplePoseClassifier.features(transformed))).forEach { (a,b) -> assertEquals(a,b,1e-9) }
    }
    @Test fun extremeUnsupportedFeatureVectorsAbstain() {
        val result = SamplePoseClassifier.builtIn.classifyFeatures(List(20) { 1e6 })
        assertTrue(result.status != SamplePoseStatus.MATCHED)
        assertNull(result.exerciseId)
    }
    @Test fun liveAspectCorrectionMatchesPixelTrainingAndMissingConfidenceAbstains() {
        val points = fixtures.first().jsonObject.getValue("points").jsonArray.map { p -> p.jsonArray.map { it.jsonPrimitive.double } }
        val expected = SamplePoseClassifier.builtIn.classifyFeatures(assertNotNull(SamplePoseClassifier.features(points)))
        for ((width,height) in listOf(3000 to 1000,1000 to 3000)) {
            val pose = PoseFrame(TimestampMs(0),0,PoseLandmarkId.entries.map { id ->
                val p = points[id.index]
                PoseLandmark(id,ImageLandmark(p[0]/width,p[1]/height,p[2]/width),visibility = 0.99,presence = 0.99)
            },FrameValidity.Valid)
            val actual = SamplePoseClassifier.builtIn.classify(pose,width,height)
            assertEquals(expected.status,actual.status)
            assertEquals(expected.nearestLabel,actual.nearestLabel)
            assertEquals(assertNotNull(expected.normalizedDistance),assertNotNull(actual.normalizedDistance),1e-8)
            val missing = pose.copy(landmarks = pose.landmarks.map { if (it.id == PoseLandmarkId.LEFT_ELBOW) it.copy(presence = null) else it })
            assertEquals(SamplePoseStatus.WEAK_POSE,SamplePoseClassifier.builtIn.classify(missing,width,height).status)
            assertEquals(SamplePoseStatus.INVALID_INPUT,SamplePoseClassifier.builtIn.classify(pose,0,height).status)
        }
    }
}

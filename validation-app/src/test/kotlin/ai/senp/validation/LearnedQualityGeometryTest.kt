package ai.senp.validation

import ai.senp.core.contracts.*
import ai.senp.motion.*
import ai.senp.sync.v2.PoseObservationAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.*

class LearnedQualityGeometryTest {
    @Test fun trainingWorldGeometryAndConfidenceMasksMatchTheLiveAndroidSeam() {
        val cases = requireNotNull(javaClass.getResourceAsStream("/learned-quality-geometry-parity.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(12,cases.size)
        val adapter = PoseObservationAdapter()
        val spatial = SpatialSynchronizationEngine(SpatialSynchronizationConfig(includeWorkoutFeatures = true))
        for (item in cases) {
            val row = item.jsonObject
            val points = row.getValue("landmarks").jsonArray.map { it.jsonArray.map { n -> n.jsonPrimitive.double } }
            val timestamp = TimestampMs(row.getValue("timestamp_ms").jsonPrimitive.long)
            val pose = PoseFrame(timestamp,0,PoseLandmarkId.entries.map { id ->
                val p = points[id.index]
                PoseLandmark(id,ImageLandmark(p[0],p[1],p[2]),WorldLandmark(p[5],p[6],p[7]),p[3],p[4])
            },FrameValidity.Valid)
            val sequence = CanonicalObservationSequence(VideoRole.SOURCE,DurationMs(timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 2.0),listOf(adapter.adaptFrame(pose)))
            val result = spatial.analyzeSequence(sequence).frames.single()
            assertEquals(SpatialEvidenceKind.THREE_D,result.evidenceKind,row.getValue("video").jsonPrimitive.content)
            val expected = row.getValue("features").jsonArray
            row.getValue("feature_names").jsonArray.forEachIndexed { index,key ->
                val name = key.jsonPrimitive.content
                val actual = result.intrinsicDescriptor.values[name]?.takeIf { (result.intrinsicDescriptor.featureConfidences[name] ?: 0.0) >= 0.65 }
                if (expected[index] is JsonNull) assertNull(actual,name) else assertEquals(expected[index].jsonPrimitive.double,assertNotNull(actual,name),1e-7,name)
            }
        }
    }

    @Test fun qualityRequiresExplicitSelectionModelIdentityAndTimestampedRawEvidence() {
        val profile = sampleProfile()
        assertFailsWith<IllegalArgumentException> { LiveReferenceActionProcessor(profile,15.0,enableLearnedQuality = true) }
        val processor = LiveReferenceActionProcessor(profile,15.0,exerciseId = "bodyweight_squat",enableLearnedQuality = true)
        val pose = PoseFrame(TimestampMs(0),0,PoseLandmarkId.entries.map { PoseLandmark(it,ImageLandmark(0.5,0.5,0.0),visibility = 1.0,presence = 1.0) },FrameValidity.Valid)
        assertEquals(LearnedQualityStatus.MODEL_MISMATCH,processor.update(pose).learnedQuality?.status)
        assertEquals(LearnedQualityStatus.WEAK_POSE,processor.update(pose.copy(timestamp = TimestampMs(67)),poseModelSha256 = LearnedExerciseQualityModel.builtIn.poseModelSha256).learnedQuality?.status)
        assertFailsWith<IllegalArgumentException> { processor.update(pose.copy(timestamp = TimestampMs(134)),qualityRawPose = pose) }
    }

    private fun sampleProfile(): ActionProfile {
        val d = RobustDistribution(90.0,80.0,100.0,4.0,20,20,0.0)
        val f = ActionFeatureProfile("angle.left_knee",ActionFeatureKind.GEOMETRY,d,10.0,0.9,0.8,0.95,0.8,0.85,0.9)
        return ActionProfile(ReferenceActionVersions.PROFILE,false,0.0,1,
            listOf(ActionStateProfile("state_00",0,0.0,1.0,d,listOf(f),0.9)),emptyList(),null,
            mapOf(f.name to 10.0),0.9,ActionProfileValidation(0.9,0.9,0.9,0.9,0.0),staticHold = true)
    }
}

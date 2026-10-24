package ai.senp.validation

import ai.senp.core.contracts.TimestampMs
import ai.senp.motion.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Constructed angular controls execute the packaged engine; these are not camera accuracy labels. */
@RunWith(AndroidJUnit4::class)
class QualifiedRepEvidenceInstrumentedTest {
    private val identity = RepEvidenceFingerprint("a".repeat(64),"b".repeat(64))
    private fun reference(asymmetric: Boolean): QualifiedRepReference {
        val duration = RobustDistribution(2000.0,1500.0,7000.0,20.0,30,30,0.0)
        val phases = listOf(0.0,.4,.8,1.0,.8,.4)
        val states = phases.mapIndexed { index,p ->
            val values = mapOf("angle.left_elbow" to 170-100*p,
                "angle.left_shoulder" to if (asymmetric && index in 4..5) 65.0 else 15.0)
            ActionStateProfile("phase_$index",index,index/6.0,(index+1)/6.0,duration,values.map { (name,v) ->
                ActionFeatureProfile(name,ActionFeatureKind.GEOMETRY,RobustDistribution(v,v-2,v+2,.5,30,30,0.0),
                    5.0,.95,.8,.95,.8,.8,.95)
            },.95)
        }
        val profile = ActionProfile(ReferenceActionVersions.PROFILE,true,.95,3,states,
            states.indices.map { ActionTransitionProfile(it,(it+1)%6,it==5,.95) },duration,
            states.first().features.associate { it.name to it.scale },.95,
            ActionProfileValidation(1.0,1.0,.95,1.0,0.0),false,setOf(SpatialEvidenceKind.THREE_D))
        return (QualifiedRepCompiler.compile(profile,"biceps_curl",identity) as QualifiedRepCompilation.Ready).reference
    }
    private fun frame(time: Long,p: Double,quality: Double): SpatialObservationFrame {
        val values = mapOf("angle.left_elbow" to 170-100*p,"angle.left_shoulder" to quality)
        return SpatialObservationFrame(TimestampMs(time),SpatialEvidenceKind.THREE_D,null,null,null,
            SpatialIntrinsicDescriptor(values,.95,values.mapValues { .95 }),.95,"constructed-subject",0)
    }
    private fun save(name: String,qualified: Int,cases: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(directory,name).writeText(JSONObject().put("kind","constructed-native-evidence-controls-not-human-accuracy")
            .put("reference_schema","qualified-reference-reps/5").put("cases",cases).put("qualified",qualified).toString(2))
    }
    @Test fun incompatibleIntermediateEvidenceIsWithheldWithoutAnInventedSevereFault() {
        var accepted = 0
        for (fps in listOf(10,15,30,60)) for (bad in listOf(false,true)) {
            val engine = reference(false).newEngine()
            val points = List(fps/3+2) { 0.0 }+listOf(.5)+List(fps*5) { 1.0 }+listOf(.5)+List(fps) { 0.0 }
            val faults = mutableListOf<RepFault>()
            points.forEachIndexed { i,p -> faults += engine.evaluate(frame((i*1000.0/fps).toLong(),p,
                if (bad && p == .5) 65.0 else 15.0),identity.poseModelSha256).newFaults }
            val output = engine.finish((points.size*1000.0/fps).toLong())
            assertEquals(if (bad) 0 else 1,output.counts.qualified)
            assertEquals(0,output.counts.rejected)
            assertTrue(faults.isEmpty())
            accepted += output.counts.qualified
        }
        save("compatible-path-controls.json",accepted,8)
    }
    @Test fun asymmetricReturnCurveIsUsedWithoutConfirmingAReversalEarly() {
        var accepted = 0
        for (fps in listOf(10,15,30,60)) for (wrong in listOf(false,true)) {
            val engine = reference(true).newEngine()
            var last: QualifiedRepOutput? = null
            for (i in 0..fps*2) {
                val time = (i*1000.0/fps).toLong()
                val p = when { time < 300 -> 0.0;time < 700 -> (time-300)/400.0;time < 1000 -> 1.0;
                    time < 1500 -> (1500-time)/500.0;else -> 0.0 }
                val quality = if (time < 1000 || wrong) 15.0 else when {
                    p < .4 -> 15+125*p;p <= .8 -> 65.0;else -> 65-250*(p-.8)
                }
                last = engine.evaluate(frame(time,p,quality),identity.poseModelSha256)
                if (time <= 1000) assertEquals(0,last.counts.qualified)
            }
            assertEquals(if (wrong) 0 else 1,requireNotNull(last).counts.qualified)
            accepted += last.counts.qualified
        }
        save("return-branch-controls.json",accepted,8)
    }

    private fun calibrationSequence(mixed: Boolean, count: Int = 4): SpatialSequenceAnalysis {
            val observations = (0 until count).flatMap { rep -> (0..60).map { index ->
                val t = index*33L
                val p = when { t < 300 -> 0.0;t < 900 -> (t-300)/600.0;t < 1100 -> 1.0;
                    t < 1700 -> (1700-t)/600.0;else -> 0.0 }
                frame(rep*2100L+t,p,15.0).copy(selectedSubjectId = if (mixed) "person-$rep" else "same-person",spatialSegmentId = rep)
            } }
            return SpatialSequenceAnalysis(ai.senp.core.contracts.VideoRole.REFERENCE,
                ai.senp.core.contracts.DurationMs(observations.last().timestamp.value+1),
                ai.senp.core.contracts.ObservationSampling(analysisFramesPerSecond = 30.0),observations,1.0)
    }
    @Test fun calibrationCannotPoolCyclesFromDifferentTrackedPeople() {
        val rejected = QualifiedRepCompiler.compile(calibrationSequence(true),"biceps_curl",identity)
        assertTrue(rejected is QualifiedRepCompilation.Unavailable)
        assertEquals(RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT,(rejected as QualifiedRepCompilation.Unavailable).reason)
        val valid = QualifiedRepCompiler.compile(calibrationSequence(false),"biceps_curl",identity)
        assertTrue(valid is QualifiedRepCompilation.Ready)
        assertEquals(4,(valid as QualifiedRepCompilation.Ready).reference.calibration?.acceptedSelfCycles)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(directory,"reference-subject-guard.json").writeText(JSONObject().put("kind","constructed-reference-identity-control")
            .put("compiler_policy",QualifiedRepCompiler.VERSION).put("mixed_subject_reference_blocked",true)
            .put("single_subject_self_accepted",4).toString(2))
    }

    @Test fun customMeasurementPipelineSelfCheckPreservesRuntimeMismatchRejection() {
        val fingerprint = identity.copy(preprocessingId = "observed-native-test-geometry/1")
        val sequence = calibrationSequence(false)
        val result = QualifiedRepCompiler.compile(sequence,"biceps_curl",fingerprint)
        assertTrue(result is QualifiedRepCompilation.Ready)
        val reference = (result as QualifiedRepCompilation.Ready).reference
        assertEquals(4,reference.calibration?.acceptedSelfCycles)
        val engine = reference.newEngine()
        sequence.frames.forEach { engine.evaluate(it,fingerprint.poseModelSha256,fingerprint.preprocessingId) }
        assertEquals(4,engine.finish(sequence.duration.value).counts.qualified)
        for (wrong in listOf(RepEvidenceFingerprint.RAW_WORLD_PIPELINE,"other/1")) {
            assertEquals(RepReason.PREPROCESSING_MISMATCH,reference.newEngine().evaluate(sequence.frames.first(),
                fingerprint.poseModelSha256,wrong).unavailableReason)
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(directory,"measurement-pipeline-guard.json").writeText(JSONObject().put("schema","qualified-measurement-pipeline-native/1")
            .put("compiler_policy",QualifiedRepCompiler.VERSION).put("custom_pipeline_self_accepted",4)
            .put("raw_and_other_pipeline_rejected",true).toString(2))
    }

    @Test fun referenceCycleSelectionCannotBorrowContinuityOrArmingAcrossMissingEvidence() {
        val cases = listOf("segment_during_attempt","segment_during_ready_dwell","blind_departure")
        for (case in cases) {
            val raw = calibrationSequence(false,5)
            val observations = raw.frames.map { frame ->
                val t = frame.timestamp.value
                when (case) {
                    "segment_during_attempt" -> frame.copy(spatialSegmentId = if (t<1000) 0 else 1)
                    "segment_during_ready_dwell" -> frame.copy(spatialSegmentId = if (t<264) 0 else 1)
                    else -> if (t==396L) frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(
                        featureConfidences = frame.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to 0.0))) else frame
                }
            }
            val result = QualifiedRepCompiler.compile(raw.copy(frames = observations),"biceps_curl",identity)
            assertTrue(case,result is QualifiedRepCompilation.Ready)
            val calibration = (result as QualifiedRepCompilation.Ready).reference.calibration
            assertEquals(case,4,calibration?.observedCycles)
            assertEquals(case,4,calibration?.acceptedSelfCycles)
            assertEquals(case,1.0,requireNotNull(calibration).selfAcceptanceFraction,0.0)
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"qualified-rep-evidence").apply { mkdirs() }
        File(directory,"reference-continuity-guard.json").writeText(JSONObject().put("schema","qualified-reference-continuity-native/1")
            .put("compiler_policy",QualifiedRepCompiler.VERSION).put("constructed_cases",cases.size)
            .put("selected_cycles_per_case",4).put("causal_self_accepted_per_case",4).put("noncausal_cycle_excluded_per_case",1).toString(2))
    }
}

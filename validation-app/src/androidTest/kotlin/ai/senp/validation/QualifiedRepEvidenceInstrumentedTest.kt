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
}

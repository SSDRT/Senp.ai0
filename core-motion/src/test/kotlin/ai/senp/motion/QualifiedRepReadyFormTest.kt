package ai.senp.motion

import kotlin.test.*

/** Constructed evidence-admission controls, not labelled human exercise accuracy. */
class QualifiedRepReadyFormTest {
    private val data=QualifiedRepTestData
    @Test fun incompatibleInitialReadyCannotArmAQualifiedCycle() {
        checkBadReady(false)
    }
    @Test fun previouslyArmedStateDoesNotSurviveIncompatibleReadyEvidence() {
        checkBadReady(true)
    }
    private fun checkBadReady(armedFirst: Boolean) {
        val errors=mutableListOf<String>()
        for(id in QualifiedRepCompiler.supportedExerciseIds) for(side in RepObservedSide.entries)
            for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60)) {
                val ref=data.reference(id,QualifiedRepConfig(side=side,mirrorMode=mirror))
                val quality=side.observedFeature(ref.requiredFeatures.first { it!=ref.driverFeature },mirror)
                val normal=data.features(id,0.0).getValue(quality)
                val bad=if(normal>90) 0.0 else 180.0
                val engine=ref.newEngine()
                var last: QualifiedRepOutput?=null
                for((time,p) in data.stream(fps)) {
                    val corrupt=time<300 && (!armedFirst || time>=180)
                    last=engine.evaluate(data.frame(time,p,id,if(corrupt) mapOf(quality to bad) else emptyMap()),data.identity.poseModelSha256)
                }
                if(last?.counts?.qualified!=0) errors+="$id $side $mirror $fps qualified=${last?.counts?.qualified}"
                // A subsequent complete, observed compatible cycle must recover without resetting counts.
                for((time,p) in data.stream(fps,offset=2100)) last=engine.evaluate(data.frame(time,p,id),data.identity.poseModelSha256)
                if(last?.counts?.qualified!=1) errors+="recovery $id $side $mirror $fps qualified=${last?.counts?.qualified}"
            }
        assertTrue(errors.isEmpty(),errors.joinToString("\n"))
    }
    @Test fun acceptableReadyDeviationAndWrongLimbCorruptionPreserveCleanCycle() {
        for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60)) {
            val ref=data.reference(config=QualifiedRepConfig(mirrorMode=mirror))
            val measured=RepObservedSide.LEFT.observedFeature("angle.left_shoulder",mirror)
            val unmeasured=if(measured=="angle.left_shoulder") "angle.right_shoulder" else "angle.left_shoulder"
            val engine=ref.newEngine()
            val outputs=data.stream(fps).map { (time,p) -> engine.evaluate(data.frame(time,p,changes=
                if(time<300) mapOf(measured to 24.0,unmeasured to 180.0) else emptyMap()),data.identity.poseModelSha256) }
            assertEquals(1,outputs.last().counts.qualified)
            assertTrue(outputs.all { it.newFaults.isEmpty() })
        }
    }
}

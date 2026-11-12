package ai.senp.motion

import kotlin.test.*

/** Constructed evidence controls only; never human-form accuracy labels. */
class QualifiedRepClearReadyTest {
    private val data=QualifiedRepTestData
    private fun samples(fps: Int,prepared: Boolean): List<Pair<Long,Double>> = if(!prepared) data.stream(fps) else
        data.stream(fps).takeWhile { it.first<400 }.map { it.first to 0.0 }+data.stream(fps,offset=400)

    @Test fun persistentClearStartCannotSupplyReadyDwellAndFreshCycleRecovers() {
        val errors=mutableListOf<String>()
        for(id in QualifiedRepCompiler.supportedExerciseIds) for(side in RepObservedSide.entries)
            for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60))
                for(prepared in listOf(false,true)) {
                    val ref=data.reference(id,QualifiedRepConfig(side=side,mirrorMode=mirror))
                    val quality=side.observedFeature(ref.requiredFeatures.first { it!=ref.driverFeature },mirror)
                    val normal=data.features(id,0.0).getValue(quality)
                    val bad=if(normal>90) 0.0 else 180.0
                    val engine=ref.newEngine()
                    var last: QualifiedRepOutput?=null
                    for((t,p) in samples(fps,prepared)) {
                        val corrupt=if(prepared) t in 350..699 else t<300
                        last=engine.evaluate(data.frame(t,p,id,if(corrupt) mapOf(quality to bad) else emptyMap()),data.identity.poseModelSha256)
                    }
                    if(last?.counts?.qualified!=0) errors+="$id $side $mirror $fps prepared=$prepared count=${last?.counts?.qualified}"
                    for((t,p) in data.stream(fps,offset=2600)) last=engine.evaluate(data.frame(t,p,id),data.identity.poseModelSha256)
                    if(last?.counts?.qualified!=1) errors+="recovery $id $side $mirror $fps prepared=$prepared count=${last?.counts?.qualified}"
                }
        assertTrue(errors.isEmpty(),errors.joinToString("\n"))
    }

    @Test fun greyTransientAndUnmeasuredReadyDifferencesKeepTheirExistingSemantics() {
        for(id in QualifiedRepCompiler.supportedExerciseIds) for(side in RepObservedSide.entries)
            for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60))
                for(kind in listOf("grey","transient","unmeasured")) {
                    val ref=data.reference(id,QualifiedRepConfig(side=side,mirrorMode=mirror))
                    val quality=side.observedFeature(ref.requiredFeatures.first { it!=ref.driverFeature },mirror)
                    val normal=data.features(id,0.0).getValue(quality)
                    val other=quality.replace(if(quality.contains("left")) "left" else "right",if(quality.contains("left")) "right" else "left")
                    val engine=ref.newEngine()
                    val outputs=data.stream(fps).map { (t,p) ->
                        val changes=when {
                            kind=="grey" && t<300 -> mapOf(quality to normal+if(normal>90) -23.0 else 23.0)
                            kind=="transient" && t<80 -> mapOf(quality to if(normal>90) 0.0 else 180.0)
                            kind=="unmeasured" && t<300 -> mapOf(other to if(normal>90) 0.0 else 180.0)
                            else -> emptyMap()
                        }
                        engine.evaluate(data.frame(t,p,id,changes),data.identity.poseModelSha256)
                    }
                    assertEquals(1,outputs.last().counts.qualified,"$id $side $mirror $fps $kind")
                    assertTrue(outputs.all { it.newFaults.isEmpty() })
                }
    }

    @Test fun oppositeDirectionsCannotPoolAConfirmedReadyDiscrepancy() {
        for(fps in listOf(10,15,30,60)) {
            val engine=data.reference("glute_bridge").newEngine()
            val outputs=data.stream(fps).map { (t,p) ->
                val changes=when { t<100 -> mapOf("angle.left_knee" to 150.0);t<250 -> mapOf("angle.left_knee" to 40.0);else -> emptyMap() }
                engine.evaluate(data.frame(t,p,"glute_bridge",changes),data.identity.poseModelSha256)
            }
            assertEquals(1,outputs.last().counts.qualified)
        }
    }

    @Test fun gapIdentityMissingEvidenceFinishAndResetCannotPoolReadyFaultDwell() {
        for(kind in listOf("gap","subject","driver","quality","finish","reset")) {
            val engine=data.reference().newEngine()
            for(t in listOf(0L,67L,134L)) engine.evaluate(data.frame(t,0.0,changes=mapOf("angle.left_shoulder" to 85.0)),data.identity.poseModelSha256)
            if(kind=="finish") engine.finish(150)
            if(kind=="reset") engine.reset()
            if(kind in listOf("driver","quality")) {
                val key=if(kind=="driver") "angle.left_elbow" else "angle.left_shoulder"
                val raw=data.frame(201,0.0)
                engine.evaluate(raw.copy(intrinsicDescriptor=raw.intrinsicDescriptor.copy(
                    featureConfidences=raw.intrinsicDescriptor.featureConfidences+(key to .1))),data.identity.poseModelSha256)
            }
            val start=when(kind) { "gap" -> 500L;"reset" -> 0L;"driver","quality" -> 268L;else -> 201L }
            val outputs=(0..2).map { index ->
                val frame=data.frame(start+index*67,0.0,changes=mapOf("angle.left_shoulder" to 85.0))
                engine.evaluate(if(kind=="subject") frame.copy(selectedSubjectId="new-subject") else frame,data.identity.poseModelSha256)
            }
            assertEquals(RepStage.READY,outputs.last().stage,kind)
            assertTrue(outputs.all { it.finalizedAttempts.isEmpty() && it.newFaults.isEmpty() })
        }
    }
}

package ai.senp.motion

import kotlin.test.*

/** Constructed causal preparation controls, never human-form accuracy labels. */
class QualifiedRepPreparationFaultTest {
    private val data=QualifiedRepTestData
    private fun prelude(fps: Int): List<Pair<Long,Double>> = generateSequence(0) { it+1 }
        .map { (it*1000.0/fps).toLong() }.takeWhile { it<1000 }
        .map { it to if(it<400) 0.0 else .15 }.toList()
    private fun departWithoutAnotherReady(fps: Int) = data.stream(fps).filter { it.first>=450 }
        .map { 1000+it.first-450 to it.second }

    @Test fun confirmedClearPreDepartureFaultCannotBorrowEarlierReadyDwellAndFreshCycleRecovers() {
        val failures=mutableListOf<String>()
        for(id in QualifiedRepCompiler.supportedExerciseIds) for(side in RepObservedSide.entries)
            for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60)) {
                val ref=data.reference(id,QualifiedRepConfig(side=side,mirrorMode=mirror))
                val quality=side.observedFeature(ref.requiredFeatures.first { it!=ref.driverFeature },mirror)
                val normal=data.features(id,.15).getValue(quality)
                val bad=if(normal>90) 0.0 else 180.0
                val engine=ref.newEngine();var last: QualifiedRepOutput?=null
                for((t,p) in prelude(fps)) last=engine.evaluate(data.frame(t,p,id,if(t>=400) mapOf(quality to bad) else emptyMap()),data.identity.poseModelSha256)
                for((t,p) in departWithoutAnotherReady(fps)) last=engine.evaluate(data.frame(t,p,id),data.identity.poseModelSha256)
                if(last?.counts?.qualified!=0) failures+="$id $side $mirror $fps unsupported=${last?.counts?.qualified}"
                for((t,p) in data.stream(fps,offset=3000)) last=engine.evaluate(data.frame(t,p,id),data.identity.poseModelSha256)
                if(last?.counts?.qualified!=1) failures+="$id $side $mirror $fps recovery=${last?.counts?.qualified}"
            }
        assertTrue(failures.isEmpty(),failures.joinToString("\n"))
    }

    @Test fun greyBriefAndUnmeasuredPreDepartureDifferencesKeepExistingCounts() {
        for(id in QualifiedRepCompiler.supportedExerciseIds) for(side in RepObservedSide.entries)
            for(mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for(fps in listOf(10,15,30,60))
                for(kind in listOf("grey","brief","unmeasured")) {
                    val ref=data.reference(id,QualifiedRepConfig(side=side,mirrorMode=mirror))
                    val quality=side.observedFeature(ref.requiredFeatures.first { it!=ref.driverFeature },mirror)
                    val normal=data.features(id,.15).getValue(quality)
                    val other=quality.replace(if(quality.contains("left")) "left" else "right",if(quality.contains("left")) "right" else "left")
                    val engine=ref.newEngine();var last: QualifiedRepOutput?=null
                    for((t,p) in prelude(fps)) {
                        val change=when {
                            kind=="grey" && t>=400 -> mapOf(quality to normal+if(normal>90) -23.0 else 23.0)
                            kind=="brief" && t in 400..479 -> mapOf(quality to if(normal>90) 0.0 else 180.0)
                            kind=="unmeasured" && t>=400 -> mapOf(other to if(normal>90) 0.0 else 180.0)
                            else -> emptyMap()
                        }
                        last=engine.evaluate(data.frame(t,p,id,change),data.identity.poseModelSha256)
                    }
                    for((t,p) in departWithoutAnotherReady(fps)) last=engine.evaluate(data.frame(t,p,id),data.identity.poseModelSha256)
                    assertEquals(1,last?.counts?.qualified,"$id $side $mirror $fps $kind")
                }
    }
}

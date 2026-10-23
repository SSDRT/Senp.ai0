package ai.senp.motion

import ai.senp.core.contracts.TimestampMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class QualifiedRepEngineTest {
    private val data = QualifiedRepTestData
    private fun replay(reference: QualifiedRepReference = data.reference(), amplitude: Double = 1.0,
        change: (Long,Double,SpatialObservationFrame) -> SpatialObservationFrame = { _,_,f -> f }): List<QualifiedRepOutput> {
        val engine = reference.newEngine()
        return data.stream(amplitude = amplitude).map { (time,p) -> engine.evaluate(change(time,p,data.frame(time,p,reference.exerciseId)),data.identity.poseModelSha256) }
    }
    @Test fun cleanCyclesCountOnceForAllSixExercisesSidesMirrorsAndFrameRates() {
        for (id in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries) for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60)) {
            val ref = data.reference(id,QualifiedRepConfig(side = side,mirrorMode = mirror))
            val engine = ref.newEngine()
            val outputs = data.stream(fps).map { (time,p) -> engine.evaluate(data.frame(time,p,id),data.identity.poseModelSha256) }
            assertEquals(1,outputs.last().counts.qualified,"$id $side $mirror $fps")
            assertEquals(1,outputs.sumOf { it.finalizedAttempts.size })
            assertTrue(outputs.all { !it.releaseQualified })
            assertEquals(if (mirror == ActionMirrorMode.MIRRORED) { if (side == RepObservedSide.LEFT) RepObservedSide.RIGHT else RepObservedSide.LEFT } else side,outputs.last().observedSide)
        }
    }
    @Test fun smallDeviationIsAcceptedButNotLabelledExactAndGreyZoneIsUnassessable() {
        val almost = replay { _,_,f -> withValue(f,"angle.left_shoulder",24.0) }
        assertEquals(1,almost.last().counts.acceptable)
        val grey = replay { time,_,f -> if (time in 500..1450) withValue(f,"angle.left_shoulder",38.0) else f }
        assertEquals(1,grey.last().counts.unassessable)
        assertEquals(0,grey.last().counts.qualified)
        assertEquals(0,grey.last().counts.rejected)
    }
    @Test fun severeFaultIsReportedBeforeCompletionAndCannotBeErasedByRecovery() {
        val outputs = replay { time,_,f -> if (time in 500..1000) withValue(f,"angle.left_shoulder",70.0) else f }
        val fault = outputs.flatMap { it.newFaults }.single()
        assertEquals(RepReason.CLEAR_FORM_DEVIATION,fault.reason)
        assertTrue(fault.confirmedMs-fault.firstEvidenceMs in 160..200)
        assertTrue(outputs.any { it.activeDisqualified && it.counts.finalized == 0 })
        assertEquals(1,outputs.last().counts.rejected)
        assertEquals(0,outputs.last().counts.qualified)
        assertEquals(RepVerdict.REJECTED,outputs.flatMap { it.finalizedAttempts }.single().verdict)
    }
    @Test fun shallowReversalIsRejectedOnlyAfterReversalIsObserved() {
        val outputs = replay(amplitude = 0.45)
        val fault = outputs.flatMap { it.newFaults }.single()
        assertEquals(RepReason.EARLY_REVERSAL,fault.reason)
        assertTrue(fault.firstEvidenceMs > 1100,"Do not reject while still descending or holding")
        assertEquals(1,outputs.last().counts.rejected)
        val marginal = replay(amplitude = 0.75)
        assertEquals(1,marginal.last().counts.unassessable)
        assertTrue(marginal.flatMap { it.finalizedAttempts }.single().reasons.contains(RepReason.INSUFFICIENT_RANGE))
        assertEquals(1,replay(amplitude = 0.90).last().counts.acceptable)
    }
    @Test fun isolatedDepartureSpikesCannotInventAReversalOrHideAShallowRep() {
        for (amplitude in listOf(1.0,.45)) {
            val engine = data.reference().newEngine()
            val outputs = QualifiedRepDepartureBenchmark.samples(30,amplitude,true).map { (time,p) ->
                engine.evaluate(data.frame(time,p),data.identity.poseModelSha256)
            }
            assertTrue(outputs.filter { it.timestampMs < 1100 }.none { it.stage == RepStage.RETURNING })
            val event = outputs.flatMap { it.finalizedAttempts }.single()
            if (amplitude == 1.0) assertEquals(1,outputs.last().counts.qualified) else {
                assertEquals(RepVerdict.REJECTED,event.verdict)
                assertEquals(.45,event.maximumProgress,1e-8)
                assertTrue(RepReason.EARLY_REVERSAL in event.reasons)
            }
        }
    }
    @Test fun twoAdjacentDepartureObservationsStillEstablishARealPeak() {
        val engine = data.reference().newEngine()
        val samples = listOf(0.0,0.0,0.0,.15,1.0,1.0,.5,0.0,0.0,0.0,0.0,0.0)
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
        assertEquals(1,outputs.last().counts.qualified)
        assertEquals(1.0,outputs.flatMap { it.finalizedAttempts }.single().maximumProgress)
    }
    @Test fun unsupportedDepartureFollowedByReadyJitterCannotProveAnEarlyReversalFault() {
        val engine = data.reference().newEngine()
        val samples = listOf(.08,.08,.08,.98)+List(8) { -.09 }
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
        assertEquals(0,outputs.last().counts.qualified)
        assertEquals(0,outputs.last().counts.rejected)
        assertTrue(outputs.all { it.newFaults.isEmpty() })
        val event = outputs.flatMap { it.finalizedAttempts }.single()
        assertEquals(RepVerdict.UNASSESSABLE,event.verdict)
        assertTrue(RepReason.INSUFFICIENT_RANGE in event.reasons)
    }
    @Test fun missingDriverStopsAnAttemptAndRecoveryMidRepCannotEarnACount() {
        val outputs = replay { time,_,f -> if (time in 700..800) without(f,"angle.left_elbow") else f }
        assertEquals(1,outputs.last().counts.unassessable)
        assertEquals(0,outputs.last().counts.qualified)
        assertEquals(1,outputs.sumOf { it.finalizedAttempts.size })
    }
    @Test fun missingCriticalQualityAtTurnaroundCannotBeRepairedIntoAPassedEndpoint() {
        val outputs = replay { _,p,f -> if (p >= 0.82) without(f,"angle.left_shoulder") else f }
        assertEquals(0,outputs.last().counts.qualified)
        assertEquals(1,outputs.last().counts.unassessable)
    }
    @Test fun gapsModelPreprocessingAndCaptureChangesFinalizeOnceAndRequireFreshStart() {
        for (kind in listOf("gap","model","pipeline","camera","subject","2d")) {
            val engine = data.reference().newEngine()
            var last: QualifiedRepOutput? = null
            val events = mutableListOf<RepAttemptResult>()
            for ((time,p) in data.stream()) {
                val trigger = time in 700..800
                val f = data.frame(time,p).let { when {
                    kind == "subject" && trigger -> it.copy(selectedSubjectId = "other")
                    kind == "2d" && trigger -> it.copy(evidenceKind = SpatialEvidenceKind.IMAGE_2D)
                    else -> it
                } }
                if (kind == "gap" && time in 650..1000) continue
                last = engine.evaluate(f,if (kind == "model" && trigger) "wrong" else data.identity.poseModelSha256,
                    if (kind == "pipeline" && trigger) "other" else RepEvidenceFingerprint.RAW_WORLD_PIPELINE,
                    if (kind == "camera" && trigger) "rotated" else "original")
                events += last.finalizedAttempts
            }
            assertEquals(0,last?.counts?.qualified,kind)
            assertEquals(1,events.size,kind)
            assertEquals(RepVerdict.UNASSESSABLE,events.single().verdict,kind)
        }
    }
    @Test fun startsInMiddleEndpointBouncesAndStationaryNoiseDoNotCount() {
        val engine = data.reference().newEngine()
        var result: QualifiedRepOutput? = null
        for ((time,p) in data.stream().filter { it.first >= 700 }) result = engine.evaluate(data.frame(time,p),data.identity.poseModelSha256)
        assertEquals(0,result?.counts?.finalized)
        for (time in 2100L..6000L step 33) result = engine.evaluate(data.frame(time,if (time%2L==0L) 0.04 else 0.0),data.identity.poseModelSha256)
        assertEquals(0,result?.counts?.qualified)
    }
    @Test fun finishDoesNotAcceptPartialCyclesAndRepeatedFinishDoesNotDuplicateThem() {
        val engine = data.reference().newEngine()
        for ((time,p) in data.stream().takeWhile { it.first <= 800 }) engine.evaluate(data.frame(time,p),data.identity.poseModelSha256)
        assertEquals(1,engine.finish(900).counts.unassessable)
        assertTrue(engine.finish(1000).finalizedAttempts.isEmpty())
        assertFailsWith<IllegalArgumentException> { engine.evaluate(data.frame(1000,0.0),data.identity.poseModelSha256) }
        engine.reset()
        assertEquals(0,engine.evaluate(data.frame(0,0.0),data.identity.poseModelSha256).counts.finalized)
    }
    @Test fun slowAndFastCompleteCyclesKeepCountingSemantics() {
        for (scale in listOf(0.5,1.0,2.0,5.0)) {
            val engine = data.reference().newEngine()
            val results = data.stream(30,scale = scale).map { (time,p) -> engine.evaluate(data.frame(time,p),data.identity.poseModelSha256) }
            assertEquals(1,results.last().counts.qualified,"scale=$scale")
        }
    }
    @Test fun longStreamHasUniqueAttemptIdsAndConservedCountsWithoutRetainedEvents() {
        val engine = data.reference().newEngine()
        val ids = mutableSetOf<Long>()
        var result: QualifiedRepOutput? = null
        repeat(100) { rep ->
            for ((time,p) in data.stream(15,offset = rep*2100L)) {
                result = engine.evaluate(data.frame(time,p),data.identity.poseModelSha256)
                for (event in result.finalizedAttempts) assertTrue(ids.add(event.attemptId))
                assertTrue(result.finalizedAttempts.size <= 1 && result.newFaults.size <= 3)
            }
        }
        assertEquals(100,result?.counts?.qualified)
        assertEquals(100,result?.counts?.finalized)
        assertEquals(100,ids.size)
    }
    @Test fun processingAStreamPrefixHasNoDependencyOnFutureMovement() {
        val a = data.reference().newEngine(); val b = data.reference().newEngine()
        for ((time,p) in data.stream().takeWhile { it.first <= 1000 }) {
            assertEquals(a.evaluate(data.frame(time,p),data.identity.poseModelSha256),b.evaluate(data.frame(time,p),data.identity.poseModelSha256))
        }
        assertEquals(0,a.finish(1050).counts.qualified)
        for ((time,p) in data.stream().filter { it.first > 1000 }) b.evaluate(data.frame(time,p),data.identity.poseModelSha256)
        assertEquals(1,b.finish(2100).counts.qualified)
    }
    @Test fun guidanceModeCanRejectABadDemonstrationWithoutChangingReferenceMatchMode() {
        val bad = data.profile().copy(states = data.profile().states.map { state -> state.copy(features = state.features.map {
            if (it.name == "angle.left_shoulder") it.copy(reference = it.reference.copy(median = 70.0,lower = 68.0,upper = 72.0)) else it
        }) })
        val plain = (QualifiedRepCompiler.compile(bad,"biceps_curl",data.identity) as QualifiedRepCompilation.Ready).reference.newEngine()
        val guarded = (QualifiedRepCompiler.compile(bad,"biceps_curl",data.identity,QualifiedRepConfig(mode = RepPolicyMode.REFERENCE_WITH_TECHNIQUE_GUARDS)) as QualifiedRepCompilation.Ready).reference.newEngine()
        var p: QualifiedRepOutput? = null; var g: QualifiedRepOutput? = null
        for ((time,progress) in data.stream()) {
            val f = withValue(data.frame(time,progress),"angle.left_shoulder",70.0)
            p = plain.evaluate(f,data.identity.poseModelSha256); g = guarded.evaluate(f,data.identity.poseModelSha256)
        }
        assertEquals(1,p?.counts?.qualified)
        assertEquals(1,g?.counts?.rejected)
        assertEquals(70.0,bad.states.first().features.first { it.name == "angle.left_shoulder" }.reference.median)
    }
    @Test fun referenceCompilationRefusesUnsupportedWeakOrStaticInputsAndFingerprintsAreStable() {
        val profile = data.profile()
        fun reason(id: String,p: ActionProfile = profile) = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(p,id,data.identity)).reason
        assertEquals(RepReferenceFailure.UNKNOWN_EXERCISE,reason("whatever"))
        assertEquals(RepReferenceFailure.UNSUPPORTED_EXERCISE,reason("plank"))
        assertEquals(RepReferenceFailure.STATIC_OR_NONCYCLIC,reason("biceps_curl",WorkoutPrecisionTestData.profile(static = true)))
        assertEquals(RepReferenceFailure.WEAK_REFERENCE,reason("biceps_curl",profile.copy(confidence = 0.1)))
        assertEquals(RepReferenceFailure.WEAK_REFERENCE,reason("biceps_curl",profile.copy(states = profile.states.map { state -> state.copy(features = state.features.map {
            if (it.name == "angle.left_shoulder") it.copy(reference = it.reference.copy(mad = 40.0)) else it
        }) })))
        assertEquals(RepReferenceFailure.INSUFFICIENT_EXCURSION,reason("biceps_curl",WorkoutPrecisionTestData.profile()))
        assertEquals(data.reference().referenceKey,data.reference().referenceKey)
        assertEquals("qualified-reference-reps/4",data.reference().schema)
        assertNotEquals(data.reference().referenceKey,data.reference(config = QualifiedRepConfig(requireObservedPath = false)).referenceKey)
        assertNotEquals(data.reference().referenceKey,data.reference(config = QualifiedRepConfig(side = RepObservedSide.RIGHT)).referenceKey)
        val guided = data.reference(config = QualifiedRepConfig(mode = RepPolicyMode.REFERENCE_WITH_TECHNIQUE_GUARDS))
        val changedTargets = guided.sourceTargets.mapValues { (_,targets) -> targets.mapValues { (_,target) -> target.copy(lower = 10.0,upper = 10.0) } }
        val changed = QualifiedRepReference(guided.profile,guided.fingerprint,guided.config,guided.spec,guided.driverFeature,guided.requiredFeatures,
            guided.readyAngle,guided.farAngle,guided.nodes,changedTargets,guided.sourceTolerances)
        assertNotEquals(guided.referenceKey,changed.referenceKey,"Guidance changes must invalidate policy identity")
        assertFailsWith<IllegalArgumentException> { RepEvidenceFingerprint("bad","bad") }
    }
    @Test fun confidentEndpointJumpsWithoutObservedMotionCannotEarnACount() {
        val samples = listOf(0L to 0.0,67L to 0.0,134L to 0.0,201L to 1.0,268L to 1.0,
            335L to 0.0,402L to 0.0,469L to 0.0,536L to 0.0,603L to 0.0)
        fun run(requirePath: Boolean): QualifiedRepOutput {
            val engine = data.reference(config = QualifiedRepConfig(requireObservedPath = requirePath)).newEngine()
            return samples.map { (time,p) -> engine.evaluate(data.frame(time,p),data.identity.poseModelSha256) }.last()
        }
        assertEquals(1,run(false).counts.qualified,"Counterfactual demonstrates the endpoint-only shortcut")
        val fixed = run(true)
        assertEquals(0,fixed.counts.qualified)
        assertEquals(1,fixed.counts.unassessable)
        assertEquals(RepReason.INSUFFICIENT_PATH_EVIDENCE,fixed.finalizedAttempts.single().reasons.single())
    }
    @Test fun oneIntermediateObservationPerDirectionCanSupportAFastVisibleRep() {
        for (outboundOnly in listOf(true,false)) {
            val samples = buildList {
                addAll(listOf(0.0,0.0,0.0))
                addAll(if (outboundOnly) listOf(.3,.7,1.0,1.0) else listOf(.5,1.0,1.0))
                addAll(if (outboundOnly) listOf(.5,0.0,0.0,0.0,0.0,0.0) else listOf(.7,.3,0.0,0.0,0.0,0.0,0.0))
            }
            val engine = data.reference().newEngine()
            val result = samples.mapIndexed { index,p -> engine.evaluate(data.frame(index*67L,p),data.identity.poseModelSha256) }.last()
            assertEquals(1,result.counts.qualified,"outboundOnly=$outboundOnly")
            assertEquals(0,result.counts.unassessable)
        }
    }
    @Test fun anAbandonedPartialReturnCannotSupplyEvidenceForALaterEndpointJump() {
        val samples = listOf(0.0,0.0,0.0,0.0,0.0,.3,.6,.8,.8,.6,.4,.8,1.0,1.0,1.0,1.0,
            0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0)
        val engine = data.reference().newEngine()
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*33L,p),data.identity.poseModelSha256) }
        assertEquals(0,outputs.last().counts.qualified)
        val event = outputs.flatMap { it.finalizedAttempts }.single()
        assertEquals(RepVerdict.UNASSESSABLE,event.verdict)
        assertTrue(RepReason.INSUFFICIENT_PATH_EVIDENCE in event.reasons)
    }
    @Test fun observedEarlyMovementBeforeDepartureRemainsValidPathEvidence() {
        val samples = listOf(0.0,0.0,0.0,.15,1.0,1.0,.5,0.0,0.0,0.0,0.0,0.0)
        val engine = data.reference().newEngine()
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
        assertEquals(1,outputs.last().counts.qualified)
        assertTrue(outputs.flatMap { it.finalizedAttempts }.single().outboundPathObserved)
    }
    @Test fun returningToReadyDiscardsAbandonedEarlyMovementEvidence() {
        val samples = listOf(0.0,0.0,0.0,.15,0.0,0.0,1.0,1.0,.5,0.0,0.0,0.0,0.0,0.0)
        val engine = data.reference().newEngine()
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
        assertEquals(0,outputs.last().counts.qualified)
        val event = outputs.flatMap { it.finalizedAttempts }.single()
        assertFalse(event.outboundPathObserved)
        assertTrue(RepReason.INSUFFICIENT_PATH_EVIDENCE in event.reasons)
    }
    @Test fun returnEvidenceMustFollowTheConfirmedFarEndpointAndCannotSurviveAnotherFarVisit() {
        for (middle in listOf(
            listOf(.3,.7,.7,.4,.4,.4,.4,1.0,1.0),
            listOf(.3,.7,1.0,1.0,.6,.4,.4,.4,1.0,1.0),
        )) {
            val samples = listOf(0.0,0.0,0.0)+middle+List(6) { 0.0 }
            val engine = data.reference().newEngine()
            val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
            assertEquals(0,outputs.last().counts.qualified)
            val event = outputs.flatMap { it.finalizedAttempts }.single()
            assertEquals(RepVerdict.UNASSESSABLE,event.verdict)
            assertFalse(event.returnPathObserved)
            assertTrue(RepReason.INSUFFICIENT_PATH_EVIDENCE in event.reasons)
        }
    }
    @Test fun aReadyRevisitInsideAnAttemptInvalidatesItsEarlierOutboundWitness() {
        val samples = listOf(0.0,0.0,0.0,.3,.3,0.0,.9,1.0,1.0,.5,0.0,0.0,0.0,0.0,0.0)
        val engine = data.reference().newEngine()
        val outputs = samples.mapIndexed { i,p -> engine.evaluate(data.frame(i*67L,p),data.identity.poseModelSha256) }
        assertEquals(0,outputs.last().counts.qualified)
        val event = outputs.flatMap { it.finalizedAttempts }.single()
        assertFalse(event.outboundPathObserved)
    }
    private fun withValue(frame: SpatialObservationFrame,name: String,value: Double) = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = frame.intrinsicDescriptor.values+(name to value)))
    private fun without(frame: SpatialObservationFrame,name: String) = frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(
        values = frame.intrinsicDescriptor.values-name,featureConfidences = frame.intrinsicDescriptor.featureConfidences-name))
}

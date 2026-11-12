package ai.senp.motion

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.ObservationSampling
import ai.senp.core.contracts.VideoRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.math.abs
import kotlin.math.pow

class QualifiedRepReferenceCompilerTest {
    private val data = QualifiedRepTestData
    private fun sequence(cycles: Int = 4, amplitude: Double = 1.0): SpatialSequenceAnalysis {
        val frames = (0 until cycles).flatMap { rep -> data.stream(30,amplitude = amplitude,offset = rep*2100L).map { (time,p) -> data.frame(time,p) } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
    }
    @Test fun directlyObservedCyclesCompileAndASeparateCompleteCycleCanBeAccepted() {
        val result = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(sequence(),"biceps_curl",data.identity))
        val calibration = requireNotNull(result.reference.calibration)
        assertEquals(4,calibration.observedCycles)
        assertEquals(4,calibration.acceptedSelfCycles)
        assertEquals(1.0,calibration.selfAcceptanceFraction)
        assertTrue(calibration.selectedFrames < calibration.sourceFrames)
        assertEquals(6,result.reference.profile.states.size)
        val engine = result.reference.newEngine()
        val output = data.stream().map { (time,p) -> engine.evaluate(data.frame(time,p),data.identity.poseModelSha256) }.last()
        assertEquals(1,output.counts.qualified)
    }
    @Test fun fittingNarrowEndpointBinsCannotShrinkTheDriverExcursion() {
        val frames = (0 until 4).flatMap { rep -> data.stream(offset = rep*2100L).map { (time,p) ->
            // Return confirmation happens just inside the ready threshold; the full resting
            // extension follows later, outside the selected cycle's endpoint fitting bin.
            val delayedReturn = if (time-rep*2100L in 1667..1867) maxOf(p,.08) else p
            data.frame(time,delayedReturn)
        } }
        val source = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
        val result = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity))
        assertEquals(170.0,result.reference.readyAngle,1e-8)
        assertEquals(70.0,result.reference.farAngle,1e-8)
        assertEquals(1.0,requireNotNull(result.reference.calibration).selfAcceptanceFraction)
    }
    @Test fun anIsolatedDepartureSpikeCannotMoveTheReferenceTurnaroundBeforeItsObservedPath() {
        val frames = (0 until 4).flatMap { rep ->
            QualifiedRepDepartureBenchmark.samples(30,1.0,true).map { (time,p) -> data.frame(time+rep*2100L,p) }
        }
        val source = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
        val result = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity))
        val calibration = requireNotNull(result.reference.calibration)
        assertEquals(4,calibration.observedCycles)
        assertEquals(4,calibration.acceptedSelfCycles)
        val engine = result.reference.newEngine()
        val outputs = data.stream().map { (time,p) -> engine.evaluate(data.frame(time,p),data.identity.poseModelSha256) }
        assertEquals(1,outputs.last().counts.qualified)
    }
    @Test fun repeatedWorkoutEndpointsAreNotReplacedByLongSetupMotion() {
        val setup = (0 until 120).map { data.frame(it*33L,0.0,"overhead_press",mapOf("angle.left_shoulder" to 20.0)) }
        val movement = (0 until 4).flatMap { rep -> data.stream(offset = 4000+rep*2100L).map { (time,p) ->
            data.frame(time,p,"overhead_press",mapOf("angle.left_shoulder" to 80+80*p))
        } }
        val frames = setup+movement
        val source = SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
        assertTrue(quantile(frames.map { it.intrinsicDescriptor.values.getValue("angle.left_shoulder") },.1) < 40)
        val reference = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"overhead_press",data.identity)).reference
        assertEquals(80.0,reference.readyAngle,1e-8)
        assertEquals(160.0,reference.farAngle,1e-8)
        assertEquals(4,requireNotNull(reference.calibration).acceptedSelfCycles)
    }
    @Test fun isolatedSpikesAndMissingReversalsCannotDefineRepeatedEndpoints() {
        val spikes = (0..300).map { it*33L to if (it%30==15) 150.0 else 40.0 }
        assertEquals(null,repeatedRepEndpoints(spikes,QualifiedRepConfig()))
        val missing = (0 until 4).flatMap { rep -> data.stream(offset = rep*2100L).map { (time,p) ->
            time to (170-100*p).takeIf { p < .8 }
        } }
        assertEquals(null,repeatedRepEndpoints(missing,QualifiedRepConfig()))
    }
    @Test fun partialMissingAndTooShortReferencesDoNotBecomeSuccessfulCalibrations() {
        assertEquals(RepReferenceFailure.INSUFFICIENT_REFERENCE_CYCLES,
            assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence(1),"biceps_curl",data.identity)).reason)
        val unavailable = sequence().let { it.copy(frames = it.frames.map { frame ->
            val values = frame.intrinsicDescriptor.values-"angle.left_shoulder"
            frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = values,featureConfidences = frame.intrinsicDescriptor.featureConfidences.filterKeys { key -> key in values }))
        }) }
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(unavailable,"biceps_curl",data.identity))
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence().copy(frames = sequence().frames.map {
            it.copy(evidenceKind = SpatialEvidenceKind.IMAGE_2D)
        }),"biceps_curl",data.identity))
    }
    @Test fun setupAndRestCannotReplaceCompleteReferenceCycles() {
        val movement = sequence(2)
        val rest = (0..150).map { data.frame(movement.frames.last().timestamp.value+33+(it*33L),0.0) }
        val all = movement.frames+rest
        val extended = movement.copy(frames = all,duration = DurationMs(all.last().timestamp.value+1))
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(extended,"biceps_curl",data.identity))
    }
    @Test fun missingMilestoneEvidenceIsNeverInterpolatedIntoAReferenceCycle() {
        val source = sequence()
        val frames = source.frames.map { f ->
            val p = (170-f.intrinsicDescriptor.values.getValue("angle.left_elbow"))/100
            if (p < 0.82) f else f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
                featureConfidences = f.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to 0.1)))
        }
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(source.copy(frames = frames),"biceps_curl",data.identity))
    }

    @Test fun finerReferenceCurvesReduceNonlinearInterpolationErrorOnSeparateProgressSamples() {
        val source = sequence().let { sequence -> sequence.copy(frames = sequence.frames.map { frame ->
            val p = (170-frame.intrinsicDescriptor.values.getValue("angle.left_elbow"))/100
            frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(values = frame.intrinsicDescriptor.values+
                ("angle.left_shoulder" to 15+130*p.pow(5))))
        }) }
        // Only this diagnostic comparison disables self-qualification to measure fit error.
        // Production configurations keep the same 80% self-reconstruction requirement.
        fun curve(nodes: Int) = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,
            QualifiedRepConfig(referenceCurveNodes = nodes,minimumReferenceReconstruction = 0.0))).reference
        val coarse = curve(6);val fine = curve(10)
        fun error(ref: QualifiedRepReference) = (1..16).map { index ->
            val p = index/20.0
            val band = ref.band("angle.left_shoulder",p,false)
            abs((band.lower+band.upper)/2-(15+130*p.pow(5)))
        }.average()
        assertTrue(error(fine) < error(coarse)*0.7,"Coarse ${error(coarse)}, finer ${error(fine)}")
        assertEquals(10,fine.profile.states.size)
        assertEquals(6,coarse.profile.states.size)
        assertFailsWith<IllegalArgumentException> { QualifiedRepConfig(referenceCurveNodes = 8) }
    }

    @Test fun finerCurvesStillRequireObservedCyclesAndMilestones() {
        val config = QualifiedRepConfig(referenceCurveNodes = 10)
        val reference = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(sequence(),"biceps_curl",data.identity,config)).reference
        assertEquals(1.0,requireNotNull(reference.calibration).selfAcceptanceFraction)
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence(2),"biceps_curl",data.identity,config))
        val source = sequence().let { it.copy(frames = it.frames.map { f ->
            val p = (170-f.intrinsicDescriptor.values.getValue("angle.left_elbow"))/100
            if (p < .82) f else f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
                featureConfidences = f.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to 0.1)))
        }) }
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,config))
    }
    @Test fun selfCalibrationCannotTreatAnUnobservedPathAsADemonstratedCompleteRep() {
        val source = sequence().let { it.copy(frames = it.frames.map { frame ->
            val local = frame.timestamp.value-6300
            if (local < 0) frame else data.frame(frame.timestamp.value,if (local in 300..1100) 1.0 else 0.0)
        }) }
        for (nodes in listOf(6,10)) {
            val config = QualifiedRepConfig(referenceCurveNodes = nodes)
            // Correct branch splitting makes the endpoint-only ablation reconstruct the
            // jump too. The production path gate must independently withhold it.
            val baseline = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,config.copy(requireObservedPath = false)))
            assertEquals(4,requireNotNull(baseline.reference.calibration).acceptedSelfCycles)
            val result = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(source,"biceps_curl",data.identity,config))
            assertEquals(RepReferenceFailure.LOW_SELF_RECONSTRUCTION,result.reason)
        }
    }
}

package ai.senp.motion

import ai.senp.core.contracts.DurationMs
import ai.senp.core.contracts.ObservationSampling
import ai.senp.core.contracts.VideoRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
}

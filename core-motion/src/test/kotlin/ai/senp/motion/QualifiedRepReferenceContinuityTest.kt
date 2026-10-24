package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

/** A tolerated self-replay failure must not make a noncausal reference cycle training evidence. */
class QualifiedRepReferenceContinuityTest {
    private fun sequence(change: (Int,Long,SpatialObservationFrame) -> SpatialObservationFrame): SpatialSequenceAnalysis {
        val frames = (0 until 5).flatMap { rep -> QualifiedRepTestData.stream(offset = rep*2100L).map { (time,p) ->
            change(rep,time-rep*2100L,QualifiedRepTestData.frame(time,p))
        } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
    }
    private fun compile(sequence: SpatialSequenceAnalysis): QualifiedRepReference = assertIs<QualifiedRepCompilation.Ready>(
        QualifiedRepCompiler.compile(sequence,"biceps_curl",QualifiedRepTestData.identity)).reference

    @Test fun aCycleCrossingASpatialSegmentBoundaryIsNotCalibrationEvidence() {
        val reference = compile(sequence { rep,t,frame -> frame.copy(spatialSegmentId = if (rep==0 && t<1000) 0 else 1) })
        assertEquals(4,reference.calibration?.observedCycles)
        assertEquals(4,reference.calibration?.acceptedSelfCycles)
        assertEquals(1.0,reference.calibration?.selfAcceptanceFraction)
    }

    @Test fun missingCriticalEvidenceBeforeDepartureDisarmsReferenceCycleSelection() {
        val reference = compile(sequence { rep,t,frame ->
            if (rep==0 && t==400L) frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(
                featureConfidences = frame.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to 0.0))) else frame
        })
        assertEquals(4,reference.calibration?.observedCycles)
        assertEquals(4,reference.calibration?.acceptedSelfCycles)
    }

    @Test fun readyDwellCannotBeCarriedAcrossASpatialSegmentBoundary() {
        val reference = compile(sequence { rep,t,frame -> frame.copy(spatialSegmentId = if (rep==0 && t<266) 0 else 1) })
        assertEquals(4,reference.calibration?.observedCycles)
        assertEquals(4,reference.calibration?.acceptedSelfCycles)
    }

    @Test fun completeCyclesInSeparateSegmentsStillContributeDirectEvidence() {
        val reference = compile(sequence { rep,_,frame -> frame.copy(spatialSegmentId = rep) })
        assertEquals(5,reference.calibration?.observedCycles)
        assertEquals(5,reference.calibration?.acceptedSelfCycles)
    }

    @Test fun endpointReversalsCannotBorrowMovementFromOtherSegments() {
        val samples = (0 until 8).flatMap { segment -> (0..4).map { i ->
            segment*500L+i*100L to if (segment%2==0) 170.0 else 70.0
        } }
        // Without provenance, these steps resemble movement. Within each actual
        // capture segment the person is stationary; there is no observed excursion.
        assertNotNull(repeatedRepEndpoints(samples,QualifiedRepConfig()))
        assertNull(repeatedRepEndpoints(samples,QualifiedRepConfig(),(1..7).mapTo(mutableSetOf()) { it*500L }))
    }
}

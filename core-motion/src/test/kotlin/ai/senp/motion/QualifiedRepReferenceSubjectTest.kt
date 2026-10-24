package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

class QualifiedRepReferenceSubjectTest {
    private fun sequence(subject: (Int) -> String?): SpatialSequenceAnalysis {
        val frames = (0 until 4).flatMap { rep -> QualifiedRepTestData.stream(offset = rep*2100L).map { (time,p) ->
            QualifiedRepTestData.frame(time,p).copy(selectedSubjectId = subject(rep),spatialSegmentId = rep)
        } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
    }
    @Test fun oneCycleFromEachOfFourPeopleCannotBecomeAFourCycleReference() {
        val result = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence { "person-$it" },"biceps_curl",QualifiedRepTestData.identity))
        assertEquals(RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT,result.reason)
    }
    @Test fun identityAmbiguityCannotBeHiddenByChangingTheCurveFit() {
        for (fit in RepReferenceCurveFit.entries) {
            val result = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence { "person-$it" },"biceps_curl",QualifiedRepTestData.identity,
                QualifiedRepConfig(referenceCurveFit = fit)))
            assertEquals(RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT,result.reason)
        }
    }
    @Test fun anonymousAndBlankThreeDimensionalEvidenceCannotPrepareAQualifiedReference() {
        for (subject in listOf(null,""," ")) {
            val result = assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(sequence { subject },"biceps_curl",QualifiedRepTestData.identity))
            assertEquals(RepReferenceFailure.MISSING_PROVENANCE,result.reason)
        }
    }
    @Test fun segmentChangesAloneDoNotClaimDifferentPeopleOrRemoveObservedCycles() {
        val ref = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(sequence { "same-tracked-subject" },"biceps_curl",QualifiedRepTestData.identity)).reference
        assertEquals(4,ref.calibration?.observedCycles)
        assertEquals(4,ref.calibration?.acceptedSelfCycles)
        assertEquals("qualified-rep-compiler/2",QualifiedRepCompiler.VERSION)
    }
}

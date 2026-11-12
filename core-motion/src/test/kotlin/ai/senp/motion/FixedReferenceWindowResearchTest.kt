package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

class FixedReferenceWindowResearchTest {
    private val data = QualifiedRepTestData
    private fun source(): SpatialSequenceAnalysis {
        val frames = (0 until 14).flatMap { rep -> data.stream(30,offset = rep*2100L).map { (time,p) ->
            val f = data.frame(time,p)
            if (rep < 7) f else f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
                values = f.intrinsicDescriptor.values + ("angle.left_shoulder" to 120.0)))
        } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond=30.0),frames,1.0)
    }

    @Test fun aFixedEarlierDemonstrationCanCalibrateWithoutPoolingTheLaterDifferentPose() {
        val seq=source()
        val full=QualifiedRepCompiler.compile(seq,"biceps_curl",data.identity)
        assertIs<QualifiedRepCompilation.Unavailable>(full)
        val first=FixedReferenceWindowResearch.windows(seq).first()
        assertEquals(0,first.startMs);assertEquals(16_000,first.endExclusiveMs)
        val calibrated=assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(first.sequence,"biceps_curl",data.identity))
        val c=requireNotNull(calibrated.reference.calibration)
        assertTrue(c.observedCycles>=3);assertTrue(c.selfAcceptanceFraction>=.80)
        // No acceptance/range/confidence defaults are changed for the selected window.
        assertEquals(QualifiedRepConfig(),calibrated.reference.config)
    }

    @Test fun windowsKeepOriginalObjectsAbsoluteTimestampsAndMissingEvidence() {
        val original=source()
        val altered=original.copy(frames=original.frames.filter { it.timestamp.value !in 12_000..13_000 }.map { f ->
            if(f.timestamp.value==original.frames[10].timestamp.value) f.copy(selectedSubjectId=null,
                intrinsicDescriptor=f.intrinsicDescriptor.copy(featureConfidences=emptyMap())) else f
        })
        val windows=FixedReferenceWindowResearch.windows(altered)
        assertEquals(listOf(0L,8_000L,16_000L),windows.map { it.startMs })
        for(w in windows) {
            assertEquals(altered.duration,w.sequence.duration)
            val expected=altered.frames.filter { it.timestamp.value in w.startMs until w.endExclusiveMs }
            assertEquals(expected,w.sequence.frames)
            expected.zip(w.sequence.frames).forEach { (a,b) -> assertSame(a,b) }
        }
        assertNull(windows.first().sequence.frames[10].selectedSubjectId)
        assertTrue(windows.first().sequence.frames[10].intrinsicDescriptor.featureConfidences.isEmpty())
    }

    @Test fun fewerThanThreeCyclesAndUnobservedCriticalFeaturesCannotBeRescued() {
        val seq=source().let { it.copy(frames=it.frames.filter { f -> f.timestamp.value<4200 }) }
        assertTrue(FixedReferenceWindowResearch.windows(seq).isEmpty())
        val missing=source().let { it.copy(frames=it.frames.map { f -> f.copy(intrinsicDescriptor=f.intrinsicDescriptor.copy(
            featureConfidences=f.intrinsicDescriptor.featureConfidences+("angle.left_shoulder" to .1))) }) }
        for(w in FixedReferenceWindowResearch.windows(missing))
            assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(w.sequence,"biceps_curl",data.identity))
    }

    @Test fun croppingCannotEvadeTheWholeReferenceIdentityGate() {
        val seq=source().let { it.copy(frames=it.frames.map { f ->
            if(f.timestamp.value<16000) f else f.copy(selectedSubjectId="another-person") }) }
        val full=QualifiedRepCompiler.compile(seq,"biceps_curl",data.identity)
        assertEquals(RepReferenceFailure.AMBIGUOUS_REFERENCE_SUBJECT,assertIs<QualifiedRepCompilation.Unavailable>(full).reason)
        assertFalse(FixedReferenceWindowResearch.mayScreen(full))
        assertFalse(FixedReferenceWindowResearch.mayScreen(QualifiedRepCompilation.Unavailable(RepReferenceFailure.MISSING_PROVENANCE,"missing")))
    }
}

package ai.senp.motion

import kotlin.test.*

class QualifiedRepEvidenceTest {
    private fun replay(kind: String, fps: Int = 15, exercise: String = "biceps_curl"): List<QualifiedRepOutput> {
        val reference = QualifiedRepEvidenceBenchmark.reference(exercise,QualifiedRepConfig(),kind.startsWith("phase_"))
        val engine = reference.newEngine()
        return QualifiedRepEvidenceBenchmark.samples(fps,kind).map { (time,p) ->
            engine.evaluate(QualifiedRepEvidenceBenchmark.frame(reference,time,p,kind),QualifiedRepTestData.identity.poseModelSha256)
        }
    }
    @Test fun anIncompatiblePoseCannotSupplyTheOnlyOutboundPathEvidence() {
        val events = replay("incompatible_outbound").flatMap { it.finalizedAttempts }
        assertEquals(RepVerdict.UNASSESSABLE,events.single().verdict)
        assertFalse(events.single().outboundPathObserved)
        assertTrue(events.single().returnPathObserved)
        assertEquals(setOf(RepReason.INSUFFICIENT_PATH_EVIDENCE),events.single().reasons)
    }
    @Test fun anIncompatiblePoseCannotSupplyTheOnlyReturnPathEvidence() {
        val event = replay("incompatible_return").flatMap { it.finalizedAttempts }.single()
        assertEquals(RepVerdict.UNASSESSABLE,event.verdict)
        assertTrue(event.outboundPathObserved)
        assertFalse(event.returnPathObserved)
    }
    @Test fun briefIncompatiblePathEvidenceDoesNotBecomeASustainedFault() {
        val outputs = replay("incompatible_both")
        assertTrue(outputs.all { it.newFaults.isEmpty() })
        assertEquals(0,outputs.last().counts.rejected)
        assertEquals(0,outputs.last().counts.qualified)
    }
    @Test fun compatibleSparseMotionStillCountsAcrossAllPoliciesAndCadences() {
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (fps in listOf(10,15,30,60))
            assertEquals(1,replay("compatible_sparse",fps,exercise).last().counts.qualified,"$exercise $fps")
    }
    @Test fun directlyObservedReturnGeometryDoesNotInheritOutboundAmbiguityDuringReversalDwell() {
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (fps in listOf(10,15,30,60)) {
            val result = replay("phase_correct",fps,exercise)
            assertEquals(1,result.last().counts.qualified,"$exercise $fps")
            assertTrue(result.all { it.newFaults.isEmpty() })
            assertEquals(0.0,result.flatMap { it.finalizedAttempts }.single().ambiguousFraction)
        }
    }
    @Test fun outboundGeometryOnTheReturnStillCannotEarnAQualifiedCount() {
        for (fps in listOf(10,15,30,60)) assertEquals(0,replay("phase_wrong_return",fps).last().counts.qualified)
    }
    @Test fun provisionalReturnDoesNotConfirmTheTurnaroundOrReadFutureFrames() {
        val ref = QualifiedRepEvidenceBenchmark.reference("biceps_curl",QualifiedRepConfig(),true)
        val a = ref.newEngine();val b = ref.newEngine()
        for ((time,p) in QualifiedRepEvidenceBenchmark.samples(15,"phase_correct").takeWhile { it.first <= 1067 }) {
            val frame = QualifiedRepEvidenceBenchmark.frame(ref,time,p,"phase_correct")
            assertEquals(a.evaluate(frame,QualifiedRepTestData.identity.poseModelSha256),b.evaluate(frame,QualifiedRepTestData.identity.poseModelSha256))
        }
        assertEquals(0,a.finish(1100).counts.qualified)
    }
}

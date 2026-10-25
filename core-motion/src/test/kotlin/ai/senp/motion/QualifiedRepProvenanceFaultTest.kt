package ai.senp.motion

import kotlin.test.*

/** Provenance admission and fault locations must refer to the observed source, including mirrored input. */
class QualifiedRepProvenanceFaultTest {
    private val data = QualifiedRepTestData

    @Test fun unidentifiedStreamsCannotArmOrCountEvenWithCompleteConfidentAngles() {
        for (id in QualifiedRepCompiler.supportedExerciseIds) for (subject in listOf(null,""," ","\t\r\n")) {
            val engine = data.reference(id).newEngine()
            val outputs = data.stream().map { (time,p) ->
                engine.evaluate(data.frame(time,p,id).copy(selectedSubjectId = subject),data.identity.poseModelSha256)
            }
            assertTrue(outputs.all { it.stage == RepStage.UNAVAILABLE && it.unavailableReason == RepReason.MISSING_CRITICAL_EVIDENCE },"$id subject=[$subject]")
            assertTrue(outputs.all { it.activeAttemptId == null && it.newFaults.isEmpty() && it.finalizedAttempts.isEmpty() })
            assertEquals(QualifiedRepCounts(),engine.finish(2100).counts)
        }
    }

    @Test fun losingIdentityFinalizesOnceAndRequiresAnEntireNewObservedCycle() {
        for (subject in listOf(null,""," ","\t\r\n")) {
            val engine = data.reference().newEngine()
            val outputs = data.stream().map { (time,p) ->
                val frame = data.frame(time,p).let { if (time in 700..800) it.copy(selectedSubjectId = subject) else it }
                engine.evaluate(frame,data.identity.poseModelSha256)
            }
            val interrupted = outputs.flatMap { it.finalizedAttempts }.single()
            assertEquals(RepVerdict.UNASSESSABLE,interrupted.verdict)
            assertTrue(RepReason.CONTEXT_CHANGED in interrupted.reasons)
            assertTrue(outputs.filter { it.timestampMs in 700..800 }.all { it.unavailableReason == RepReason.MISSING_CRITICAL_EVIDENCE })
            assertEquals(0,outputs.last().counts.qualified)
            val recovery = data.stream(offset = 2100).map { (time,p) ->
                engine.evaluate(data.frame(time,p),data.identity.poseModelSha256)
            }
            assertEquals(1,recovery.last().counts.qualified)
            assertEquals(2,recovery.last().counts.finalized)
        }
    }

    @Test fun earlyReversalIdentifiesTheMeasuredArmOrLegRatherThanTheReferenceSide() {
        for (id in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries)
            for (mirror in listOf(ActionMirrorMode.DIRECT,ActionMirrorMode.MIRRORED)) for (fps in listOf(10,15,30,60)) {
                val reference = data.reference(id,QualifiedRepConfig(side = side,mirrorMode = mirror))
                val observedSide = if (mirror == ActionMirrorMode.DIRECT) side else if (side == RepObservedSide.LEFT) RepObservedSide.RIGHT else RepObservedSide.LEFT
                val sourcePrefix = "angle.${observedSide.name.lowercase()}_"
                val engine = reference.newEngine()
                // Only the measured limb makes a shallow movement. The other limb completes the full range.
                val outputs = data.stream(fps).map { (time,p) ->
                    val shallow = data.features(id,p*.45).filterKeys { it.startsWith(sourcePrefix) }
                    engine.evaluate(data.frame(time,p,id,shallow),data.identity.poseModelSha256)
                }
                val fault = outputs.flatMap { it.newFaults }.single { it.reason == RepReason.EARLY_REVERSAL }
                val expectedFeature = side.observedFeature(reference.driverFeature,mirror)
                assertEquals(expectedFeature,fault.feature,"$id $side $mirror $fps")
                assertTrue(assertNotNull(fault.feature).startsWith(sourcePrefix))
                assertTrue(fault.firstEvidenceMs > 1100)
                assertTrue(fault.confirmedMs >= fault.firstEvidenceMs+reference.config.reversalConfirmationMs)
                val attempt = outputs.flatMap { it.finalizedAttempts }.single()
                assertEquals(RepVerdict.REJECTED,attempt.verdict)
                assertEquals(fault,attempt.faults.single { it.reason == RepReason.EARLY_REVERSAL })
                assertEquals(0,outputs.last().counts.qualified)
            }
    }
}

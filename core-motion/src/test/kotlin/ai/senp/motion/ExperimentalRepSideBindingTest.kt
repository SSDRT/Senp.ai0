package ai.senp.motion

import kotlin.test.*

class ExperimentalRepSideBindingTest {
    private val data = QualifiedRepTestData
    private val exercise = "lateral_raise"
    private fun probe() = ExperimentalRepSideBinding(data.reference(exercise,QualifiedRepConfig(side = RepObservedSide.RIGHT)))
    private fun source(t: Long, left: Double, right: Double): SpatialObservationFrame {
        val values = data.features(exercise,left).filterKeys { "left_" in it } +
            data.features(exercise,right).filterKeys { "right_" in it }
        return data.frame(t,0.0,exercise,values)
    }
    private fun replay(side: RepObservedSide, alter: (SpatialObservationFrame) -> SpatialObservationFrame = { it }): List<ExperimentalRepSideBinding.Result> {
        val probe = probe()
        return data.stream().map { (t,p) -> probe.evaluate(alter(source(t,if (side == RepObservedSide.LEFT) p else 0.0,
            if (side == RepObservedSide.RIGHT) p else 0.0)),data.identity.poseModelSha256,data.identity.preprocessingId,"camera") }
    }

    @Test fun eitherDeclaredInterchangeableArmLocksBeforeTurnaroundAndCountsOnce() {
        for (side in RepObservedSide.entries) {
            val rows = replay(side)
            val first = rows.first { it.state == ExperimentalRepSideBinding.State.LOCKED }
            assertEquals(side,first.side)
            assertTrue(requireNotNull(first.lockedMs) in 420L..600L)
            assertEquals(1,rows.last().output?.counts?.qualified)
            assertTrue(rows.filter { it.state == ExperimentalRepSideBinding.State.WAITING }.all { it.output == null })
            assertTrue(rows.filter { it.side != null }.all { it.side == side && it.lockedMs == first.lockedMs })
        }
    }

    @Test fun driverBindingDoesNotChooseTheBetterFormOrUseTheCompanionFeature() {
        for (side in RepObservedSide.entries) {
            val normal = replay(side)
            val activeFault = replay(side) { f -> f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
                values = f.intrinsicDescriptor.values + ("angle.${side.name.lowercase()}_elbow" to 70.0))) }
            assertEquals(normal.map { it.side to it.lockedMs },activeFault.map { it.side to it.lockedMs })
            assertEquals(0,activeFault.last().output?.counts?.qualified)
            assertEquals(1,activeFault.last().output?.counts?.rejected)
        }
    }

    @Test fun bilateralMotionMissingOppositeEvidenceAndStartupInMotionCannotChooseAnArm() {
        for (kind in listOf("bilateral","missing","low-confidence","mid-rep")) {
            val probe = probe()
            val rows = data.stream().filter { kind != "mid-rep" || it.first in 500..1500 }.map { (t,p) ->
                val raw = source(t,p,if (kind == "bilateral") p else 0.0)
                val f = when (kind) {
                    "missing" -> raw.copy(intrinsicDescriptor = raw.intrinsicDescriptor.copy(
                        values = raw.intrinsicDescriptor.values-filterSet,
                        featureConfidences = raw.intrinsicDescriptor.featureConfidences-filterSet))
                    "low-confidence" -> raw.copy(intrinsicDescriptor = raw.intrinsicDescriptor.copy(
                        featureConfidences = raw.intrinsicDescriptor.featureConfidences + ("angle.right_shoulder" to .2)))
                    else -> raw
                }
                probe.evaluate(f,data.identity.poseModelSha256,data.identity.preprocessingId,null)
            }
            assertTrue(rows.none { it.side != null },kind)
            assertNull(probe.finish(2100),kind)
        }
    }
    private val filterSet = setOf("angle.right_shoulder")

    @Test fun sideStaysLockedAcrossOppositeArmMotionAndCriticalEvidenceLoss() {
        val probe = probe(); val outputs = mutableListOf<ExperimentalRepSideBinding.Result>()
        for ((t,p) in data.stream()) outputs += probe.evaluate(source(t,p,0.0),data.identity.poseModelSha256,data.identity.preprocessingId,null)
        for ((t,p) in data.stream(offset = 2100)) outputs += probe.evaluate(source(t,0.0,p),data.identity.poseModelSha256,data.identity.preprocessingId,null)
        assertTrue(outputs.filter { it.side != null }.all { it.side == RepObservedSide.LEFT })
        assertEquals(1,outputs.last().output?.counts?.qualified)
        val invalid = source(4200,0.0,0.0).copy(selectedSubjectId = null)
        assertEquals(RepReason.MISSING_CRITICAL_EVIDENCE,probe.evaluate(invalid,data.identity.poseModelSha256,data.identity.preprocessingId,null).output?.unavailableReason)
        probe.reset()
        val rows = data.stream().map { (t,p) -> probe.evaluate(source(t,0.0,p),data.identity.poseModelSha256,data.identity.preprocessingId,null) }
        assertEquals(RepObservedSide.RIGHT,rows.last().side)
        assertEquals(1,rows.last().output?.counts?.qualified)
    }

    @Test fun prefixCausalityAndMappingKeysAreStable() {
        val frames = data.stream().map { (t,p) -> source(t,p,0.0) }
        fun run(input: List<SpatialObservationFrame>): List<ExperimentalRepSideBinding.Result> {
            val probe = probe()
            return input.map { probe.evaluate(it,data.identity.poseModelSha256,data.identity.preprocessingId,null) }
        }
        val full = run(frames)
        for (size in listOf(5,15,30,45)) assertEquals(full.take(size),run(frames.take(size)))
        val leftKey = full.last().output?.referenceKey
        val rightKey = replay(RepObservedSide.RIGHT).last().output?.referenceKey
        assertNotEquals(leftKey,rightKey)
    }

    @Test fun preBindingGapsContextAndProvenanceFailuresBlockRatherThanSelectLater() {
        for (kind in listOf("gap","subject","segment","camera","model","pipeline")) {
            val probe = probe()
            probe.evaluate(source(0,0.0,0.0),data.identity.poseModelSha256,data.identity.preprocessingId,"a")
            val frame = source(if (kind == "gap") 500 else 100,0.0,0.0).let { when (kind) {
                "subject" -> it.copy(selectedSubjectId = "other")
                "segment" -> it.copy(spatialSegmentId = 7)
                else -> it
            } }
            val result = probe.evaluate(frame,if (kind == "model") "wrong" else data.identity.poseModelSha256,
                if (kind == "pipeline") "wrong" else data.identity.preprocessingId,if (kind == "camera") "b" else "a")
            assertEquals(ExperimentalRepSideBinding.State.BLOCKED,result.state,kind)
            assertEquals(ExperimentalRepSideBinding.State.BLOCKED,probe.evaluate(source(1000,.5,0.0),data.identity.poseModelSha256,data.identity.preprocessingId,"a").state)
        }
    }

    @Test fun singleSpikeOrFastTurnaroundCannotLockAndNonIncreasingTimestampsAreRejected() {
        for (peak in listOf(.3,1.0)) {
            val probe = probe()
            val rows = listOf(0.0,0.0,0.0,peak,0.0,0.0).mapIndexed { i,p ->
                probe.evaluate(source(i*67L,p,0.0),data.identity.poseModelSha256,data.identity.preprocessingId,null)
            }
            assertTrue(rows.none { it.side != null })
            assertEquals(ExperimentalRepSideBinding.State.BLOCKED,rows.last().state)
            assertFailsWith<IllegalArgumentException> { probe.evaluate(source(0,0.0,0.0),data.identity.poseModelSha256,data.identity.preprocessingId,null) }
        }
        assertFailsWith<IllegalArgumentException> { ExperimentalRepSideBinding(data.reference("bodyweight_squat")) }
    }
}

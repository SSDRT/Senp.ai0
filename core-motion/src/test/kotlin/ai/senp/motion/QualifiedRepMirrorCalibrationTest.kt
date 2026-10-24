package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

/** Unilateral angular controls expose reference-vs-source orientation mistakes. */
class QualifiedRepMirrorCalibrationTest {
    private val data = QualifiedRepTestData
    private fun sequence(exercise: String, side: RepObservedSide): SpatialSequenceAnalysis {
        val prefix = "angle.${side.name.lowercase()}_"
        val frames = (0 until 4).flatMap { rep -> data.stream(offset = rep*2100L).map { (t,p) ->
            val frame = data.frame(t,p,exercise)
            frame.copy(intrinsicDescriptor = frame.intrinsicDescriptor.copy(
                values = frame.intrinsicDescriptor.values.filterKeys { it.startsWith(prefix) },
                featureConfidences = frame.intrinsicDescriptor.featureConfidences.filterKeys { it.startsWith(prefix) }))
        } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
    }
    private fun reference(exercise: String = "biceps_curl", side: RepObservedSide = RepObservedSide.LEFT,
        mirror: ActionMirrorMode = ActionMirrorMode.MIRRORED): QualifiedRepReference = assertIs<QualifiedRepCompilation.Ready>(
        QualifiedRepCompiler.compile(sequence(exercise,side),exercise,data.identity,QualifiedRepConfig(side = side,mirrorMode = mirror))).reference

    @Test fun mirroredReferenceCalibrationUsesOnlyTheOriginalDemonstratedSideForEveryPolicy() {
        for (exercise in QualifiedRepCompiler.supportedExerciseIds) for (side in RepObservedSide.entries) {
            val direct = reference(exercise,side,ActionMirrorMode.DIRECT)
            val mirrored = reference(exercise,side)
            assertEquals(4,mirrored.calibration?.acceptedSelfCycles,"$exercise $side")
            assertEquals(direct.profile,mirrored.profile)
            assertEquals(direct.calibration,mirrored.calibration)
            assertEquals(direct.driverEnvelope,mirrored.driverEnvelope)
            assertNotEquals(direct.referenceKey,mirrored.referenceKey)
            assertEquals(ActionMirrorMode.MIRRORED,mirrored.config.mirrorMode)
        }
    }

    @Test fun mirrorMappingIsStillAppliedToTheLiveSourceAndItsFaultFeature() {
        val ref = reference()
        for (bad in listOf(false,true)) {
            val engine = ref.newEngine();val faults = mutableListOf<RepFault>()
            var last: QualifiedRepOutput? = null
            for ((t,p) in data.stream()) {
                val source = data.frame(t,p,changes = mapOf("angle.left_elbow" to 170.0,
                    "angle.right_shoulder" to if (bad && p>.2) 85.0 else 15.0))
                last = engine.evaluate(source,data.identity.poseModelSha256)
                faults += last.newFaults
            }
            assertEquals(if (bad) 0 else 1,requireNotNull(last).counts.qualified)
            assertEquals(RepObservedSide.RIGHT,last.observedSide)
            if (bad) assertTrue(faults.any { it.feature == "angle.right_shoulder" && it.reason == RepReason.CLEAR_FORM_DEVIATION })
        }
        val wrongSide = ref.newEngine()
        val output = data.stream().map { (t,p) -> wrongSide.evaluate(data.frame(t,p,
            changes = mapOf("angle.right_elbow" to 170.0)),data.identity.poseModelSha256) }.last()
        assertEquals(0,output.counts.qualified)
    }

    @Test fun mirrorCalibrationCannotBorrowMissingReferenceFeaturesOrAcceptWrongRuntimeProvenance() {
        val source = sequence("biceps_curl",RepObservedSide.LEFT)
        val missing = source.copy(frames = source.frames.map { f -> f.copy(intrinsicDescriptor = f.intrinsicDescriptor.copy(
            values = f.intrinsicDescriptor.values-"angle.left_shoulder",
            featureConfidences = f.intrinsicDescriptor.featureConfidences-"angle.left_shoulder")) })
        assertIs<QualifiedRepCompilation.Unavailable>(QualifiedRepCompiler.compile(missing,"biceps_curl",data.identity,
            QualifiedRepConfig(mirrorMode = ActionMirrorMode.MIRRORED)))
        val ref = reference()
        val first = data.frame(0,0.0)
        assertEquals(RepReason.MODEL_MISMATCH,ref.newEngine().evaluate(first,"c".repeat(64)).unavailableReason)
        assertEquals(RepReason.PREPROCESSING_MISMATCH,ref.newEngine().evaluate(first,data.identity.poseModelSha256,"other/1").unavailableReason)
    }
}

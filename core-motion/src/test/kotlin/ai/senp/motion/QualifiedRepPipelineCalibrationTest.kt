package ai.senp.motion

import ai.senp.core.contracts.*
import kotlin.test.*

class QualifiedRepPipelineCalibrationTest {
    private fun sequence(): SpatialSequenceAnalysis {
        val frames = (0 until 4).flatMap { rep -> QualifiedRepTestData.stream(offset = rep*2100L).map { (time,p) ->
            QualifiedRepTestData.frame(time,p)
        } }
        return SpatialSequenceAnalysis(VideoRole.REFERENCE,DurationMs(frames.last().timestamp.value+1),
            ObservationSampling(analysisFramesPerSecond = 30.0),frames,1.0)
    }
    @Test fun selfReconstructionUsesTheDeclaredMeasurementPipeline() {
        val fingerprint = QualifiedRepTestData.identity.copy(preprocessingId = "observed-test-geometry/1")
        val reference = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(sequence(),"biceps_curl",fingerprint)).reference
        assertEquals(4,reference.calibration?.acceptedSelfCycles)
        val engine = reference.newEngine()
        sequence().frames.forEach { frame -> engine.evaluate(frame,fingerprint.poseModelSha256,fingerprint.preprocessingId) }
        assertEquals(4,engine.finish(sequence().duration.value).counts.qualified)
    }
    @Test fun usingAValidCustomPipelineDoesNotPermitRawOrOtherPipelinesAtRuntime() {
        val fingerprint = QualifiedRepTestData.identity.copy(preprocessingId = "observed-test-geometry/1")
        val reference = assertIs<QualifiedRepCompilation.Ready>(QualifiedRepCompiler.compile(sequence(),"biceps_curl",fingerprint)).reference
        for (wrong in listOf(RepEvidenceFingerprint.RAW_WORLD_PIPELINE,"other/1")) {
            val engine = reference.newEngine()
            val result = engine.evaluate(sequence().frames.first(),fingerprint.poseModelSha256,wrong)
            assertEquals(RepReason.PREPROCESSING_MISMATCH,result.unavailableReason)
            assertEquals(0,result.counts.qualified)
        }
    }
}

package ai.senp.pose.mediapipe

import ai.senp.pose.PoseFailure
import ai.senp.pose.PoseInputFrame
import ai.senp.pose.PoseOutcome
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipePoseEstimatorInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun missingModelAssetIsTyped() {
        assertThrows(PoseFailure.ModelLoad::class.java) {
            MediaPipePoseEstimator.create(context, PoseModelSource.Asset("missing-model.task"))
        }
    }

    @Test
    fun blankFrameProducesNoPersonAndDuplicateTimestampIsRejected() {
        MediaPipePoseEstimator.create(
            context = context,
            source = PoseModelSource.Asset("pose_landmarker_full.task"),
            descriptor = PoseModelDescriptor(sha256 = EXPECTED_MODEL_SHA256),
        ).use { estimator ->
            val pixels = IntArray(256 * 256) { 0xff000000.toInt() }
            val frame = PoseInputFrame(0L, 256, 256, pixels)
            val outcome = estimator.estimate(frame)
            assertTrue("Expected no person for a solid black frame, got $outcome", outcome is PoseOutcome.NoPerson)
            assertEquals(0L, outcome.timestampMs)
            assertThrows(PoseFailure.NonMonotonicTimestamp::class.java) {
                estimator.estimate(frame)
            }
        }
    }

    companion object {
        private const val EXPECTED_MODEL_SHA256 =
            "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    }
}

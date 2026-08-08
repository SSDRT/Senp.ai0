package ai.senp.pose.mediapipe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPipePoseEstimatorInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test fun packagedModelMatchesPinnedArtifact() {
        val actual = context.assets.open(MODEL_ASSET).use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                bytes += read
            }
            bytes to digest.digest().joinToString("") { "%02x".format(it) }
        }
        assertEquals(EXPECTED_MODEL_BYTES, actual.first)
        assertEquals(EXPECTED_MODEL_SHA256, actual.second)
    }

    @Test fun missingOrMismatchedModelIsTypedInternally() {
        assertThrows(MediaPipeAdapterException.ModelLoad::class.java) {
            MediaPipePoseEstimator.create(context, "missing.task", EXPECTED_MODEL_SHA256)
        }
        assertThrows(MediaPipeAdapterException.ModelLoad::class.java) {
            MediaPipePoseEstimator.create(context, MODEL_ASSET, "0".repeat(64))
        }
    }

    @Test fun blankFrameProducesNoPersonAndRejectsDuplicateTimestamp() {
        MediaPipePoseEstimator.create(context, MODEL_ASSET, EXPECTED_MODEL_SHA256).use { estimator ->
            val pixels = IntArray(256 * 256) { 0xff000000.toInt() }
            val outcome = estimator.estimate(0, 0, 256, 256, pixels)
            assertEquals(TrackingState.NO_PERSON, outcome.state)
            assertThrows(MediaPipeAdapterException.NonMonotonicTimestamp::class.java) {
                estimator.estimate(0, 1, 256, 256, pixels)
            }
        }
    }

    companion object {
        private const val MODEL_ASSET = "pose_landmarker_full.task"
        private const val EXPECTED_MODEL_BYTES = 9_398_198L
        private const val EXPECTED_MODEL_SHA256 = "5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1"
    }
}

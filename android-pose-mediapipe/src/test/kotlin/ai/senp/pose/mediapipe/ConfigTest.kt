package ai.senp.pose.mediapipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConfigTest {
    @Test
    fun detectionPresenceAndTrackingAreIndependentSettings() {
        val config = MediaPipePoseEstimator.Config(
            detectionConfidence = 0.4f,
            presenceConfidence = 0.5f,
            trackingConfidence = 0.6f,
        )
        assertEquals(0.4f, config.detectionConfidence)
        assertEquals(0.5f, config.presenceConfidence)
        assertEquals(0.6f, config.trackingConfidence)
    }

    @Test
    fun rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaPipePoseEstimator.Config(detectionConfidence = 1.1f)
        }
    }
}

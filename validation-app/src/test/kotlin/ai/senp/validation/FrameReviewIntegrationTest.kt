package ai.senp.validation

import ai.senp.core.contracts.TimestampMs
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.review.ReviewModels
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameReviewIntegrationTest {
    @Test
    fun `production frame review is pinned to Luna`() {
        assertEquals(ReviewModels.LUNA_5_6, LUNA_FRAME_REVIEW_MODEL.id)
    }

    @Test
    fun `review selection preserves existing persistent deviation ranking`() {
        val deviations = listOf(
            deviation(timestampMs = 100, normalized = 0.8, confidence = 0.5, persistent = true, feature = "angle.elbow"),
            deviation(timestampMs = 200, normalized = 1.1, confidence = 0.9, persistent = true, feature = "angle.knee"),
            deviation(timestampMs = 100, normalized = 1.0, confidence = 0.8, persistent = true, feature = "ratio.torso"),
            deviation(timestampMs = 300, normalized = 2.0, confidence = 1.0, persistent = false, feature = "angle.hip"),
            deviation(timestampMs = 400, normalized = 0.7, confidence = 0.9, persistent = true, feature = "angle.shoulder"),
        )

        val selected = selectFrameReviewDeviations(deviations, maximumFrames = 3)

        assertEquals(listOf(200L, 100L, 400L), selected.map { it.timestamp.value })
        assertEquals(listOf("angle.knee", "ratio.torso", "angle.shoulder"), selected.map { it.feature })
    }

    private fun deviation(
        timestampMs: Long,
        normalized: Double,
        confidence: Double,
        persistent: Boolean,
        feature: String,
    ) = ReferenceDeviationMeasurement(
        timestamp = TimestampMs(timestampMs),
        stateId = "state-1",
        feature = feature,
        referenceRange = 0.0..1.0,
        referenceMedian = 0.5,
        userValue = 1.2,
        signedDeltaOutsideRange = 0.2,
        normalizedDeviation = normalized,
        confidence = confidence,
        persistenceCandidate = persistent,
    )
}

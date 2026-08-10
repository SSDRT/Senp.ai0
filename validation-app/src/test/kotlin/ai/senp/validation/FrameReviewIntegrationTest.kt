package ai.senp.validation

import ai.senp.core.contracts.TimestampMs
import ai.senp.motion.ReferenceDeviationMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameReviewIntegrationTest {
    @Test
    fun `AI review formatting keeps issue reference and cue in existing review slot`() {
        val text = formatAiReview(
            GeminiAnalysisResult(
                exercise = "pull-up",
                summary = "The user is close to the reference but loses control near the top.",
                overallScore = 78.0,
                confidence = 0.91,
                repCount = 1,
                problems = listOf(
                    GeminiProblem(
                        title = "Top range",
                        userStartMs = 800,
                        userEndMs = 1_200,
                        referenceStartMs = 900,
                        referenceEndMs = 1_300,
                        phase = "concentric",
                        bodyRegion = "upper body",
                        severity = GeminiSeverity.MEDIUM,
                        confidence = 0.9,
                        observedIssue = "The user stops short at the top.",
                        referenceBehavior = "The reference pulls the chest higher.",
                        cue = "Pull the chest toward the bar.",
                    ),
                ),
            ),
        )

        assertTrue(text.contains("Issue: The user stops short at the top."))
        assertTrue(text.contains("Reference: The reference pulls the chest higher."))
        assertTrue(text.contains("Cue: Pull the chest toward the bar."))
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

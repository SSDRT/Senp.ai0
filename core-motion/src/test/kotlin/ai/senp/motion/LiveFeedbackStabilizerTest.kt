package ai.senp.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveFeedbackStabilizerTest {
    @Test
    fun `candidate must persist before surfacing and one frame spike is suppressed`() {
        val stabilizer = LiveFeedbackStabilizer()

        assertNull(stabilizer.update(0, listOf(observation(0, "low", confidence = 0.40))).primary)
        assertNull(stabilizer.update(67, listOf(observation(67, "spike", priority = 3))).primary)
        assertNull(stabilizer.update(134, listOf(observation(134, "steady"))).primary)
        assertNull(stabilizer.update(201, listOf(observation(201, "steady"))).primary)
        assertNull(stabilizer.update(268, listOf(observation(268, "steady"))).primary)

        val surfaced = stabilizer.update(335, listOf(observation(335, "steady")))
        assertEquals("steady", surfaced.primary?.stableKey)
        assertTrue(surfaced.primary?.stableKey != "spike")
        assertEquals(201L, surfaced.timestampMs - 134L)
        assertTrue(surfaced.timestampMs - 134L < 250L)
    }

    @Test
    fun `persistent moderate confidence cue can surface above the live tracking floor`() {
        val stabilizer = LiveFeedbackStabilizer()
        val outputs = listOf(0L, 67L, 134L, 201L).map { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "moderate", confidence = 0.52)))
        }

        assertTrue(outputs.take(3).all { it.primary == null })
        assertEquals("moderate", outputs.last().primary?.stableKey)
    }

    @Test
    fun `confidence noise around threshold does not flicker a confirmed cue`() {
        val stabilizer = LiveFeedbackStabilizer()
        val confidences = listOf(0.62, 0.66, 0.57, 0.64, 0.61, 0.67, 0.56, 0.65, 0.60, 0.63)
        val outputs = confidences.mapIndexed { index, confidence ->
            val timestamp = index * 67L
            stabilizer.update(timestamp, listOf(observation(timestamp, "stable", confidence = confidence)))
        }

        val firstVisible = outputs.indexOfFirst { it.primary != null }
        assertTrue(firstVisible >= 0)
        assertTrue(outputs.drop(firstVisible).all { it.primary?.stableKey == "stable" })
    }

    @Test
    fun `alternating near tied issues do not swap primary`() {
        val stabilizer = LiveFeedbackStabilizer()
        val keys = mutableListOf<String?>()

        repeat(14) { index ->
            val timestamp = index * 67L
            val aConfidence = if (index % 2 == 0) 0.75 else 0.78
            val bConfidence = if (index % 2 == 0) 0.77 else 0.76
            val feedback = stabilizer.update(
                timestamp,
                listOf(
                    observation(timestamp, "alpha", confidence = aConfidence, severity = 0.82),
                    observation(timestamp, "beta", confidence = bConfidence, severity = 0.80),
                ),
            )
            keys += feedback.primary?.stableKey
        }

        val firstVisible = keys.indexOfFirst { it != null }
        assertTrue(firstVisible >= 0)
        assertEquals("alpha", keys[firstVisible])
        assertTrue(keys.drop(firstVisible).all { it == "alpha" })
    }

    @Test
    fun `higher priority persistent issue replaces weaker cue within sub second bound`() {
        val stabilizer = LiveFeedbackStabilizer()
        val baselineTimes = listOf(0L, 67L, 134L, 201L, 268L)
        baselineTimes.forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "baseline", confidence = 0.86, severity = 0.82)))
        }
        assertEquals("baseline", stabilizer.update(300, listOf(observation(300, "baseline", confidence = 0.86, severity = 0.82))).primary?.stableKey)

        val changeStartedAt = 335L
        val changeTimes = listOf(335L, 402L, 469L, 536L, 603L, 670L, 737L)
        var switchedAt: Long? = null
        changeTimes.forEach { timestamp ->
            val feedback = stabilizer.update(
                timestamp,
                listOf(
                    observation(timestamp, "baseline", confidence = 0.80, severity = 0.78),
                    observation(timestamp, "replacement", confidence = 0.84, severity = 0.88, priority = 1),
                ),
            )
            if (feedback.primary?.stableKey == "replacement" && switchedAt == null) switchedAt = timestamp
        }

        assertEquals(670L, switchedAt)
        val latencyMs = requireNotNull(switchedAt) - changeStartedAt
        println("LIVE_FEEDBACK_INTENDED_SWITCH_LATENCY_MS=$latencyMs")
        assertEquals(335L, latencyMs)
        assertTrue(latencyMs < 1_000L)
    }

    @Test
    fun `brief dropout and degraded tracking preserve a visible cue`() {
        val stabilizer = LiveFeedbackStabilizer()
        listOf(0L, 67L, 134L, 201L).forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")))
        }

        val dropout = stabilizer.update(268, emptyList(), trackingConfidence = 0.55)
        val recovered = stabilizer.update(335, listOf(observation(335, "alpha")), trackingConfidence = 0.90)

        assertEquals(LiveTrackingState.DEGRADED, dropout.trackingState)
        assertEquals("alpha", dropout.primary?.stableKey)
        assertEquals("alpha", recovered.primary?.stableKey)
    }

    @Test
    fun `full tracking loss holds briefly then clears and recovery reconfirms`() {
        val stabilizer = LiveFeedbackStabilizer()
        listOf(0L, 67L, 134L, 201L).forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")))
        }

        var latest = stabilizer.update(268, emptyList(), trackingConfidence = 0.0)
        assertEquals("alpha", latest.primary?.stableKey)
        listOf(335L, 402L, 469L, 536L, 603L, 670L).forEach { timestamp ->
            latest = stabilizer.update(timestamp, emptyList(), trackingConfidence = 0.0)
        }
        assertEquals(LiveTrackingState.LOST, latest.trackingState)
        assertEquals(LiveFeedbackUncertainty.HIGH, latest.uncertainty)
        assertNull(latest.primary)

        val recoveryTimes = listOf(737L, 804L, 871L, 938L)
        val recoveryOutputs = recoveryTimes.map { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")), trackingConfidence = 0.95)
        }
        assertTrue(recoveryOutputs.take(3).all { it.primary == null })
        assertEquals("alpha", recoveryOutputs.last().primary?.stableKey)
    }

    @Test
    fun `priority controls primary secondary ordering without action specific semantics`() {
        val stabilizer = LiveFeedbackStabilizer()
        val times = listOf(0L, 67L, 134L, 201L)
        var feedback: StableLiveFeedback? = null
        times.forEach { timestamp ->
            feedback = stabilizer.update(
                timestamp,
                listOf(
                    observation(timestamp, "high-strength", confidence = 0.95, severity = 0.95, priority = 0),
                    observation(timestamp, "top-priority", confidence = 0.72, severity = 0.72, priority = 2),
                    observation(timestamp, "mid-priority", confidence = 0.85, severity = 0.85, priority = 1),
                ),
            )
        }

        assertEquals("top-priority", feedback?.primary?.stableKey)
        assertEquals("mid-priority", feedback?.secondary?.stableKey)
    }

    @Test
    fun `stale cue clears after bounded release grace`() {
        val stabilizer = LiveFeedbackStabilizer()
        listOf(0L, 67L, 134L, 201L).forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")))
        }

        assertEquals("alpha", stabilizer.update(469, emptyList()).primary?.stableKey)
        assertNull(stabilizer.update(536, emptyList()).primary)
    }

    @Test
    fun `duplicate stable keys prefer newest evidence rather than replaying a stronger stale sample`() {
        val stabilizer = LiveFeedbackStabilizer()
        stabilizer.update(0, listOf(observation(0, "alpha", confidence = 0.95, severity = 0.95)))
        stabilizer.update(
            100,
            listOf(
                observation(0, "alpha", label = "stale", confidence = 0.99, severity = 1.0, priority = 5),
                observation(100, "alpha", label = "fresh", confidence = 0.75, severity = 0.75),
            ),
        )
        stabilizer.update(200, listOf(observation(200, "alpha", label = "fresh")))

        val surfaced = stabilizer.update(300, listOf(observation(300, "alpha", label = "fresh")))
        assertEquals("fresh", surfaced.primary?.label)
        assertEquals(300L, surfaced.primary?.lastObservedAtMs)
    }

    @Test
    fun `timestamps are strict and future observation timestamps are rejected`() {
        val stabilizer = LiveFeedbackStabilizer()
        stabilizer.update(100, listOf(observation(100, "alpha")))

        assertFailsWith<IllegalArgumentException> { stabilizer.update(100, emptyList()) }
        assertFailsWith<IllegalArgumentException> { stabilizer.update(99, emptyList()) }

        val fresh = LiveFeedbackStabilizer()
        assertFailsWith<IllegalArgumentException> {
            fresh.update(100, listOf(observation(101, "future")))
        }
        assertNull(fresh.update(100, listOf(observation(100, "retry"))).primary)
    }

    @Test
    fun `released cue requires fresh confirmation even when evidence freshness window is longer`() {
        val stabilizer = LiveFeedbackStabilizer(
            LiveFeedbackConfig(
                releaseGraceMs = 100L,
                trackingLossHoldMs = 200L,
                maximumEvidenceGapMs = 500L,
                candidateStaleMs = 700L,
            ),
        )
        listOf(0L, 67L, 134L, 201L).forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")))
        }

        assertNull(stabilizer.update(335, emptyList()).primary)
        assertNull(stabilizer.update(402, listOf(observation(402, "alpha"))).primary)
        assertNull(stabilizer.update(536, listOf(observation(536, "alpha"))).primary)
        assertEquals("alpha", stabilizer.update(603, listOf(observation(603, "alpha"))).primary?.stableKey)
    }

    @Test
    fun `tracking loss never re promotes a cue after its bounded hold expires`() {
        val stabilizer = LiveFeedbackStabilizer(
            LiveFeedbackConfig(
                releaseGraceMs = 100L,
                trackingLossHoldMs = 200L,
                maximumEvidenceGapMs = 500L,
                candidateStaleMs = 700L,
            ),
        )
        listOf(0L, 67L, 134L, 201L).forEach { timestamp ->
            stabilizer.update(timestamp, listOf(observation(timestamp, "alpha")))
        }
        assertEquals("alpha", stabilizer.update(268, emptyList(), trackingConfidence = 0.0).primary?.stableKey)

        val expired = stabilizer.update(402, emptyList(), trackingConfidence = 0.0)
        val stillLost = stabilizer.update(469, emptyList(), trackingConfidence = 0.0)

        assertNull(expired.primary)
        assertNull(stillLost.primary)
    }

    @Test
    fun `same stable key latches label so wording noise does not animate the UI`() {
        val stabilizer = LiveFeedbackStabilizer()
        val labels = listOf("First wording", "Second wording", "Third wording", "Fourth wording", "Fifth wording")
        var feedback: StableLiveFeedback? = null
        labels.forEachIndexed { index, label ->
            val timestamp = index * 67L
            feedback = stabilizer.update(timestamp, listOf(observation(timestamp, "alpha", label = label)))
        }

        assertEquals("Fourth wording", feedback?.primary?.label)
        val later = stabilizer.update(402, listOf(observation(402, "alpha", label = "Noisy new wording")))
        assertEquals("Fourth wording", later.primary?.label)
    }

    @Test
    fun `deterministic 15 fps noisy stream exceeds eighty percent no jitter target`() {
        val stabilizer = LiveFeedbackStabilizer()
        val displayedKeys = mutableListOf<String?>()
        val alphaConfidences = listOf(0.62, 0.66, 0.57, 0.64, 0.61, 0.67)
        val betaConfidences = listOf(0.65, 0.62, 0.66, 0.63, 0.64, 0.61)

        repeat(180) { index ->
            val timestamp = index * 67L
            val observations = buildList {
                if (index % 41 != 0) {
                    add(
                        observation(
                            timestamp,
                            "semantic-alpha",
                            label = if (index % 2 == 0) "Alpha wording A" else "Alpha wording B",
                            confidence = alphaConfidences[index % alphaConfidences.size],
                            severity = 0.82,
                        ),
                    )
                    add(
                        observation(
                            timestamp,
                            "near-tie-distractor",
                            confidence = betaConfidences[index % betaConfidences.size],
                            severity = 0.72,
                        ),
                    )
                }
                if (index % 37 == 11) {
                    add(observation(timestamp, "one-frame-spike", confidence = 0.98, severity = 1.0, priority = 3))
                }
            }
            displayedKeys += stabilizer.update(timestamp, observations).primary?.stableKey
        }

        val firstVisible = displayedKeys.indexOfFirst { it != null }
        assertTrue(firstVisible >= 0)
        val visible = displayedKeys.drop(firstVisible)
        val transitions = visible.zipWithNext()
        val unchanged = transitions.count { (before, after) -> before == after }
        val stabilityRatio = unchanged.toDouble() / transitions.size.toDouble()

        println(
            "LIVE_FEEDBACK_STABILITY ratio=$stabilityRatio unchanged=$unchanged transitions=${transitions.size} " +
                "frames=${displayedKeys.size} first_visible_frame=$firstVisible",
        )
        assertTrue(stabilityRatio >= 0.80, "stability ratio $stabilityRatio must be >= 0.80")
        assertTrue(visible.all { it == "semantic-alpha" })
    }

    private fun observation(
        timestampMs: Long,
        key: String,
        label: String = key,
        confidence: Double = 0.85,
        severity: Double = 0.80,
        priority: Int = 0,
    ): CoachingObservation = CoachingObservation(
        stableKey = key,
        label = label,
        confidence = confidence,
        severity = severity,
        timestampMs = timestampMs,
        priority = priority,
    )
}

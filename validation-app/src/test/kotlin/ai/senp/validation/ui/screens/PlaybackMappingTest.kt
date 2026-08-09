package ai.senp.validation.ui.screens

import ai.senp.core.contracts.MotionUnitCorrespondence
import ai.senp.core.contracts.TimestampCorrespondence
import ai.senp.core.contracts.TimestampMs
import ai.senp.core.contracts.UnmatchedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMappingTest {
    @Test
    fun `internal unmatched timestamp splits supported mapping spans`() {
        val mapping = playbackMapping(
            listOf(
                matchedUnit(
                    "source-1",
                    "reference-1",
                    matched(100, 1000),
                    matched(200, 1200),
                    unmatched(300),
                    matched(400, 1800),
                    matched(500, 2000),
                ),
            ),
        )

        assertEquals(1100L, mapping.sourceToReference(150))
        assertNull(mapping.sourceToReference(300))
        assertNull(mapping.sourceToReference(350))
        assertEquals(1900L, mapping.sourceToReference(450))
    }

    @Test
    fun `separate matched units do not interpolate across their unmatched gap`() {
        val mapping = playbackMapping(
            listOf(
                matchedUnit("source-1", "reference-1", matched(100, 1000), matched(200, 1200)),
                matchedUnit("source-2", "reference-2", matched(600, 2200), matched(700, 2400)),
            ),
        )

        assertEquals(1100L, mapping.sourceToReference(150))
        assertNull(mapping.sourceToReference(400))
        assertEquals(2300L, mapping.sourceToReference(650))
    }

    @Test
    fun `leading and trailing time outside explicit spans is unsupported`() {
        val mapping = playbackMapping(
            listOf(
                matchedUnit("source-1", "reference-1", matched(100, 1000), matched(200, 1200)),
            ),
        )

        assertNull(mapping.sourceToReference(99))
        assertEquals(1000L, mapping.sourceToReference(100))
        assertEquals(1200L, mapping.sourceToReference(200))
        assertNull(mapping.sourceToReference(201))
    }

    @Test
    fun `reverse mapping is bounded to the same explicit spans`() {
        val mapping = playbackMapping(
            listOf(
                matchedUnit(
                    "source-1",
                    "reference-1",
                    matched(100, 1000),
                    matched(200, 1200),
                    unmatched(300),
                    matched(400, 1800),
                    matched(500, 2000),
                ),
            ),
        )

        assertEquals(150L, mapping.referenceToSource(1100))
        assertNull(mapping.referenceToSource(1500))
        assertEquals(450L, mapping.referenceToSource(1900))
        assertNull(mapping.referenceToSource(999))
        assertNull(mapping.referenceToSource(2001))
    }

    @Test
    fun `reverse mapping refuses ambiguous reused reference spans`() {
        val mapping = playbackMapping(
            listOf(
                matchedUnit("source-1", "reference-1", matched(100, 1000), matched(200, 1200)),
                matchedUnit("source-2", "reference-1", matched(600, 1000), matched(700, 1200)),
            ),
        )

        assertNull(mapping.referenceToSource(1100))
    }

    private fun matchedUnit(
        sourceId: String,
        referenceId: String,
        vararg timeline: TimestampCorrespondence,
    ): MotionUnitCorrespondence.MatchedUnit = MotionUnitCorrespondence.MatchedUnit(
        sourceUnitId = sourceId,
        referenceUnitId = referenceId,
        timeline = timeline.toList(),
        decisionConfidence = 0.95,
        ambiguity = 0.05,
    )

    private fun matched(sourceMs: Long, referenceMs: Long): TimestampCorrespondence.Matched =
        TimestampCorrespondence.Matched(TimestampMs(sourceMs), TimestampMs(referenceMs), 0.95)

    private fun unmatched(sourceMs: Long): TimestampCorrespondence.UnmatchedSource =
        TimestampCorrespondence.UnmatchedSource(TimestampMs(sourceMs), UnmatchedReason.AMBIGUOUS, 0.95)
}

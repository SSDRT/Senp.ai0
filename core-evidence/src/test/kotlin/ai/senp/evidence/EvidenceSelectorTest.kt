package ai.senp.evidence

import ai.senp.core.contracts.AlignmentPoint
import ai.senp.core.contracts.AlignmentResult
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.core.contracts.ProblemWindow
import ai.senp.core.contracts.TimestampMs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvidenceSelectorTest {

    /** Source timestamps 0..900 mapped to a reference running 100 ms behind. */
    private val alignment = AlignmentResult(
        mode = "test",
        points = (0..9).map {
            AlignmentPoint(TimestampMs(it * 100L), TimestampMs(it * 100L + 100L), 0.05, 0.9)
        },
        aggregateConfidence = 0.9,
    )

    private fun window(
        startMs: Long,
        endExclusiveMs: Long,
        severity: Double,
        certainty: ProblemCertainty = ProblemCertainty.GENUINE,
        mapped: Boolean = true,
        label: String = "label",
    ) = ProblemWindow(
        sourceStart = TimestampMs(startMs),
        sourceEndExclusive = TimestampMs(endExclusiveMs),
        referenceStart = if (mapped) TimestampMs(startMs + 100L) else null,
        referenceEndExclusive = if (mapped) TimestampMs(endExclusiveMs + 100L) else null,
        label = label,
        metric = "left_knee_degrees",
        meanDeviation = 10.0,
        peakDeviation = 20.0,
        severity = severity,
        alignmentConfidence = 0.8,
        certainty = certainty,
    )

    @Test
    fun `confirmed problems outrank uncertain ones whatever the severity`() {
        val selected = EvidenceSelector.select(
            problems = listOf(
                window(0, 100, severity = 0.99, certainty = ProblemCertainty.UNCERTAIN, label = "uncertain"),
                window(200, 300, severity = 0.10, certainty = ProblemCertainty.GENUINE, label = "genuine"),
            ),
            alignment = alignment,
        )
        assertEquals(listOf("genuine", "uncertain"), selected.map { it.window.label })
        assertEquals(listOf(0, 1), selected.map { it.rank })
    }

    @Test
    fun `severity orders windows of the same certainty`() {
        val selected = EvidenceSelector.select(
            problems = listOf(
                window(0, 100, severity = 0.40, label = "low"),
                window(200, 300, severity = 0.90, label = "high"),
                window(400, 500, severity = 0.60, label = "middle"),
            ),
            alignment = alignment,
        )
        assertEquals(listOf("high", "middle", "low"), selected.map { it.window.label })
    }

    @Test
    fun `equal severities break on start time so the order is reproducible`() {
        val selected = EvidenceSelector.select(
            problems = listOf(
                window(500, 600, severity = 0.5, label = "later"),
                window(100, 200, severity = 0.5, label = "earlier"),
            ),
            alignment = alignment,
        )
        assertEquals(listOf("earlier", "later"), selected.map { it.window.label })
    }

    @Test
    fun `selection is capped`() {
        val problems = (0..5).map { window(it * 100L, it * 100L + 50L, severity = 0.5) }
        assertEquals(3, EvidenceSelector.select(problems, alignment).size)
        assertEquals(1, EvidenceSelector.select(problems, alignment, maxWindows = 1).size)
        assertEquals(6, EvidenceSelector.select(problems, alignment, maxWindows = 10).size)
    }

    @Test
    fun `each window asks for entry midpoint and exit`() {
        val evidence = EvidenceSelector.select(listOf(window(200, 601, severity = 0.5)), alignment).single()

        assertEquals(
            listOf(EvidenceMoment.ENTRY, EvidenceMoment.MIDPOINT, EvidenceMoment.EXIT),
            evidence.frames.map { it.moment },
        )
        assertEquals(listOf(200L, 400L, 600L), evidence.frames.map { it.sourceTimestamp.value })
    }

    @Test
    fun `timestamps snap to frames that actually have a pose`() {
        // Bounds land between sampled points; an overlay needs a frame the pose exists on, so
        // 250 and 459 must come back as sampled timestamps rather than as themselves.
        val evidence = EvidenceSelector.select(listOf(window(250, 460, severity = 0.5)), alignment).single()

        val sampled = alignment.points.map { it.sourceTimestamp.value }
        evidence.frames.forEach {
            assertTrue(it.sourceTimestamp.value in sampled, "${it.sourceTimestamp.value} is not a sampled frame")
        }
        assertEquals(listOf(300L, 400L), evidence.frames.map { it.sourceTimestamp.value })
    }

    @Test
    fun `a window covering one sampled frame asks for one frame`() {
        val evidence = EvidenceSelector.select(listOf(window(300, 301, severity = 0.5)), alignment).single()

        assertEquals(1, evidence.frames.size)
        assertEquals(EvidenceMoment.ENTRY, evidence.frames.single().moment)
        assertEquals(300L, evidence.frames.single().sourceTimestamp.value)
    }

    @Test
    fun `reference timestamps come from the alignment path`() {
        val evidence = EvidenceSelector.select(listOf(window(200, 601, severity = 0.5)), alignment).single()

        // The path runs 100 ms behind its source frame.
        assertEquals(listOf(300L, 500L, 700L), evidence.frames.map { it.referenceTimestamp?.value })
    }

    @Test
    fun `an unmapped window keeps its reference side empty`() {
        val evidence = EvidenceSelector.select(
            listOf(window(200, 601, severity = 0.5, mapped = false)),
            alignment,
        ).single()

        assertTrue(evidence.frames.isNotEmpty(), "the user side is still worth capturing")
        evidence.frames.forEach { assertNull(it.referenceTimestamp, "at ${it.sourceTimestamp.value}") }
    }

    @Test
    fun `no problems means nothing to capture`() {
        assertEquals(emptyList(), EvidenceSelector.select(emptyList(), alignment))
    }
}

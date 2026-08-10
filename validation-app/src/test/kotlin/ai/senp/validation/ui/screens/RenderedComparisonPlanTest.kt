package ai.senp.validation.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderedComparisonPlanTest {
    @Test
    fun `trusted spans are concatenated and gaps disappear from output`() {
        val mapping = PlaybackMapping(
            spans = listOf(
                PlaybackSpan(
                    listOf(
                        PlaybackPoint(100L, 1_000L),
                        PlaybackPoint(200L, 1_100L),
                        PlaybackPoint(300L, 1_200L),
                    ),
                ),
                PlaybackSpan(
                    listOf(
                        PlaybackPoint(700L, 2_000L),
                        PlaybackPoint(800L, 2_100L),
                    ),
                ),
            ),
        )

        val plan = mapping.renderedComparisonPlan()

        assertEquals(2, plan.segments.size)
        assertEquals(300L, plan.durationMs)
        assertEquals(0L, plan.segments[0].outputStartMs)
        assertEquals(200L, plan.segments[1].outputStartMs)
        assertEquals(700L, plan.segments[1].sourceStartMs)
    }

    @Test
    fun `smooth timing warp stays in one rendered segment`() {
        val mapping = PlaybackMapping(
            spans = listOf(
                PlaybackSpan(
                    listOf(
                        PlaybackPoint(0L, 0L),
                        PlaybackPoint(100L, 120L),
                        PlaybackPoint(200L, 240L),
                        PlaybackPoint(300L, 360L),
                    ),
                ),
            ),
        )

        val plan = mapping.renderedComparisonPlan(maximumLinearErrorMs = 10L)

        assertEquals(1, plan.segments.size)
        assertEquals(300L, plan.durationMs)
        assertEquals(1.2f, plan.segments.single().referenceSpeed, absoluteTolerance = 0.0001f)
    }

    @Test
    fun `nonlinear timing warp splits before visible drift accumulates`() {
        val mapping = PlaybackMapping(
            spans = listOf(
                PlaybackSpan(
                    listOf(
                        PlaybackPoint(0L, 0L),
                        PlaybackPoint(100L, 100L),
                        PlaybackPoint(200L, 200L),
                        PlaybackPoint(300L, 500L),
                        PlaybackPoint(400L, 800L),
                    ),
                ),
            ),
        )

        val plan = mapping.renderedComparisonPlan(maximumLinearErrorMs = 30L)

        assertTrue(plan.segments.size >= 2)
        assertEquals(400L, plan.durationMs)
        assertEquals(plan.segments.first().outputDurationMs, plan.segments.drop(1).first().outputStartMs)
    }

    @Test
    fun `reference plateaus are skipped instead of inventing duplicate motion`() {
        val mapping = PlaybackMapping(
            spans = listOf(
                PlaybackSpan(
                    listOf(
                        PlaybackPoint(0L, 1_000L),
                        PlaybackPoint(100L, 1_000L),
                        PlaybackPoint(200L, 1_100L),
                    ),
                ),
            ),
        )

        val plan = mapping.renderedComparisonPlan()

        assertEquals(1, plan.segments.size)
        assertEquals(100L, plan.durationMs)
        assertEquals(100L, plan.segments.single().sourceStartMs)
        assertEquals(1_000L, plan.segments.single().referenceStartMs)
    }
}

private fun assertEquals(
    expected: Float,
    actual: Float,
    absoluteTolerance: Float,
) {
    assertTrue(kotlin.math.abs(expected - actual) <= absoluteTolerance, "expected=$expected actual=$actual")
}

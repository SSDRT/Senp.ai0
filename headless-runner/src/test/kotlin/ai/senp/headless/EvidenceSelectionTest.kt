package ai.senp.headless

import ai.senp.core.cache.InMemoryAnalysisCache
import ai.senp.core.contracts.AnalysisOutcome
import ai.senp.core.contracts.AnalysisResult
import ai.senp.core.contracts.ProblemCertainty
import ai.senp.evidence.EvidenceSelector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Evidence selection against a real pipeline run rather than a hand-built payload.
 *
 * The adapters are fakes, but the orchestration, contract invariants and serialization are the
 * production ones, so this is the closest check available without a decoder, model or device.
 */
class EvidenceSelectionTest {

    private fun analyse(): AnalysisResult = runBlocking {
        assertIs<AnalysisOutcome.Success>(
            samplePipeline(InMemoryAnalysisCache()).analyze(sampleRequest()),
        ).result
    }

    private fun AnalysisResult.evidence() = EvidenceSelector.select(payload.problems, payload.alignment)

    @Test
    fun `the most important confirmed windows are chosen from a pipeline result`() {
        val result = analyse()
        assertEquals(4, result.payload.problems.size, "the fake aligner should offer more than the cap")

        val evidence = result.evidence()
        assertEquals(
            listOf("knee_collapse", "hip_shift", "shoulder_rise"),
            evidence.map { it.window.label },
        )
        assertTrue(
            evidence.none { it.window.certainty == ProblemCertainty.UNCERTAIN },
            "an uncertain window should not displace a confirmed one",
        )
    }

    @Test
    fun `captured frames sit on sampled poses inside their own window`() {
        val result = analyse()
        val sampled = result.payload.alignment.points.map { it.sourceTimestamp.value }

        result.evidence().forEach { evidence ->
            assertTrue(evidence.frames.isNotEmpty(), "${evidence.window.label} selected no frames")
            evidence.frames.forEach { frame ->
                val at = frame.sourceTimestamp
                assertTrue(at.value in sampled, "${evidence.window.label}: $at has no pose")
                assertTrue(at >= evidence.window.sourceStart, "${evidence.window.label}: $at precedes the window")
                assertTrue(at < evidence.window.sourceEndExclusive, "${evidence.window.label}: $at overruns the window")
            }
        }
    }

    @Test
    fun `an unmapped window still yields user side frames`() {
        val evidence = analyse().evidence().single { it.window.label == "shoulder_rise" }

        assertTrue(evidence.frames.isNotEmpty())
        evidence.frames.forEach { assertNull(it.referenceTimestamp, "at ${it.sourceTimestamp.value}") }
    }

    @Test
    fun `mapped windows carry a reference frame for every capture`() {
        analyse().evidence()
            .filter { it.window.referenceStart != null }
            .forEach { evidence ->
                evidence.frames.forEach {
                    assertTrue(
                        it.referenceTimestamp != null,
                        "${evidence.window.label}: ${it.sourceTimestamp.value} lost its reference",
                    )
                }
            }
    }

    @Test
    fun `selection is stable across identical runs`() {
        fun shape() = analyse().evidence().map { it.window.label to it.frames.map { f -> f.sourceTimestamp.value } }
        assertEquals(shape(), shape())
    }
}

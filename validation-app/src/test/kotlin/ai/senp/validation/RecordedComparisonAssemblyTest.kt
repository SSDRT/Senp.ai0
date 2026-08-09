package ai.senp.validation

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.PipelineStageId
import ai.senp.sync.v2.VideoSynchronizationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class RecordedComparisonAssemblyTest {
    @Test
    fun `sync failure preserves completed generic action result`() {
        val actionResult = "independent-action-result"
        val failure = AnalysisFailure.Alignment("whole-video correspondence unavailable")

        val assembled = assembleRecordedComparison(
            actionResult = actionResult,
            synchronization = VideoSynchronizationOutcome.Failure(failure),
        )

        assertEquals(actionResult, assembled.actionResult)
        assertEquals(failure, assembled.synchronizationFailure)
        assertNull(assembled.synchronizationRun)
    }

    @Test
    fun `sync exception becomes optional alignment metadata without erasing action result`() = runBlocking {
        val actionResult = "independent-action-result"

        val assembled = assembleRecordedComparisonCatching(actionResult) {
            error("sync implementation exploded")
        }

        assertEquals(actionResult, assembled.actionResult)
        assertNull(assembled.synchronizationRun)
        val failure = assertIs<AnalysisFailure.Unexpected>(assembled.synchronizationFailure)
        assertEquals(PipelineStageId.ALIGNMENT, failure.stage)
        assertEquals("sync implementation exploded", failure.message)
    }
    @Test
    fun `generic action exception leaves sync fallback available`() {
        val attempt = analyzeReferenceActionCatching<String> {
            error("generic action implementation exploded")
        }

        assertNull(attempt.result)
        assertEquals(
            "Reference-action comparison could not be completed. Optional Sync-v2 correspondence may still be available.",
            attempt.message,
        )
    }

}

package ai.senp.validation

import ai.senp.core.contracts.AnalysisFailure
import ai.senp.core.contracts.PipelineStageId
import ai.senp.sync.v2.VideoSynchronizationOutcome
import ai.senp.sync.v2.VideoSynchronizationRun
import kotlinx.coroutines.CancellationException

/**
 * Keeps generic reference-action understanding authoritative while treating Sync-v2 as optional
 * correspondence metadata. A synchronization failure must never erase an already-computed action result.
 */
internal data class RecordedComparisonAssembly<T>(
    val actionResult: T,
    val synchronizationRun: VideoSynchronizationRun? = null,
    val synchronizationFailure: AnalysisFailure? = null,
) {
    init {
        require(synchronizationRun == null || synchronizationFailure == null)
    }
}

internal data class OptionalReferenceActionAnalysis<T>(
    val result: T? = null,
    val message: String? = null,
) {
    init {
        require(result == null || message == null)
    }
}

/**
 * Generic action understanding is preferred when it can complete, but it must not prevent the
 * existing Sync-v2 correspondence path from remaining available as a fallback. Cancellation is
 * still propagated so leaving the screen or starting a new analysis cannot leak stale results.
 */
internal fun <T> analyzeReferenceActionCatching(
    analyze: () -> T,
): OptionalReferenceActionAnalysis<T> = try {
    OptionalReferenceActionAnalysis(result = analyze())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    OptionalReferenceActionAnalysis(
        message = "Reference-action comparison could not be completed. Optional Sync-v2 correspondence may still be available.",
    )
}

internal fun <T> assembleRecordedComparison(
    actionResult: T,
    synchronization: VideoSynchronizationOutcome,
): RecordedComparisonAssembly<T> = when (synchronization) {
    is VideoSynchronizationOutcome.Success -> RecordedComparisonAssembly(
        actionResult = actionResult,
        synchronizationRun = synchronization.run,
    )
    is VideoSynchronizationOutcome.Failure -> RecordedComparisonAssembly(
        actionResult = actionResult,
        synchronizationFailure = synchronization.failure,
    )
}

internal suspend fun <T> assembleRecordedComparisonCatching(
    actionResult: T,
    synchronize: suspend () -> VideoSynchronizationOutcome,
): RecordedComparisonAssembly<T> = try {
    assembleRecordedComparison(actionResult, synchronize())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    RecordedComparisonAssembly(
        actionResult = actionResult,
        synchronizationFailure = AnalysisFailure.Unexpected(
            stage = PipelineStageId.ALIGNMENT,
            exceptionType = error::class.qualifiedName ?: error.javaClass.name,
            message = error.message ?: "Optional Sync-v2 correspondence failed unexpectedly.",
        ),
    )
}

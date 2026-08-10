package ai.senp.validation

import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.review.FrameReview
import ai.senp.review.ReviewFailureKind
import android.content.Context
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface FrameReviewRunResult {
    data class Success(
        val review: FrameReview,
        val frameCount: Int,
    ) : FrameReviewRunResult

    data class Failure(
        val kind: ReviewFailureKind,
        val message: String,
        val frameCount: Int,
    ) : FrameReviewRunResult

    data class NoFrames(val message: String) : FrameReviewRunResult
}

internal class FrameReviewCoordinator(context: Context) {
    private val analyzer = GeminiPlanBClient(context.applicationContext)

    val isSignedIn: Boolean
        get() = true

    suspend fun signIn() = Unit

    suspend fun review(
        sourceUri: Uri,
        referenceUri: Uri,
        deviations: List<ReferenceDeviationMeasurement>,
    ): FrameReviewRunResult = withContext(Dispatchers.IO) {
        val selected = selectFrameReviewDeviations(deviations)
        if (selected.isEmpty()) {
            return@withContext FrameReviewRunResult.NoFrames(
                "No persistent reference-relative differences were flagged for AI review.",
            )
        }

        val detectorContext = selected.joinToString(
            prefix = "The existing on-device reference-action detector flagged these candidate differences. " +
                "Use them only as supplemental context; independently compare the complete reference and user videos.\n",
            separator = "\n",
        ) { deviation ->
            "${deviation.timestamp.value} ms: ${deviation.toReferenceCueLabel()}; " +
                "evidence ${(deviation.confidence * 100).toInt()}%; " +
                "normalized difference ${String.format(Locale.US, "%.2f", deviation.normalizedDeviation)}."
        }

        try {
            val result = analyzer.analyze(
                referenceUri = referenceUri,
                userUri = sourceUri,
                exerciseMetadata = detectorContext,
            )
            FrameReviewRunResult.Success(
                review = FrameReview(
                    text = formatAiReview(result),
                    reasoningSummary = "",
                    modelId = "AI",
                ),
                frameCount = selected.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FrameReviewRunResult.Failure(
                kind = if (error.hasRateLimitCause()) ReviewFailureKind.RATE_LIMITED else ReviewFailureKind.TRANSPORT,
                message = error.message ?: "AI review could not be completed.",
                frameCount = selected.size,
            )
        }
    }
}

internal fun formatAiReview(result: GeminiAnalysisResult): String = buildString {
    append(result.summary.trim())
    result.problems.take(4).forEachIndexed { index, problem ->
        append("\n\n")
        append(index + 1)
        append(". ")
        append(problem.title)
        append(" [")
        append(problem.severity.name)
        append("]\nIssue: ")
        append(problem.observedIssue)
        append("\nReference: ")
        append(problem.referenceBehavior)
        append("\nCue: ")
        append(problem.cue)
    }
    if (result.problems.isEmpty()) {
        append("\n\nNo additional visible form difference was supported by the video evidence.")
    }
    if (result.uncertainties.isNotEmpty()) {
        append("\n\nUncertainty: ")
        append(result.uncertainties.take(2).joinToString("; "))
    }
}

private fun Throwable.hasRateLimitCause(): Boolean =
    generateSequence(this) { current -> current.cause }
        .filterIsInstance<GeminiTransportException>()
        .any(GeminiTransportException::rateLimited)

internal fun selectFrameReviewDeviations(
    deviations: List<ReferenceDeviationMeasurement>,
    maximumFrames: Int = 4,
): List<ReferenceDeviationMeasurement> {
    require(maximumFrames > 0)
    return deviations
        .asSequence()
        .filter(ReferenceDeviationMeasurement::persistenceCandidate)
        .sortedByDescending { it.normalizedDeviation * it.confidence }
        .distinctBy { it.timestamp.value }
        .take(maximumFrames)
        .toList()
}

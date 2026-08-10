package ai.senp.validation

import ai.senp.codex.AndroidCodexTransport
import ai.senp.codex.CodexAuth
import ai.senp.codex.ReviewFrames
import ai.senp.motion.ReferenceDeviationMeasurement
import ai.senp.review.CodexFrameReviewer
import ai.senp.review.FrameReview
import ai.senp.review.FrameReviewRequest
import ai.senp.review.ImageDetail
import ai.senp.review.ReasoningEffort
import ai.senp.review.ReasoningSummary
import ai.senp.review.ReviewFailureKind
import ai.senp.review.ReviewModel
import ai.senp.review.ReviewModels
import ai.senp.review.ReviewOutcome
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LUNA_FRAME_REVIEW_MODEL = ReviewModel(
    id = ReviewModels.LUNA_5_6,
    effort = ReasoningEffort.LOW,
    summary = ReasoningSummary.AUTO,
    imageDetail = ImageDetail.LOW,
)

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
    private val appContext = context.applicationContext
    private val auth = CodexAuth(appContext)
    private val reviewer = CodexFrameReviewer(AndroidCodexTransport(auth))

    val isSignedIn: Boolean
        get() = auth.isSignedIn

    suspend fun signIn() {
        auth.signIn(timeoutMs = 300_000)
    }

    suspend fun review(
        sourceUri: Uri,
        deviations: List<ReferenceDeviationMeasurement>,
    ): FrameReviewRunResult = withContext(Dispatchers.IO) {
        val selected = selectFrameReviewDeviations(deviations)
        if (selected.isEmpty()) {
            return@withContext FrameReviewRunResult.NoFrames(
                "No persistent reference-relative differences were flagged for AI review.",
            )
        }

        val retriever = MediaMetadataRetriever()
        val frames = try {
            retriever.setDataSource(appContext, sourceUri)
            selected.mapIndexedNotNull { index, deviation ->
                val bitmap = retriever.getFrameAtTime(
                    deviation.timestamp.value * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: return@mapIndexedNotNull null
                try {
                    ReviewFrames.encode(
                        bitmap = bitmap,
                        label = "flagged-${index + 1}",
                        timestampMs = deviation.timestamp.value,
                    )
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
        }

        if (frames.isEmpty()) {
            return@withContext FrameReviewRunResult.NoFrames(
                "The flagged timestamps could not be decoded from the candidate video.",
            )
        }

        val selectedByTimestamp = selected.associateBy { it.timestamp.value }
        val userContext = frames.joinToString(
            prefix = "The on-device reference-action detector flagged these candidate frames.\n",
            separator = "\n",
        ) { frame ->
            val deviation = selectedByTimestamp.getValue(frame.timestampMs)
            "${frame.label} at ${frame.timestampMs} ms: ${deviation.toReferenceCueLabel()}; " +
                "evidence ${(deviation.confidence * 100).toInt()}%; " +
                "normalized difference ${String.format(Locale.US, "%.2f", deviation.normalizedDeviation)}."
        }

        val outcome = reviewer.review(
            FrameReviewRequest(
                systemPrompt = FRAME_REVIEW_PROMPT,
                userContext = userContext,
                frames = frames,
                model = LUNA_FRAME_REVIEW_MODEL,
            ),
        )

        when (outcome) {
            is ReviewOutcome.Success -> FrameReviewRunResult.Success(outcome.review, frames.size)
            is ReviewOutcome.Failure -> FrameReviewRunResult.Failure(
                kind = outcome.kind,
                message = outcome.message,
                frameCount = frames.size,
            )
        }
    }

    private companion object {
        const val FRAME_REVIEW_PROMPT = """
You review a small set of exercise/movement frames that an on-device reference-action detector already flagged. Use only the visible frame evidence and the supplied reference-relative detector context. Do not diagnose injuries, medical conditions, or claim universal biomechanical correctness. Do not change or second-guess which frames were selected. Give concise, practical coaching in at most 160 words. State: (1) what is visibly different, (2) one likely movement consequence, and (3) one concrete adjustment to try. If the images do not support a reliable conclusion, say so clearly.
"""
    }
}

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

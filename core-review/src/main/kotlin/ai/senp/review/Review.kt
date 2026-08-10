package ai.senp.review

/**
 * Frame review contracts.
 *
 * Pure Kotlin/JVM: no transport, no Android types. The caller supplies already-encoded frames and
 * an adapter supplies the HTTP transport, so every decision that affects cost, latency and answer
 * quality is expressed here as data and stays testable headlessly.
 */

/** Reasoning budget. Model-dependent; validate with [ReviewModels.supportedEfforts]. */
enum class ReasoningEffort {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    ;

    val wire: String get() = name.lowercase()
}

/** Verbosity of the streamed reasoning summary. Ignored when effort is [ReasoningEffort.NONE]. */
enum class ReasoningSummary {
    AUTO,
    CONCISE,
    DETAILED,
    ;

    val wire: String get() = name.lowercase()
}

/**
 * Per-image token budget. [LOW] caps each frame at a fixed small tile cost, which is the difference
 * between an eight-frame review costing hundreds versus thousands of input tokens.
 */
enum class ImageDetail {
    AUTO,
    LOW,
    HIGH,
    ;

    val wire: String get() = name.lowercase()
}

object ReviewModels {
    /**
     * Default. Luna is the fast, cheap tier of the 5.6 family and takes image input, which is all a
     * frame review needs. Verified against a ChatGPT-account session; `gpt-5.4-codex` is not
     * entitled on that path and returns 400.
     *
     * Do not substitute the bare `gpt-5.6` alias — it routes to Sol, a different model.
     */
    const val LUNA_5_6: String = "gpt-5.6-luna"
    const val CODEX_5_4: String = "gpt-5.4-codex"
    const val CODEX_5_3: String = "gpt-5.3-codex"

    // ponytail: table, not a registry. Read the models endpoint if this grows past a handful.
    fun supportedEfforts(modelId: String): Set<ReasoningEffort> = when {
        modelId.startsWith(CODEX_5_3) -> setOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH,
            ReasoningEffort.XHIGH,
        )

        else -> ReasoningEffort.entries.toSet()
    }
}

/**
 * Model selection for a review call.
 *
 * Defaults are a starting point, not a tuned answer: effort and [imageDetail] trade answer quality
 * against latency and against the signed-in account's plan limits, and the usable setting depends on
 * the device, the network and how many frames a review carries. Keep them wired to configuration.
 *
 * No output-token cap: the ChatGPT-account endpoint rejects `max_output_tokens` outright. Bound the
 * response through the system prompt instead.
 */
data class ReviewModel(
    val id: String = ReviewModels.LUNA_5_6,
    val effort: ReasoningEffort = ReasoningEffort.LOW,
    val summary: ReasoningSummary = ReasoningSummary.AUTO,
    val imageDetail: ImageDetail = ImageDetail.AUTO,
) {
    init {
        require(id.isNotBlank()) { "model id must not be blank" }
        require(effort in ReviewModels.supportedEfforts(id)) {
            "$id does not support reasoning effort ${effort.wire}"
        }
    }
}

/** One frame handed to the model, JPEG-encoded and base64'd by the platform adapter. */
data class ReviewFrame(
    val label: String,
    val timestampMs: Long,
    val jpegBase64: String,
) {
    init {
        require(label.isNotBlank()) { "frame label must not be blank" }
        require(timestampMs >= 0) { "frame timestamp must be non-negative" }
        require(jpegBase64.isNotBlank()) { "frame payload must not be blank" }
    }
}

data class FrameReviewRequest(
    val systemPrompt: String,
    val userContext: String,
    val frames: List<ReviewFrame>,
    val model: ReviewModel = ReviewModel(),
) {
    init {
        require(systemPrompt.isNotBlank()) { "system prompt must not be blank" }
        require(frames.isNotEmpty()) { "review requires at least one frame" }
        require(frames.map { it.label }.toSet().size == frames.size) {
            "frame labels must be unique so the model can refer to them"
        }
    }
}

data class FrameReview(
    val text: String,
    val reasoningSummary: String,
    val modelId: String,
)

enum class ReviewFailureKind {
    UNAUTHENTICATED,
    RATE_LIMITED,
    UNSUPPORTED_MODEL,
    TRANSPORT,
    PROTOCOL,
    REFUSED,
    CANCELLED,
}

sealed interface ReviewOutcome {
    data class Success(val review: FrameReview) : ReviewOutcome
    data class Failure(val kind: ReviewFailureKind, val message: String) : ReviewOutcome
}

/** Port. Implemented by [CodexFrameReviewer]; fake it in tests. */
fun interface FrameReviewer {
    suspend fun review(request: FrameReviewRequest): ReviewOutcome
}

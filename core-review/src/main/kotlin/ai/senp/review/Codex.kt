package ai.senp.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire format for the Codex Responses endpoint that backs a ChatGPT sign-in.
 *
 * Deliberately not the platform `api.openai.com` shape: this endpoint is reached with an OAuth
 * access token rather than an API key, and it answers only in server-sent events.
 */
object Codex {
    const val ENDPOINT: String = "https://chatgpt.com/backend-api/codex/responses"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the request body.
     *
     * `stream` and `store` are fixed: the endpoint always answers with SSE, and persisting a
     * training-visible copy of someone's workout frames is not ours to opt into.
     */
    fun body(request: FrameReviewRequest): String = buildJsonObject {
        put("model", request.model.id)
        put("instructions", request.systemPrompt)
        putJsonArray("input") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    if (request.userContext.isNotBlank()) {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", request.userContext)
                        }
                    }
                    request.frames.forEach { frame ->
                        // Label every frame: without it the model cannot tell rep 3 from rep 7.
                        addJsonObject {
                            put("type", "input_text")
                            put("text", "${frame.label} (t=${frame.timestampMs}ms)")
                        }
                        addJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:image/jpeg;base64,${frame.jpegBase64}")
                            put("detail", request.model.imageDetail.wire)
                        }
                    }
                }
            }
        }
        putJsonObject("reasoning") {
            put("effort", request.model.effort.wire)
            // A summary of no reasoning is rejected upstream.
            if (request.model.effort != ReasoningEffort.NONE) {
                put("summary", request.model.summary.wire)
            }
        }
        put("stream", true)
        put("store", false)
    }.toString()

    /**
     * Folds an SSE body into an outcome.
     *
     * Takes lines rather than a stream because the upstream response carries no `Content-Type`
     * header, so every off-the-shelf SSE client rejects it and the adapter has to read raw lines
     * anyway. Parsing them here keeps the protocol testable without a socket.
     */
    fun parse(lines: Sequence<String>, modelId: String): ReviewOutcome {
        val text = StringBuilder()
        val summary = StringBuilder()
        var completed = false

        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue

            val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: return ReviewOutcome.Failure(ReviewFailureKind.PROTOCOL, "Malformed SSE payload")

            when (event.string("type")) {
                "response.output_text.delta" -> text.append(event.string("delta").orEmpty())
                "response.reasoning_summary_text.delta" -> summary.append(event.string("delta").orEmpty())
                "response.refusal.delta" -> return ReviewOutcome.Failure(
                    ReviewFailureKind.REFUSED,
                    event.string("delta").orEmpty().ifBlank { "Model refused the request" },
                )

                "response.completed" -> completed = true
                "response.failed", "error" -> return ReviewOutcome.Failure(
                    ReviewFailureKind.PROTOCOL,
                    event.errorMessage() ?: "Upstream reported a failed response",
                )

                "response.incomplete" -> return ReviewOutcome.Failure(
                    ReviewFailureKind.PROTOCOL,
                    "Response truncated before completion; raise maxOutputTokens",
                )
            }
        }

        if (!completed) {
            return ReviewOutcome.Failure(ReviewFailureKind.TRANSPORT, "Stream ended before completion")
        }
        if (text.isBlank()) {
            return ReviewOutcome.Failure(ReviewFailureKind.PROTOCOL, "Completed with no output text")
        }
        return ReviewOutcome.Success(FrameReview(text.toString(), summary.toString(), modelId))
    }

    /**
     * Pulls the message out of a non-SSE error body. The upstream 400 for an unavailable model
     * carries the only actionable text in the whole exchange, so it must not be reduced to a status.
     */
    fun errorDetail(lines: Sequence<String>): String? {
        val text = lines.joinToString("\n").trim()
        if (text.isEmpty()) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return root.string("detail") ?: root.errorMessage()
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun JsonObject.errorMessage(): String? {
        val nested = runCatching { this["error"]?.jsonObject }.getOrNull()
        return nested?.string("message")
            ?: runCatching { this["response"]?.jsonObject?.get("error")?.jsonObject }
                .getOrNull()?.string("message")
    }
}

/** SSE response as read by a platform adapter. The adapter owns the connection and closes it. */
data class CodexResponse(val status: Int, val lines: Sequence<String>)

/**
 * Transport seam. Implementations live in platform modules because core may not import networking
 * types; [ai.senp.review.CodexFrameReviewer] stays testable with a canned response.
 */
fun interface CodexTransport {
    suspend fun postSse(url: String, jsonBody: String): CodexResponse
}

class CodexFrameReviewer(private val transport: CodexTransport) : FrameReviewer {
    override suspend fun review(request: FrameReviewRequest): ReviewOutcome {
        val response = runCatching { transport.postSse(Codex.ENDPOINT, Codex.body(request)) }
            .getOrElse { error ->
                return ReviewOutcome.Failure(
                    ReviewFailureKind.TRANSPORT,
                    error.message ?: error::class.simpleName.orEmpty(),
                )
            }

        if (response.status == 200) return Codex.parse(response.lines, request.model.id)

        val detail = Codex.errorDetail(response.lines)
        return when (response.status) {
            401, 403 -> ReviewOutcome.Failure(
                ReviewFailureKind.UNAUTHENTICATED,
                detail ?: "ChatGPT session rejected; sign in again",
            )

            // A ChatGPT-account session is entitled to a narrower model set than the platform API,
            // and refuses the rest with 400 rather than 404.
            400, 404, 422 -> ReviewOutcome.Failure(
                ReviewFailureKind.UNSUPPORTED_MODEL,
                detail ?: "${request.model.id} rejected this request shape",
            )

            429 -> ReviewOutcome.Failure(
                ReviewFailureKind.RATE_LIMITED,
                detail ?: "Plan rate limit reached",
            )

            else -> ReviewOutcome.Failure(
                ReviewFailureKind.TRANSPORT,
                detail ?: "HTTP ${response.status}",
            )
        }
    }
}

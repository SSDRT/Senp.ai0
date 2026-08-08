package ai.senp.review

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexTest {
    private val frames = listOf(
        ReviewFrame("rep 3 bottom", 4_120, "AAAA"),
        ReviewFrame("rep 3 lockout", 4_880, "BBBB"),
    )

    private fun request(model: ReviewModel = ReviewModel()) = FrameReviewRequest(
        systemPrompt = "You are a strength coach.",
        userContext = "Squat, source left.",
        frames = frames,
        model = model,
    )

    private fun sse(vararg events: String) = events.asSequence().map { "data: $it" }

    @Test
    fun `body carries model reasoning and one labelled image per frame`() {
        val body = Json.parseToJsonElement(
            Codex.body(request(ReviewModel(effort = ReasoningEffort.HIGH, imageDetail = ImageDetail.LOW))),
        ).jsonObject

        assertEquals(ReviewModels.LUNA_5_6, body["model"]?.jsonPrimitive?.content)
        assertEquals("You are a strength coach.", body["instructions"]?.jsonPrimitive?.content)
        assertEquals("high", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("auto", body["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["store"]?.jsonPrimitive?.content?.toBoolean())

        val content = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        val images = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        assertEquals(2, images.size)
        assertEquals("low", images[0].jsonObject["detail"]?.jsonPrimitive?.content)
        assertEquals(
            "data:image/jpeg;base64,AAAA",
            images[0].jsonObject["image_url"]?.jsonPrimitive?.content,
        )
        val labels = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "input_text" }
            .map { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(labels.any { it == "rep 3 bottom (t=4120ms)" }, "frames must be labelled: $labels")
    }

    @Test
    fun `zero effort omits the reasoning summary`() {
        val body = Json.parseToJsonElement(
            Codex.body(request(ReviewModel(effort = ReasoningEffort.NONE))),
        ).jsonObject
        assertEquals(null, body["reasoning"]?.jsonObject?.get("summary"))
    }

    @Test
    fun `model rejects an effort it does not support`() {
        assertFailsWith<IllegalArgumentException> {
            ReviewModel(id = ReviewModels.CODEX_5_3, effort = ReasoningEffort.MINIMAL)
        }
        // The same effort is fine on the model that does support it.
        ReviewModel(id = ReviewModels.CODEX_5_4, effort = ReasoningEffort.MINIMAL)
    }

    @Test
    fun `duplicate frame labels are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FrameReviewRequest("p", "c", listOf(frames[0], frames[0].copy(timestampMs = 9_000)))
        }
    }

    @Test
    fun `parse joins text deltas and reasoning summary`() {
        val outcome = Codex.parse(
            sse(
                """{"type":"response.reasoning_summary_text.delta","delta":"Checking depth."}""",
                """{"type":"response.output_text.delta","delta":"Hips "}""",
                """{"type":"response.output_text.delta","delta":"drop early."}""",
                """{"type":"response.completed"}""",
                "[DONE]",
            ),
            ReviewModels.CODEX_5_4,
        )

        val success = assertIs<ReviewOutcome.Success>(outcome)
        assertEquals("Hips drop early.", success.review.text)
        assertEquals("Checking depth.", success.review.reasoningSummary)
    }

    @Test
    fun `truncated stream is a transport failure not a partial answer`() {
        val outcome = Codex.parse(
            sse("""{"type":"response.output_text.delta","delta":"Hips "}"""),
            ReviewModels.CODEX_5_4,
        )
        assertEquals(ReviewFailureKind.TRANSPORT, assertIs<ReviewOutcome.Failure>(outcome).kind)
    }

    @Test
    fun `upstream error event surfaces its message`() {
        val outcome = Codex.parse(
            sse("""{"type":"response.failed","error":{"message":"context too long"}}"""),
            ReviewModels.CODEX_5_4,
        )
        val failure = assertIs<ReviewOutcome.Failure>(outcome)
        assertEquals(ReviewFailureKind.PROTOCOL, failure.kind)
        assertEquals("context too long", failure.message)
    }

    @Test
    fun `http status maps to a typed failure`() = runBlocking {
        suspend fun outcomeFor(status: Int) = CodexFrameReviewer { _, _ ->
            CodexResponse(status, emptySequence())
        }.review(request())

        assertEquals(ReviewFailureKind.UNAUTHENTICATED, assertIs<ReviewOutcome.Failure>(outcomeFor(401)).kind)
        assertEquals(ReviewFailureKind.RATE_LIMITED, assertIs<ReviewOutcome.Failure>(outcomeFor(429)).kind)
        assertEquals(ReviewFailureKind.UNSUPPORTED_MODEL, assertIs<ReviewOutcome.Failure>(outcomeFor(404)).kind)
        assertEquals(ReviewFailureKind.TRANSPORT, assertIs<ReviewOutcome.Failure>(outcomeFor(503)).kind)
    }

    @Test
    fun `transport exception does not escape the reviewer`() = runBlocking {
        val outcome = CodexFrameReviewer { _, _ -> throw IllegalStateException("socket closed") }
            .review(request())
        assertEquals("socket closed", assertIs<ReviewOutcome.Failure>(outcome).message)
    }
}

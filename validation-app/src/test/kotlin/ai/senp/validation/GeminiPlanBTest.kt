package ai.senp.validation

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeminiPlanBTest {
    @Test
    fun `one comparison request contains fixed reference then user roles and metadata`() {
        val request = GeminiRequestFactory.comparisonRequest(
            reference = GeminiRemoteFile("files/reference", "uri:reference", "video/mp4"),
            user = GeminiRemoteFile("files/user", "uri:user", "video/mp4"),
            exerciseMetadata = "exercise=pull-up; profile=strict",
        )
        val parts = request["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        val text = parts.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("exercise=pull-up; profile=strict"))
        assertTrue(text.contains("Primary-joint range of motion"))
        assertTrue(text.contains("bottom-position extension/dead-hang completeness"))
        assertTrue(text.contains("problems[0] MUST be the single most important visible mismatch"))
        assertTrue(text.indexOf("REFERENCE / MASTER VIDEO") < text.indexOf("USER VIDEO"))
        assertEquals("uri:reference", parts[2].jsonObject["fileData"]!!.jsonObject["fileUri"]!!.jsonPrimitive.content)
        assertEquals("uri:user", parts[4].jsonObject["fileData"]!!.jsonObject["fileUri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `valid Gemini result is parsed with empty problems and coaching fields`() {
        val result = GeminiAnalysisParser.parseAndValidate(validJson(emptyList()), 3_000L, 3_000L)
        assertEquals("pull-up", result.exercise)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `complete videos are uploaded once together, roles are retained, and both remote files are deleted`() = runBlocking {
        val root = tempDir("gemini-plan-b-test-")
        try {
            val reference = File(root, "reference.mp4").apply { writeText("reference") }
            val user = File(root, "user.mp4").apply { writeText("user") }
            val transport = FakeTransport(validJson(listOf(problemJson())))
            val result = GeminiPlanBSession(root, "gemini-test", 10_000L, 0, transport, GeminiJson.instance)
                .analyze(reference, user, 3_000L, 3_000L, "pull-up")
            assertEquals(1, result.problems.size)
            assertEquals(listOf("reference-master.mp4", "user-exercise.mp4"), transport.uploadedNames)
            assertEquals(1, transport.generateCalls)
            assertEquals(setOf("files/reference", "files/user"), transport.deletedNames)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("request-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retryable Gemini generation retries within bound and permanent failure does not spin`() = runBlocking {
        val root = tempDir("gemini-plan-b-retry-")
        val reference = File(root, "reference.mp4").apply { writeText("reference") }
        val user = File(root, "user.mp4").apply { writeText("user") }
        try {
            val retrying = FakeTransport(validJson(emptyList()), failGenerateAttempts = 1)
            GeminiPlanBSession(root, "gemini-test", 10_000L, 2, retrying, GeminiJson.instance)
                .analyze(reference, user, 3_000L, 3_000L, null)
            assertEquals(2, retrying.generateCalls)

            val permanent = FakeTransport(validJson(emptyList()), permanentGenerateFailure = true)
            assertFailsWith<GeminiPlanBException> {
                GeminiPlanBSession(root, "gemini-test", 10_000L, 3, permanent, GeminiJson.instance)
                    .analyze(reference, user, 3_000L, 3_000L, null)
            }
            assertEquals(1, permanent.generateCalls)
            assertEquals(setOf("files/reference", "files/user"), permanent.deletedNames)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rate limited primary key rotates to fallback while permanent errors do not rotate`() = runBlocking {
        val usedKeys = mutableListOf<String>()
        val result = withGeminiApiKeyFallback(listOf("primary", "fallback-1", "fallback-2")) { key, _ ->
            usedKeys += key
            if (key == "primary") {
                throw GeminiPlanBException(
                    "rate limited",
                    GeminiTransportException("HTTP 429", retryable = false, rateLimited = true),
                )
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("primary", "fallback-1"), usedKeys)

        val permanentKeys = mutableListOf<String>()
        assertFailsWith<GeminiPlanBException> {
            withGeminiApiKeyFallback(listOf("primary", "fallback-1")) { key, _ ->
                permanentKeys += key
                throw GeminiPlanBException(
                    "bad request",
                    GeminiTransportException("HTTP 400", retryable = false),
                )
            }
        }
        assertEquals(listOf("primary"), permanentKeys)
    }

    @Test
    fun `remote and local temporary cleanup happen when generation or validation fails`() = runBlocking {
        val root = tempDir("gemini-plan-b-cleanup-")
        val reference = File(root, "reference.mp4").apply { writeText("reference") }
        val user = File(root, "user.mp4").apply { writeText("user") }
        try {
            val malformed = FakeTransport("not json")
            assertFailsWith<GeminiPlanBException> {
                GeminiPlanBSession(root, "gemini-test", 10_000L, 0, malformed, GeminiJson.instance)
                    .analyze(reference, user, 3_000L, 3_000L, null)
            }
            assertEquals(setOf("files/reference", "files/user"), malformed.deletedNames)
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("request-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid timestamps, severity, malformed, and empty responses are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GeminiAnalysisParser.parseAndValidate(validJson(listOf(problemJson(userEnd = 4_000))), 3_000L, 3_000L)
        }
        assertFailsWith<GeminiPlanBException> { GeminiAnalysisParser.parseAndValidate("{\"exercise\":", 3_000L, 3_000L) }
        assertFailsWith<GeminiPlanBException> { GeminiAnalysisParser.parseAndValidate("", 3_000L, 3_000L) }
        assertFailsWith<GeminiPlanBException> { GeminiAnalysisParser.parseAndValidate(validJson(listOf(problemJson(severity = "INVALID"))), 3_000L, 3_000L) }
    }

    private fun validJson(problems: List<String>): String = """
        {
          "exercise":"pull-up",
          "summary":"Keep your torso controlled.",
          "overall_score":82,
          "confidence":0.91,
          "rep_count":3,
          "problems":[${problems.joinToString(",")}],
          "uncertainties":[]
        }
    """.trimIndent()

    private fun problemJson(userEnd: Long = 1_200L, severity: String = "MEDIUM"): String = """
        {
          "title":"Hip timing",
          "user_start_ms":700,
          "user_end_ms":$userEnd,
          "reference_start_ms":800,
          "reference_end_ms":1300,
          "phase":"upward",
          "body_region":"hips",
          "severity":"$severity",
          "confidence":0.88,
          "observed_issue":"Your hips rise before your shoulders.",
          "reference_behavior":"The reference raises the torso and hips together.",
          "cue":"Drive your chest and hips up as one unit.",
          "explanation":"The early hip rise changes the movement path."
        }
    """.trimIndent()

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()
}

private class FakeTransport(
    private val response: String,
    private val failGenerateAttempts: Int = 0,
    private val permanentGenerateFailure: Boolean = false,
) : GeminiFilesTransport {
    val uploadedNames = mutableListOf<String>()
    val deletedNames = mutableSetOf<String>()
    var generateCalls = 0
    private var uploadCount = 0

    override suspend fun upload(file: File, displayName: String, mimeType: String): GeminiRemoteFile {
        uploadedNames += displayName
        uploadCount += 1
        return if (uploadCount == 1) GeminiRemoteFile("files/reference", "uri:reference", mimeType)
        else GeminiRemoteFile("files/user", "uri:user", mimeType)
    }

    override suspend fun waitUntilActive(file: GeminiRemoteFile, timeoutMs: Long): GeminiRemoteFile = file

    override suspend fun generate(model: String, request: JsonObject): String {
        generateCalls += 1
        if (permanentGenerateFailure || generateCalls <= failGenerateAttempts) {
            throw GeminiTransportException("test failure", retryable = !permanentGenerateFailure)
        }
        return response
    }

    override suspend fun delete(fileName: String) {
        deletedNames += fileName
    }
}

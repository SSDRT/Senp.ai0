package ai.senp.validation

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class GeminiAnalysisResult(
    val exercise: String,
    val summary: String,
    @SerialName("overall_score") val overallScore: Double,
    val confidence: Double,
    @SerialName("rep_count") val repCount: Int,
    val problems: List<GeminiProblem> = emptyList(),
    val uncertainties: List<String> = emptyList(),
)

@Serializable
data class GeminiProblem(
    val title: String,
    @SerialName("user_start_ms") val userStartMs: Long,
    @SerialName("user_end_ms") val userEndMs: Long,
    @SerialName("reference_start_ms") val referenceStartMs: Long? = null,
    @SerialName("reference_end_ms") val referenceEndMs: Long? = null,
    val phase: String? = null,
    @SerialName("body_region") val bodyRegion: String,
    val severity: GeminiSeverity,
    val confidence: Double,
    @SerialName("observed_issue") val observedIssue: String,
    @SerialName("reference_behavior") val referenceBehavior: String,
    val cue: String,
    val explanation: String? = null,
)

@Serializable
enum class GeminiSeverity {
    LOW,
    MEDIUM,
    HIGH,
}

internal interface GeminiPlanBAnalyzer {
    suspend fun analyze(referenceUri: Uri, userUri: Uri, exerciseMetadata: String? = null): GeminiAnalysisResult
}

internal data class GeminiRemoteFile(val name: String, val uri: String, val mimeType: String)

internal interface GeminiFilesTransport {
    suspend fun upload(file: File, displayName: String, mimeType: String): GeminiRemoteFile
    suspend fun waitUntilActive(file: GeminiRemoteFile, timeoutMs: Long): GeminiRemoteFile
    suspend fun generate(model: String, request: JsonObject): String
    suspend fun delete(fileName: String)
}

internal class GeminiPlanBClient(
    private val context: Context,
    private val apiKeys: List<String>,
    private val model: String,
    private val timeoutMs: Long = BuildConfig.AI_PLAN_B_TIMEOUT_SEC.toLong() * 1_000L,
    private val retries: Int = BuildConfig.AI_PLAN_B_RETRIES,
    private val transportFactory: (String) -> GeminiFilesTransport = { apiKey -> GeminiHttpTransport(apiKey, timeoutMs) },
    private val json: Json = GeminiJson.instance,
) : GeminiPlanBAnalyzer {
    override suspend fun analyze(
        referenceUri: Uri,
        userUri: Uri,
        exerciseMetadata: String?,
    ): GeminiAnalysisResult = withTimeout(timeoutMs) {
        require(apiKeys.isNotEmpty()) {
            "Gemini API key is not configured. Add one in AI Review Settings."
        }
        val workspace = File(context.cacheDir, "gemini-plan-b-" + UUID.randomUUID())
        check(workspace.mkdirs()) { "Could not create the temporary Gemini request workspace." }
        try {
            val referenceFile = copyUriToWorkspace(referenceUri, workspace, "reference-master")
            val userFile = copyUriToWorkspace(userUri, workspace, "user-exercise")
            val userDurationMs = videoDurationMs(userUri)
            val referenceDurationMs = videoDurationMs(referenceUri)
            withGeminiApiKeyFallback(apiKeys) { apiKey, keyIndex ->
                if (keyIndex > 0) {
                    planBLog("Gemini Plan B retrying with fallback key " + (keyIndex + 1))
                }
                GeminiPlanBSession(
                    workspaceRoot = workspace,
                    model = model,
                    timeoutMs = timeoutMs,
                    retries = retries,
                    transport = transportFactory(apiKey),
                    json = json,
                ).analyze(
                    referenceFile = referenceFile,
                    userFile = userFile,
                    userDurationMs = userDurationMs,
                    referenceDurationMs = referenceDurationMs,
                    exerciseMetadata = exerciseMetadata,
                )
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    private fun copyUriToWorkspace(uri: Uri, workspace: File, stem: String): File {
        val target = File(workspace, stem + ".mp4")
        val input = if (uri.scheme == "file") {
            java.io.FileInputStream(File(uri.path ?: error("Video URI has no path: " + uri)))
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: throw IOException("Cannot open video URI for Gemini Plan B: " + uri)
        input.use { source -> target.outputStream().use { destination -> source.copyTo(destination) } }
        check(target.length() > 0L) { "Video URI is empty: " + uri }
        return target
    }

    private fun videoDurationMs(uri: Uri): Long = android.media.MediaMetadataRetriever().run {
        try {
            if (uri.scheme == "file") setDataSource(uri.path)
            else setDataSource(context, uri)
            extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it >= 0L } ?: Long.MAX_VALUE
        } finally {
            release()
        }
    }

}

internal suspend fun <T> withGeminiApiKeyFallback(
    apiKeys: List<String>,
    operation: suspend (apiKey: String, keyIndex: Int) -> T,
): T {
    require(apiKeys.isNotEmpty()) { "At least one Gemini API key is required." }
    apiKeys.forEachIndexed { index, apiKey ->
        try {
            return operation(apiKey, index)
        } catch (error: GeminiPlanBException) {
            if (!error.wasRateLimited() || index == apiKeys.lastIndex) throw error
            planBLog("Gemini Plan B key " + (index + 1) + " was rate limited; rotating to the next configured key")
        }
    }
    error("Gemini API key fallback exhausted unexpectedly")
}

private fun Throwable.wasRateLimited(): Boolean =
    generateSequence(this) { current -> current.cause }
        .filterIsInstance<GeminiTransportException>()
        .any(GeminiTransportException::rateLimited)

private fun planBLog(message: String) {
    runCatching { Log.i("GeminiPlanB", message) }
}

internal object GeminiJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
}

internal class GeminiPlanBSession(
    private val workspaceRoot: File,
    private val model: String,
    private val timeoutMs: Long,
    private val retries: Int,
    private val transport: GeminiFilesTransport,
    private val json: Json,
) {
    suspend fun analyze(
        referenceFile: File,
        userFile: File,
        userDurationMs: Long,
        referenceDurationMs: Long,
        exerciseMetadata: String?,
    ): GeminiAnalysisResult = withTimeout(timeoutMs) {
        val requestWorkspace = File(workspaceRoot, "request-" + UUID.randomUUID())
        check(requestWorkspace.mkdirs()) { "Could not create the Gemini request workspace." }
        try {
            val referenceRemote = retryable("reference upload") {
                transport.upload(referenceFile, "reference-master.mp4", "video/mp4")
            }
            var userRemote: GeminiRemoteFile? = null
            try {
                planBLog("Gemini Plan B reference uploaded")
                userRemote = retryable("user upload") {
                    transport.upload(userFile, "user-exercise.mp4", "video/mp4")
                }
                planBLog("Gemini Plan B user video uploaded")
                val activeReference = retryable("reference processing") {
                    transport.waitUntilActive(referenceRemote, timeoutMs)
                }
                val activeUser = retryable("user processing") {
                    transport.waitUntilActive(requireNotNull(userRemote), timeoutMs)
                }
                val request = GeminiRequestFactory.comparisonRequest(
                    reference = activeReference,
                    user = activeUser,
                    exerciseMetadata = exerciseMetadata,
                )
                planBLog("Gemini Plan B comparison requested")
                val rawResponse = retryable("Gemini comparison") {
                    transport.generate(model, request)
                }
                val result = GeminiAnalysisParser.parseAndValidate(
                    rawResponse,
                    userDurationMs = userDurationMs,
                    referenceDurationMs = referenceDurationMs,
                    json = json,
                )
                planBLog("Gemini Plan B result received: " + result.problems.size + " problems")
                result
            } finally {
                runCatching { transport.delete(referenceRemote.name) }
                userRemote?.let { runCatching { transport.delete(it.name) } }
                planBLog("Gemini Plan B remote cleanup completed")
            }
        } finally {
            requestWorkspace.deleteRecursively()
        }
    }

    private suspend fun <T> retryable(label: String, operation: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return operation()
            } catch (error: GeminiTransportException) {
                if (error.rateLimited) {
                    throw GeminiPlanBException(label + " was rate limited", error)
                }
                if (!error.retryable || attempt >= retries) {
                    throw GeminiPlanBException(label + " failed", error)
                }
                attempt += 1
                delay(500L * attempt)
            }
        }
    }

}

internal object GeminiAnalysisParser {
    fun parseAndValidate(
        rawResponse: String,
        userDurationMs: Long,
        referenceDurationMs: Long,
        json: Json = GeminiJson.instance,
    ): GeminiAnalysisResult {
        val jsonText = extractJsonObject(rawResponse)
        val result = runCatching { json.decodeFromString<GeminiAnalysisResult>(jsonText) }
            .getOrElse { throw GeminiPlanBException("Gemini returned malformed analysis JSON", it) }
        validate(result, userDurationMs, referenceDurationMs)
        return result
    }

    internal fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw GeminiPlanBException("Gemini returned an empty analysis response")
        val fence = 96.toChar().toString().repeat(3)
        val unfenced = trimmed.removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        val start = unfenced.indexOf('{')
        val end = unfenced.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw GeminiPlanBException("Gemini response did not contain a JSON object")
        }
        return unfenced.substring(start, end + 1)
    }

    internal fun validate(result: GeminiAnalysisResult, userDurationMs: Long, referenceDurationMs: Long) {
        require(result.exercise.isNotBlank()) { "Gemini result exercise is blank" }
        require(result.summary.isNotBlank()) { "Gemini result summary is blank" }
        require(result.overallScore in 0.0..100.0) { "Gemini overall score is outside 0..100" }
        require(result.confidence in 0.0..1.0) { "Gemini confidence is outside 0..1" }
        require(result.repCount >= 0) { "Gemini repetition count is negative" }
        result.problems.forEach { problem ->
            require(problem.title.isNotBlank()) { "Gemini problem title is blank" }
            require(problem.userStartMs >= 0L && problem.userStartMs <= problem.userEndMs) {
                "Gemini user timestamp range is invalid"
            }
            require(problem.userEndMs <= userDurationMs) { "Gemini user timestamp is outside the video" }
            require((problem.referenceStartMs == null) == (problem.referenceEndMs == null)) {
                "Gemini reference timestamps must be supplied together"
            }
            if (problem.referenceStartMs != null && problem.referenceEndMs != null) {
                require(problem.referenceStartMs >= 0L && problem.referenceStartMs <= problem.referenceEndMs) {
                    "Gemini reference timestamp range is invalid"
                }
                require(problem.referenceEndMs <= referenceDurationMs) {
                    "Gemini reference timestamp is outside the video"
                }
            }
            require(problem.bodyRegion.isNotBlank()) { "Gemini problem body region is blank" }
            require(problem.confidence in 0.0..1.0) { "Gemini problem confidence is outside 0..1" }
            require(problem.observedIssue.isNotBlank()) { "Gemini observed issue is blank" }
            require(problem.referenceBehavior.isNotBlank()) { "Gemini reference behavior is blank" }
            require(problem.cue.isNotBlank()) { "Gemini correction cue is blank" }
        }
    }
}

internal object GeminiRequestFactory {
    fun comparisonRequest(
        reference: GeminiRemoteFile,
        user: GeminiRemoteFile,
        exerciseMetadata: String?,
    ): JsonObject = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", comparisonPrompt(exerciseMetadata)) })
                    add(buildJsonObject { put("text", "REFERENCE / MASTER VIDEO follows. This is the target demonstration.") })
                    add(filePart(reference))
                    add(buildJsonObject { put("text", "USER VIDEO follows. This is the execution being evaluated.") })
                    add(filePart(user))
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("responseMimeType", "application/json")
            put("responseSchema", responseSchema())
        })
    }

    private fun filePart(file: GeminiRemoteFile): JsonObject = buildJsonObject {
        put("fileData", buildJsonObject {
            put("mimeType", file.mimeType)
            put("fileUri", file.uri)
        })
    }

    private fun comparisonPrompt(exerciseMetadata: String?): String = buildString {
        appendLine("You are the motion-analysis engine for senp.ai. Your job is to find the most important reference-relative execution errors, not to praise the user or describe superficial visual differences.")
        appendLine("REFERENCE / MASTER VIDEO is the target demonstration and correct reference. USER VIDEO is the execution being evaluated. These roles are fixed.")
        appendLine("Analyze both COMPLETE videos before deciding what is wrong. First identify the exercise, camera orientation, repetitions, repetition boundaries, and equivalent movement phases.")
        appendLine("Before writing any summary, independently audit every repetition against the reference in this priority order:")
        appendLine("1. Primary-joint range of motion and whether start/end positions are actually completed.")
        appendLine("2. Flexion/extension or other primary joint actions through each phase, including the transition points.")
        appendLine("3. Main body-segment path and alignment relative to the reference.")
        appendLine("4. Compensations that materially change the movement.")
        appendLine("5. Tempo/timing differences only after the movement itself has been checked.")
        appendLine("For cyclic exercises, inspect EVERY rep. A repeated loss of range of motion or incomplete phase endpoint is usually more important than a small leg, foot, hand, or cosmetic posture difference.")
        appendLine("For pull-ups/chin-ups specifically, explicitly compare elbow range through the full cycle: bottom-position extension/dead-hang completeness, elbow flexion during the ascent, top-position completion, and re-extension during the descent. Also compare shoulder/scapular motion and torso compensation. Do not report a minor knee/shin difference as the primary problem if elbow or shoulder range differs more from the reference.")
        appendLine("Correspond USER phases with equivalent REFERENCE phases. Do not compare equal wall-clock timestamps unless they are equivalent movement phases; execution speeds may differ.")
        appendLine("Rank problems by: impact on the exercise's primary motion, magnitude versus the reference, persistence across frames/reps, then confidence. problems[0] MUST be the single most important visible mismatch. Report up to four meaningful problems in descending importance.")
        appendLine("Do not say the user has 'excellent form', 'closely matches', or similar reassuring language when a meaningful primary-joint range or phase-completion error is visible. Lead the summary with the dominant mismatch and correction priority.")
        appendLine("Only report differences supported by visible evidence. Separate true execution deviations from normal anatomical variation, harmless variation, camera perspective, blur, occlusion, and camera movement.")
        appendLine("Never invent hidden joints, exact numerical joint angles you cannot reliably observe, medical diagnoses, or facts unsupported by the videos. Use uncertainties when visibility is insufficient.")
        appendLine("For each meaningful problem, localize the USER time range and corresponding REFERENCE range when identifiable, then state: what the user does, what the reference does instead, why it matters for matching the movement, and one concrete physical correction cue.")
        appendLine("An empty problems array is valid only when no meaningful reference-relative execution deviation is visible after the full joint-ROM and phase audit. Score must follow visible evidence, not motivation.")
        if (!exerciseMetadata.isNullOrBlank()) {
            appendLine("Supplemental detector context follows. It can help you inspect timestamps, but it is NOT authoritative and MUST NOT determine your issue ranking before your independent full-video audit:")
            appendLine(exerciseMetadata)
        }
        appendLine("Return only JSON matching the supplied schema. Use milliseconds for all timestamps, severity LOW/MEDIUM/HIGH, and confidence values from 0 to 1.")
    }

    private fun responseSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("required", buildJsonArray {
            listOf("exercise", "summary", "overall_score", "confidence", "rep_count", "problems", "uncertainties").forEach { add(JsonPrimitive(it)) }
        })
        put("properties", buildJsonObject {
            put("exercise", stringSchema())
            put("summary", stringSchema())
            put("overall_score", numberSchema(0.0, 100.0))
            put("confidence", numberSchema(0.0, 1.0))
            put("rep_count", buildJsonObject { put("type", "INTEGER") })
            put("problems", buildJsonObject { put("type", "ARRAY"); put("items", problemSchema()) })
            put("uncertainties", buildJsonObject { put("type", "ARRAY"); put("items", stringSchema()) })
        })
    }

    private fun problemSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("required", buildJsonArray {
            listOf("title", "user_start_ms", "user_end_ms", "body_region", "severity", "confidence", "observed_issue", "reference_behavior", "cue").forEach { add(JsonPrimitive(it)) }
        })
        put("properties", buildJsonObject {
            put("title", stringSchema())
            put("user_start_ms", buildJsonObject { put("type", "INTEGER") })
            put("user_end_ms", buildJsonObject { put("type", "INTEGER") })
            put("reference_start_ms", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
            put("reference_end_ms", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
            put("phase", buildJsonObject { put("type", "STRING"); put("nullable", true) })
            put("body_region", stringSchema())
            put("severity", buildJsonObject {
                put("type", "STRING")
                put("enum", buildJsonArray { listOf("LOW", "MEDIUM", "HIGH").forEach { add(JsonPrimitive(it)) } })
            })
            put("confidence", numberSchema(0.0, 1.0))
            put("observed_issue", stringSchema())
            put("reference_behavior", stringSchema())
            put("cue", stringSchema())
            put("explanation", buildJsonObject { put("type", "STRING"); put("nullable", true) })
        })
    }

    private fun stringSchema() = buildJsonObject { put("type", "STRING") }

    private fun numberSchema(minimum: Double, maximum: Double) = buildJsonObject {
        put("type", "NUMBER")
        put("minimum", minimum)
        put("maximum", maximum)
    }
}

internal class GeminiPlanBException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class GeminiTransportException(
    message: String,
    val retryable: Boolean,
    val rateLimited: Boolean = false,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

private class GeminiHttpTransport(
    private val apiKey: String,
    private val timeoutMs: Long,
) : GeminiFilesTransport {
    override suspend fun upload(file: File, displayName: String, mimeType: String): GeminiRemoteFile = withContext(Dispatchers.IO) {
        val start = connection("https://generativelanguage.googleapis.com/upload/v1beta/files?key=" + encoded(apiKey), "POST")
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", file.length().toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
        start.setRequestProperty("Content-Type", "application/json")
        start.doOutput = true
        val safeDisplayName = displayName.replace("\\", "\\\\").replace("\"", "\\\"")
        val metadata = ("{\"file\":{\"display_name\":\"" + safeDisplayName + "\"}}").toByteArray()
        start.outputStream.use { output -> output.write(metadata) }
        val uploadUrl = readResponse(start).headers["x-goog-upload-url"]
            ?: throw GeminiTransportException("Gemini did not return an upload URL", retryable = false)
        val upload = connection(uploadUrl, "POST")
        upload.setRequestProperty("Content-Length", file.length().toString())
        upload.setRequestProperty("X-Goog-Upload-Offset", "0")
        upload.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        upload.setRequestProperty("Content-Type", mimeType)
        upload.doOutput = true
        file.inputStream().use { input -> upload.outputStream.use { output -> input.copyTo(output) } }
        val body = readResponse(upload).body
        val remote = GeminiJson.instance.parseToJsonElement(body).jsonObject["file"]?.jsonObject
            ?: throw GeminiTransportException("Gemini upload response did not contain a file", retryable = false)
        GeminiRemoteFile(
            name = remote["name"]?.jsonPrimitive?.content ?: error("Gemini upload response missing file name"),
            uri = remote["uri"]?.jsonPrimitive?.content ?: error("Gemini upload response missing file URI"),
            mimeType = remote["mimeType"]?.jsonPrimitive?.content ?: mimeType,
        )
    }

    override suspend fun waitUntilActive(file: GeminiRemoteFile, timeoutMs: Long): GeminiRemoteFile = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val connection = connection("https://generativelanguage.googleapis.com/v1beta/" + file.name + "?key=" + encoded(apiKey), "GET")
            val current = GeminiJson.instance.parseToJsonElement(readResponse(connection).body).jsonObject
            when (current["state"]?.jsonPrimitive?.contentOrNull) {
                "ACTIVE" -> return@withContext file
                "FAILED" -> throw GeminiTransportException("Gemini file processing failed", retryable = false)
            }
            delay(1_000L)
        }
        throw GeminiTransportException("Gemini file processing timed out", retryable = false)
    }

    override suspend fun generate(model: String, request: JsonObject): String = withContext(Dispatchers.IO) {
        val connection = connection("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + encoded(apiKey), "POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(request.toString().toByteArray()) }
        val root = GeminiJson.instance.parseToJsonElement(readResponse(connection).body).jsonObject
        root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstNotNullOfOrNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?: throw GeminiTransportException("Gemini returned no analysis text", retryable = false)
    }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        val connection = connection("https://generativelanguage.googleapis.com/v1beta/" + fileName + "?key=" + encoded(apiKey), "DELETE")
        readResponse(connection)
        Unit
    }

    private fun connection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            readTimeout = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val headers = connection.headerFields.entries.mapNotNull { (key, values) ->
            key?.lowercase()?.let { it to (values.firstOrNull() ?: "") }
        }.toMap()
        connection.disconnect()
        if (status !in 200..299) {
            throw GeminiTransportException(
                message = "Gemini API request failed with HTTP " + status,
                retryable = status >= 500,
                rateLimited = status == 429,
                httpStatus = status,
            )
        }
        return HttpResponse(body, headers)
    }

    private data class HttpResponse(val body: String, val headers: Map<String, String>)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

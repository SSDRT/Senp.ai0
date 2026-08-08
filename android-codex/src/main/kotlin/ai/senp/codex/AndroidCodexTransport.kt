package ai.senp.codex

import ai.senp.review.CodexResponse
import ai.senp.review.CodexTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Codex transport backed by the signed-in ChatGPT session.
 *
 * Uses [HttpURLConnection] rather than an SSE client on purpose: the endpoint streams events with no
 * `Content-Type` header, which off-the-shelf event-source clients reject outright. Reading raw lines
 * sidesteps that by construction and costs no dependency.
 */
class AndroidCodexTransport(
    private val auth: CodexAuth,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
) : CodexTransport {

    override suspend fun postSse(url: String, jsonBody: String): CodexResponse = withContext(Dispatchers.IO) {
        val session = auth.session()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            // High reasoning effort over several frames genuinely takes minutes.
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("chatgpt-account-id", session.accountId)
            setRequestProperty("OpenAI-Beta", "responses=experimental")
            setRequestProperty("originator", ORIGINATOR)
            // Not cosmetic: the backend picks an internal engine from originator + version + plan.
            // Omitting it routes 5.6 models to a deployment that does not exist for the caller's
            // cohort, which surfaces as an opaque "model not found".
            setRequestProperty("version", CLIENT_VERSION)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("session_id", newSessionId())
        }

        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            // ponytail: buffers the whole event stream. Yield incrementally once a UI renders tokens live.
            val lines = body?.bufferedReader()?.use { it.readLines() }.orEmpty()
            CodexResponse(status, lines.asSequence())
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ORIGINATOR = "codex_cli_rs"
        const val CLIENT_VERSION = "0.144.1"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 300_000
    }
}

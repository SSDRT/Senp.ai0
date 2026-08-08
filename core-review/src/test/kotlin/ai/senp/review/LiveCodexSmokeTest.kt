package ai.senp.review

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Manual end-to-end check against the real Codex endpoint. Skipped unless `SENP_LIVE=1`, so it never
 * runs in CI and never gates a build.
 *
 *   SENP_LIVE=1 ./gradlew :core-review:test --tests '*LiveCodexSmokeTest*' -i
 *
 * Signs in through the same loopback redirect the Android client uses — the flow is identical on a
 * laptop, which is the point: it validates the wire contract without an emulator. Reuse a session
 * across runs with SENP_CODEX_TOKEN and SENP_CODEX_ACCOUNT to skip the browser step.
 *
 * Sends one generated image (a red circle on white) and asks the model to name it. If the answer
 * comes back describing that shape, image input works end to end.
 */
class LiveCodexSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionCache = java.io.File("build/tmp/codex-session.json")

    @Test
    fun liveRoundTrip() {
        if (System.getenv("SENP_LIVE") != "1") {
            println("[live] skipped; set SENP_LIVE=1 to run")
            return
        }

        val session = System.getenv("SENP_CODEX_TOKEN")?.let { token ->
            Session(token, requireNotNull(System.getenv("SENP_CODEX_ACCOUNT")) {
                "SENP_CODEX_ACCOUNT must accompany SENP_CODEX_TOKEN"
            })
        } ?: cachedSession() ?: signIn()

        println("[live] account ${session.accountId.take(8)}…  (token withheld)")

        if (System.getenv("SENP_PROBE_MODELS") == "1") {
            probeModels(session)
            return
        }

        val request = FrameReviewRequest(
            systemPrompt = "You identify shapes. Answer in three words or fewer.",
            userContext = "Name the colour and shape in the image.",
            frames = listOf(ReviewFrame("test frame", 0, redCircleJpegBase64())),
            model = ReviewModel(effort = ReasoningEffort.LOW, imageDetail = ImageDetail.LOW),
        )

        val body = Codex.body(request)
        println("[live] request ${body.length} bytes -> ${Codex.ENDPOINT}")

        val (status, lines) = post(Codex.ENDPOINT, body, session)
        println("[live] HTTP $status, ${lines.size} lines")
        if (status != 200) {
            println("[live] body: ${lines.joinToString("\n").take(2_000)}")
        } else {
            println("[live] event types: " + lines.mapNotNull { eventType(it) }.distinct().joinToString())
        }

        when (val outcome = Codex.parse(lines.asSequence(), request.model.id)) {
            is ReviewOutcome.Success -> {
                println("[live] PASS text=${outcome.review.text.trim()}")
                println("[live] reasoning summary=${outcome.review.reasoningSummary.trim().ifEmpty { "(none)" }}")
            }

            is ReviewOutcome.Failure -> kotlin.test.fail(
                "live call failed: ${outcome.kind}: ${outcome.message}\n" +
                    "HTTP $status body: ${lines.joinToString("\n").take(1_000)}",
            )
        }
    }

    /**
     * Asks the account which models it can actually use. The entitled set differs from the platform
     * API's and moves over time, so this is measured rather than looked up.
     */
    private fun probeModels(session: Session) {
        val candidates = listOf(
            "gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6",
            "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-codex",
            "gpt-5.3-codex", "gpt-5.2-codex", "gpt-5.1-codex", "gpt-5-codex",
        )

        println("\n[probe] text-only request per model; 200 means the account may use it\n")
        val working = mutableListOf<String>()
        candidates.forEach { model ->
            val body = """
                {"model":"$model","instructions":"Reply with OK.",
                 "input":[{"role":"user","content":[{"type":"input_text","text":"OK"}]}],
                 "reasoning":{"effort":"low"},"stream":true,"store":false}
            """.trimIndent().replace("\n", "")

            val (status, lines) = runCatching { post(Codex.ENDPOINT, body, session) }
                .getOrElse { error -> -1 to listOf(error.message.orEmpty()) }
            val note = if (status == 200) "OK".also { working += model } else Codex.errorDetail(lines.asSequence())
                ?: lines.firstOrNull().orEmpty().take(120)
            println("[probe] %-20s %4d  %s".format(model, status, note))
        }

        println("\n[probe] usable models: ${working.ifEmpty { listOf("(none)") }.joinToString()}")
        println("[probe] set ReviewModels.CODEX_DEFAULT to one of these, then re-run without SENP_PROBE_MODELS\n")
    }

    private fun eventType(line: String): String? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return runCatching { json.parseToJsonElement(payload).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
    }

    private fun post(url: String, body: String, session: Session): Pair<Int, List<String>> {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("chatgpt-account-id", session.accountId)
            setRequestProperty("OpenAI-Beta", "responses=experimental")
            setRequestProperty("originator", "codex_cli_rs")
            setRequestProperty("version", "0.144.1")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("session_id", java.util.UUID.randomUUID().toString())
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            status to (stream?.bufferedReader()?.use { it.readLines() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    /** Same loopback PKCE flow as the Android client; the browser step is manual here. */
    private fun signIn(): Session {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(32)

        val authorize = buildString {
            append("https://auth.openai.com/oauth/authorize?response_type=code")
            append("&client_id=").append(encode(CLIENT_ID))
            append("&redirect_uri=").append(encode(REDIRECT_URI))
            append("&scope=").append(encode("openid profile email offline_access"))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
            // SENP_MINIMAL_AUTH=1 drops the Codex-specific extras to isolate whether one of them is
            // what stalls the consent step.
            if (System.getenv("SENP_MINIMAL_AUTH") != "1") {
                append("&id_token_add_organizations=true")
                append("&codex_cli_simplified_flow=true")
                append("&originator=codex_cli_rs")
            }
        }

        // Copying this URL out of a console wraps it and corrupts it, so open it directly and also
        // drop it in a file for a clean manual copy if the launch fails.
        val urlFile = java.io.File("build/tmp/authorize-url.txt").apply {
            parentFile?.mkdirs()
            writeText(authorize)
        }
        println("\n[live] opening your default browser to sign in.")
        println("[live] if nothing opens, copy the URL from: ${urlFile.absolutePath}\n")
        openBrowser(authorize)

        ServerSocket(1455, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 900_000
            // Browsers fire preconnects and favicon probes at this port. Keep accepting until the
            // request that actually carries the OAuth result shows up.
            var query: Map<String, String>
            while (true) {
                val candidate = server.accept().use { socket ->
                    val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val parsed = target.substringAfter('?', "")
                        .split('&').filter { it.contains('=') }.associate { pair ->
                            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                                URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                        }
                    val isResult = parsed.containsKey("code") || parsed.containsKey("error")
                    socket.getOutputStream().use { out ->
                        out.write(
                            if (isResult) {
                                ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n" +
                                    "<html><body><h3>Signed in. Return to the terminal.</h3></body></html>")
                                    .toByteArray(Charsets.UTF_8)
                            } else {
                                "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8)
                            },
                        )
                        out.flush()
                    }
                    if (isResult) parsed else null.also { println("[live] ignoring probe: $target") }
                }
                if (candidate != null) {
                    query = candidate
                    break
                }
            }

            require(query["state"] == state) { "OAuth state mismatch" }
            val code = requireNotNull(query["code"]) { "no authorization code: ${query["error"]}" }

            val form = listOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ).joinToString("&") { (key, value) -> "$key=${encode(value)}" }

            val connection = (URI("https://auth.openai.com/oauth/token").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val payload = try {
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                check(connection.responseCode == 200) {
                    "token endpoint ${connection.responseCode}: " +
                        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val root = json.parseToJsonElement(payload).jsonObject
            val accessToken = requireNotNull(root["access_token"]?.jsonPrimitive?.content)
            val idToken = requireNotNull(root["id_token"]?.jsonPrimitive?.content)
            val expiresAtMs = System.currentTimeMillis() +
                (root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3_600L) * 1_000L
            val session = Session(
                accessToken,
                requireNotNull(accountIdFrom(idToken)) { "no chatgpt_account_id claim" },
            )
            cacheSession(session, expiresAtMs)
            return session
        }
    }

    /**
     * Caches the session so one sign-in covers repeated runs. Holds a live access token in plain
     * text under the gitignored build directory; it expires within hours and can be deleted freely.
     */
    private fun cacheSession(session: Session, expiresAtMs: Long) {
        runCatching {
            sessionCache.parentFile?.mkdirs()
            sessionCache.writeText(
                """{"accessToken":"${session.accessToken}","accountId":"${session.accountId}",""" +
                    """"expiresAtMs":$expiresAtMs}""",
            )
            println("[live] session cached until ${java.time.Instant.ofEpochMilli(expiresAtMs)}")
        }
    }

    private fun cachedSession(): Session? {
        if (!sessionCache.exists()) return null
        val root = runCatching { json.parseToJsonElement(sessionCache.readText()).jsonObject }.getOrNull()
            ?: return null
        val expiresAtMs = root["expiresAtMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() > expiresAtMs - 60_000) {
            println("[live] cached session expired; signing in again")
            return null
        }
        val token = root["accessToken"]?.jsonPrimitive?.content ?: return null
        val account = root["accountId"]?.jsonPrimitive?.content ?: return null
        println("[live] reusing cached session (no browser needed)")
        return Session(token, account)
    }

    private fun openBrowser(url: String) {
        System.getenv("SENP_BROWSER")?.let { browser ->
            runCatching { ProcessBuilder(browser, url).start() }
                .onSuccess { println("[live] launched $browser"); return }
        }
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            check(desktop.isSupported(java.awt.Desktop.Action.BROWSE)) { "BROWSE unsupported" }
            desktop.browse(URI(url))
        }.recoverCatching {
            // Headless test JVMs land here; rundll32 hands the URL to the default browser intact.
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
        }.onFailure { error ->
            println("[live] could not launch a browser (${error.message}); copy the URL from the file above")
        }
    }

    private fun accountIdFrom(idToken: String): String? {
        val claims = json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(idToken.split('.')[1].padEnd((idToken.split('.')[1].length + 3) / 4 * 4, '='))),
        ).jsonObject
        return runCatching {
            claims["https://api.openai.com/auth"]?.jsonObject?.get("chatgpt_account_id")?.jsonPrimitive?.content
        }.getOrNull() ?: runCatching { claims["chatgpt_account_id"]?.jsonPrimitive?.content }.getOrNull()
    }

    private fun redCircleJpegBase64(): String {
        val image = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, 256, 256)
            color = Color.RED
            fillOval(48, 48, 160, 160)
            dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private data class Session(val accessToken: String, val accountId: String)

    private companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val REDIRECT_URI = "http://localhost:1455/auth/callback"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun randomUrlSafe(byteCount: Int): String =
            base64Url(ByteArray(byteCount).also(SecureRandom()::nextBytes))
    }
}

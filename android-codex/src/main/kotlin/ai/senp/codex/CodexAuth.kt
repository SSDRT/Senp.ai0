package ai.senp.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ChatGPT sign-in for the Codex backend.
 *
 * Same OAuth client the Codex CLI uses, so no CLI binary and no proxy process is involved. The
 * redirect is a loopback listener rather than a custom scheme: the browser and this process share
 * the device, and loopback is the redirect the public client is registered for.
 */
class CodexAuth(context: Context) {
    private val appContext = context.applicationContext
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val store by lazy {
        EncryptedSharedPreferences.create(
            "codex-session",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val isSignedIn: Boolean get() = store.contains(KEY_REFRESH)

    /**
     * Runs the full sign-in. Opens the system browser and suspends until the redirect lands or
     * [timeoutMs] elapses. Must not be called from the main thread's critical path; it blocks on a
     * socket accept internally and is dispatched to IO.
     */
    suspend fun signIn(timeoutMs: Int = 120_000): CodexSession = withContext(Dispatchers.IO) {
        val verifier = randomUrlSafe(64)
        val challenge = base64Url(sha256(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(32)

        ServerSocket(REDIRECT_PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = timeoutMs
            openBrowser(authorizeUrl(challenge, state))

            val query = server.awaitRedirect()
            require(query["state"] == state) { "OAuth state mismatch; aborting sign-in" }
            query["error"]?.let { error -> throw IOException("Sign-in rejected: $error") }
            val code = requireNotNull(query["code"]) { "Redirect carried no authorization code" }

            persist(
                exchange(
                    "grant_type" to "authorization_code",
                    "client_id" to CLIENT_ID,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier,
                ),
            )
        }
    }

    /**
     * Returns a usable session, refreshing first when the access token is close to expiry.
     *
     * Refreshes behind a lock: several frames reviewed concurrently would otherwise fire parallel
     * refreshes and race each other into an invalidated refresh token.
     */
    suspend fun session(): CodexSession = refreshLock.withLock {
        val current = stored() ?: throw IllegalStateException("Not signed in to ChatGPT")
        if (System.currentTimeMillis() < current.expiresAtMs - REFRESH_SKEW_MS) return@withLock current

        withContext(Dispatchers.IO) {
            persist(
                exchange(
                    "grant_type" to "refresh_token",
                    "client_id" to CLIENT_ID,
                    "refresh_token" to current.refreshToken,
                    "scope" to SCOPE,
                ),
                fallbackRefreshToken = current.refreshToken,
                fallbackAccountId = current.accountId,
            )
        }
    }

    fun signOut() = store.edit().clear().apply()

    private fun stored(): CodexSession? {
        val access = store.getString(KEY_ACCESS, null) ?: return null
        val refresh = store.getString(KEY_REFRESH, null) ?: return null
        val account = store.getString(KEY_ACCOUNT, null) ?: return null
        return CodexSession(access, refresh, account, store.getLong(KEY_EXPIRES, 0L))
    }

    private fun persist(
        response: TokenResponse,
        fallbackRefreshToken: String? = null,
        fallbackAccountId: String? = null,
    ): CodexSession {
        // A refresh response often omits both; keeping the previous values avoids a spurious sign-out.
        val session = CodexSession(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: fallbackRefreshToken
                ?: throw IOException("Token response carried no refresh token"),
            accountId = response.accountId ?: fallbackAccountId
                ?: throw IOException("Token response carried no ChatGPT account id"),
            expiresAtMs = System.currentTimeMillis() + response.expiresInSeconds * 1_000L,
        )
        store.edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putString(KEY_ACCOUNT, session.accountId)
            .putLong(KEY_EXPIRES, session.expiresAtMs)
            .apply()
        return session
    }

    private fun exchange(vararg form: Pair<String, String>): TokenResponse {
        val body = form.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("Token endpoint returned ${connection.responseCode}: $detail")
            }
            val payload = connection.inputStream.bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(payload).jsonObject
            val idToken = root["id_token"]?.jsonPrimitive?.content
            return TokenResponse(
                accessToken = requireNotNull(root["access_token"]?.jsonPrimitive?.content) {
                    "Token response carried no access token"
                },
                refreshToken = root["refresh_token"]?.jsonPrimitive?.content,
                accountId = idToken?.let(::accountIdFrom),
                expiresInSeconds = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3_600L,
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Pulls `chatgpt_account_id` out of the id token. Namespaced in current tokens, bare in older ones. */
    private fun accountIdFrom(idToken: String): String? {
        val segments = idToken.split('.')
        if (segments.size < 2) return null
        val claims = runCatching {
            json.parseToJsonElement(
                String(Base64.decode(segments[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)),
            ).jsonObject
        }.getOrNull() ?: return null

        return runCatching {
            claims["https://api.openai.com/auth"]?.jsonObject?.get("chatgpt_account_id")?.jsonPrimitive?.content
        }.getOrNull() ?: runCatching { claims["chatgpt_account_id"]?.jsonPrimitive?.content }.getOrNull()
    }

    private fun authorizeUrl(challenge: String, state: String): String = buildString {
        append(AUTHORIZE_URL)
        append("?response_type=code")
        append("&client_id=").append(encode(CLIENT_ID))
        append("&redirect_uri=").append(encode(REDIRECT_URI))
        append("&scope=").append(encode(SCOPE))
        append("&code_challenge=").append(challenge)
        append("&code_challenge_method=S256")
        append("&state=").append(state)
        append("&id_token_add_organizations=true")
        append("&codex_cli_simplified_flow=true")
        append("&originator=codex_cli_rs")
    }

    // ponytail: system browser via ACTION_VIEW. Chrome Custom Tabs is nicer but costs a dependency.
    private fun openBrowser(url: String) {
        appContext.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Accepts the browser's redirect and answers it, so the user sees a closed loop rather than an error page. */
    private fun ServerSocket.awaitRedirect(): Map<String, String> {
        accept().use { socket ->
            val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            socket.getOutputStream().use { out ->
                out.write(REDIRECT_RESPONSE.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            return target.substringAfter('?', "")
                .split('&')
                .filter { it.contains('=') }
                .associate { pair ->
                    Uri.decode(pair.substringBefore('=')) to Uri.decode(pair.substringAfter('='))
                }
        }
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String?,
        val accountId: String?,
        val expiresInSeconds: Long,
    )

    private companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val SCOPE = "openid profile email offline_access"
        const val REDIRECT_PORT = 1455
        const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT/auth/callback"

        /** Access tokens live hours; refresh a minute early so an in-flight review never straddles expiry. */
        const val REFRESH_SKEW_MS = 60_000L

        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCOUNT = "account_id"
        const val KEY_EXPIRES = "expires_at_ms"

        val REDIRECT_RESPONSE = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Connection: close\r\n\r\n")
            append("<html><body><h3>Signed in. Return to Senp.</h3></body></html>")
        }

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun randomUrlSafe(byteCount: Int): String =
            base64Url(ByteArray(byteCount).also(SecureRandom()::nextBytes))
    }
}

data class CodexSession(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val expiresAtMs: Long,
)

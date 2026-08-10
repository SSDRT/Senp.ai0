package ai.senp.validation

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GeminiReviewModelOption(
    val id: String,
    val title: String,
    val detail: String,
)

data class AiReviewSettingsUiState(
    val apiKeyConfigured: Boolean,
    val modelId: String,
    val availableModels: List<GeminiReviewModelOption>,
    val isLoadingModels: Boolean = false,
    val modelLoadError: String? = null,
)

internal data class AiReviewSettings(
    val apiKey: String,
    val modelId: String,
)

object GeminiReviewModels {
    const val DEFAULT_MODEL = "gemini-3.6-flash"

    fun normalize(modelId: String): String = modelId.trim()
        .removePrefix("models/")
        .ifBlank { DEFAULT_MODEL }

    fun title(modelId: String): String = normalize(modelId)
        .removePrefix("gemini-")
        .split('-')
        .joinToString(" ") { token -> token.replaceFirstChar { it.uppercase() } }
        .let { "Gemini $it" }

    fun seed(modelId: String): List<GeminiReviewModelOption> {
        val id = normalize(modelId)
        return listOf(
            GeminiReviewModelOption(
                id = id,
                title = title(id),
                detail = if (id == DEFAULT_MODEL) "Default model" else "Saved model",
            ),
        )
    }
}

internal class AiReviewSettingsStore(context: Context) {
    private val appContext = context.applicationContext

    private val store by lazy {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun snapshot(): AiReviewSettings = AiReviewSettings(
        apiKey = store.getString(KEY_API_KEY, "").orEmpty().trim(),
        modelId = GeminiReviewModels.normalize(
            store.getString(KEY_MODEL_ID, GeminiReviewModels.DEFAULT_MODEL).orEmpty(),
        ),
    )

    fun uiState(): AiReviewSettingsUiState = snapshot().let { settings ->
        AiReviewSettingsUiState(
            apiKeyConfigured = settings.apiKey.isNotBlank(),
            modelId = settings.modelId,
            availableModels = GeminiReviewModels.seed(settings.modelId),
        )
    }

    fun saveApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        require(normalized.isNotBlank()) { "Gemini API key must not be blank." }
        store.edit().putString(KEY_API_KEY, normalized).apply()
    }

    fun clearApiKey() {
        store.edit().remove(KEY_API_KEY).apply()
    }

    fun saveModel(modelId: String) {
        store.edit().putString(KEY_MODEL_ID, GeminiReviewModels.normalize(modelId)).apply()
    }

    private companion object {
        const val PREFS_NAME = "ai-review-settings-v1"
        const val KEY_API_KEY = "gemini-api-key"
        const val KEY_MODEL_ID = "gemini-model-id"
    }
}

internal object GeminiModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(apiKey: String): List<GeminiReviewModelOption> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Save a Gemini API key before loading models." }
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=$encodedKey")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Could not load Gemini models (HTTP $status). Check the API key and network.")
            }
            val root = json.parseToJsonElement(body).jsonObject
            root["models"]?.jsonArray.orEmpty().mapNotNull { element ->
                val model = element.jsonObject
                val methods = model["supportedGenerationMethods"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                if ("generateContent" !in methods) return@mapNotNull null
                val id = model["name"]?.jsonPrimitive?.contentOrNull
                    ?.removePrefix("models/")
                    ?.takeIf { it.startsWith("gemini-") }
                    ?: return@mapNotNull null
                val title = model["displayName"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: GeminiReviewModels.title(id)
                val description = model["description"]?.jsonPrimitive?.contentOrNull
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.take(110)
                    ?.takeIf(String::isNotBlank)
                    ?: "Available for Gemini generateContent"
                GeminiReviewModelOption(id = id, title = title, detail = description)
            }
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<GeminiReviewModelOption> { it.id.contains("flash") }
                        .thenByDescending { it.id }
                )
        } finally {
            connection.disconnect()
        }
    }
}

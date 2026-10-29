package dev.opencode.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Провайдер модели. kind:
 *  - "openai"    — любой OpenAI-совместимый endpoint (OpenAI, OpenRouter, Groq, LM Studio, Ollama /v1, ...)
 *  - "anthropic" — Anthropic Messages API
 */
data class Provider(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: String = "openai",
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val extraModels: List<String> = emptyList()
) {
    val models: List<String> get() = (listOf(model) + extraModels).distinct().filter { it.isNotBlank() }

    /** Switching models must not discard the previously selected model. */
    fun withModel(selected: String): Provider {
        val value = selected.trim()
        if (value.isEmpty()) return this
        return copy(model = value, extraModels = models.filterNot { it == value })
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("kind", kind)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("extraModels", JSONArray(extraModels))

    companion object {
        fun fromJson(o: JSONObject): Provider {
            val extra = mutableListOf<String>()
            o.optJSONArray("extraModels")?.let { arr ->
                for (i in 0 until arr.length()) extra.add(arr.optString(i))
            }
            return Provider(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Provider"),
                kind = o.optString("kind", "openai"),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", ""),
                extraModels = extra
            )
        }

        fun defaults(): List<Provider> = listOf(
            Provider(
                name = "OpenAI",
                kind = "openai",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o",
                extraModels = listOf("gpt-4o-mini", "gpt-4.1", "o4-mini")
            ),
            Provider(
                name = "Anthropic",
                kind = "anthropic",
                baseUrl = "https://api.anthropic.com",
                model = "claude-sonnet-4-5",
                extraModels = listOf("claude-opus-4-1", "claude-haiku-4-5")
            ),
            Provider(
                name = "OpenRouter",
                kind = "openai",
                baseUrl = "https://openrouter.ai/api/v1",
                model = "anthropic/claude-sonnet-4.5",
                extraModels = listOf("openai/gpt-4o", "google/gemini-2.5-pro", "qwen/qwen3-coder")
            ),
            Provider(
                name = "Ollama (локально)",
                kind = "openai",
                baseUrl = "http://127.0.0.1:11434/v1",
                model = "qwen2.5-coder:7b",
                extraModels = listOf("deepseek-coder-v2:16b", "llama3.1:8b")
            ),
            Provider(
                name = "LM Studio (LAN)",
                kind = "openai",
                baseUrl = "http://192.168.1.100:1234/v1",
                model = "local-model"
            )
        )
    }
}

data class AppSettings(
    val providers: List<Provider> = Provider.defaults(),
    val activeProviderId: String = providers.first().id,
    val allowRoot: Boolean = false,
    val autoApproveTools: Boolean = false,
    val maxIterations: Int = 40,
    val systemAppend: String = "",
    val lastWorkspace: String = "/sdcard/OpenCode",
    val streaming: Boolean = true,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val fontSize: Int = 13,
    val lastSessionId: String? = null
) {
    val activeProvider: Provider
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.firstOrNull()
            ?: Provider.defaults().first()

    /** Change provider and model in one update, preserving all other providers and keys. */
    fun withSelectedModel(providerId: String, model: String): AppSettings {
        if (model.isBlank() || providers.none { it.id == providerId }) return this
        return copy(
            activeProviderId = providerId,
            providers = providers.map { if (it.id == providerId) it.withModel(model) else it }
        )
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        providers.forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("providers", arr)
            .put("activeProviderId", activeProviderId)
            .put("allowRoot", allowRoot)
            .put("autoApproveTools", autoApproveTools)
            .put("maxIterations", maxIterations)
            .put("systemAppend", systemAppend)
            .put("lastWorkspace", lastWorkspace)
            .put("streaming", streaming)
            .put("reasoningLevel", reasoningLevel.name)
            .put("fontSize", fontSize)
            .put("lastSessionId", lastSessionId ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val list = mutableListOf<Provider>()
            o.optJSONArray("providers")?.let { arr ->
                for (i in 0 until arr.length()) list.add(Provider.fromJson(arr.getJSONObject(i)))
            }
            val providers = if (list.isEmpty()) Provider.defaults() else list
            val lastSession = o.optString("lastSessionId", "")
            return AppSettings(
                providers = providers,
                activeProviderId = o.optString("activeProviderId", providers.first().id),
                allowRoot = o.optBoolean("allowRoot", false),
                autoApproveTools = o.optBoolean("autoApproveTools", false),
                maxIterations = o.optInt("maxIterations", 40),
                systemAppend = o.optString("systemAppend", ""),
                lastWorkspace = o.optString("lastWorkspace", "/sdcard/OpenCode"),
                streaming = o.optBoolean("streaming", true),
                reasoningLevel = ReasoningLevel.from(o.optString("reasoningLevel", "OFF")),
                fontSize = o.optInt("fontSize", 13),
                lastSessionId = lastSession.ifBlank { null }
            )
        }
    }
}

/** Settings are encrypted as one authenticated record, including provider API keys. */
class Prefs(
    context: Context,
    preferenceName: String = "opencode_settings",
    keyAlias: String = "opencode_settings_aes_v1"
) {
    private val sp = context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val cipher = SettingsCipher(keyAlias)
    private val lock = Any()
    private var persistenceBlocked = false
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun failClosed() {
        // Do not overwrite the old ciphertext/plaintext if the keystore becomes unusable.
        persistenceBlocked = true
        _storageError.value = "Не удалось защитить или прочитать настройки. Изменения действуют только до закрытия приложения; сохранённые данные не перезаписаны."
    }

    private fun load(): AppSettings {
        val encrypted = sp.getString(ENCRYPTED_KEY, null)
        if (encrypted != null) {
            return try {
                AppSettings.fromJson(JSONObject(cipher.decrypt(encrypted)))
            } catch (_: Exception) {
                failClosed()
                AppSettings()
            }
        }
        val legacy = sp.getString(LEGACY_KEY, null)
        if (legacy.isNullOrBlank()) return AppSettings()
        val previous = try {
            AppSettings.fromJson(JSONObject(legacy))
        } catch (_: Exception) {
            failClosed()
            return AppSettings()
        }
        try {
            val protected = cipher.encrypt(legacy)
            check(sp.edit().putString(ENCRYPTED_KEY, protected).remove(LEGACY_KEY).commit())
        } catch (_: Exception) {
            // Keep the legacy record intact so the user's settings are not lost.
            failClosed()
        }
        return previous
    }

    fun update(transform: (AppSettings) -> AppSettings) = synchronized(lock) {
        val next = transform(_settings.value)
        if (!persistenceBlocked) {
            try {
                val protected = cipher.encrypt(next.toJson().toString())
                check(sp.edit().putString(ENCRYPTED_KEY, protected).remove(LEGACY_KEY).commit())
            } catch (_: Exception) {
                failClosed()
            }
        }
        _settings.value = next
    }

    private companion object {
        const val LEGACY_KEY = "settings_v1"
        const val ENCRYPTED_KEY = "settings_v2_encrypted"
    }
}

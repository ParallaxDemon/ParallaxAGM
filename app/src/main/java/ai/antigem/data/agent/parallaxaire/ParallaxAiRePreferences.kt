package ai.antigem.data.agent.parallaxaire

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection settings for Parallax AI RE. Defaults point at the on-LAN llama-server running on the
 * other phone (OpenAI-compatible /v1 endpoint).
 */
class ParallaxAiRePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_KEY, DEFAULT_KEY) ?: DEFAULT_KEY)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _systemPrompt = MutableStateFlow(prefs.getString(KEY_SYSTEM, DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM)
    val systemPrompt: StateFlow<String> = _systemPrompt.asStateFlow()

    fun setConnection(base: String, key: String, model: String) {
        val cleanBase = base.trim().trimEnd('/').ifEmpty { DEFAULT_BASE }
        val cleanKey = key.trim()
        val cleanModel = model.trim().ifEmpty { DEFAULT_MODEL }
        prefs.edit()
            .putString(KEY_BASE, cleanBase)
            .putString(KEY_KEY, cleanKey)
            .putString(KEY_MODEL, cleanModel)
            .apply()
        _baseUrl.value = cleanBase
        _apiKey.value = cleanKey
        _model.value = cleanModel
    }

    fun setSystemPrompt(prompt: String) {
        val clean = prompt.trim().ifEmpty { DEFAULT_SYSTEM }
        prefs.edit().putString(KEY_SYSTEM, clean).apply()
        _systemPrompt.value = clean
    }

    companion object {
        private const val PREFS = "parallax_ai_re_prefs"
        private const val KEY_BASE = "base_url"
        private const val KEY_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM = "system_prompt"

        const val DEFAULT_BASE = "http://10.166.8.221:8080/v1"
        const val DEFAULT_KEY = ""
        const val DEFAULT_MODEL = "Huihui-Qwen3.5-4B-abliterated.Q4_K_M.gguf"
        const val DEFAULT_SYSTEM =
            "You are Parallax AI RE, a full-access reverse-engineering and phone automation agent running inside the ParallaxAGM Android app. " +
            "You have direct access to phone tools (shell, file read/write/edit, search, web fetch). " +
            "Be precise, action-oriented, and safety-conscious. Before destructive actions, explain impact briefly. " +
            "When using tools, prefer concrete commands and verify outcomes."
    }
}

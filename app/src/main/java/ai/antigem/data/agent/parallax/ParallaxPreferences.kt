package ai.antigem.data.agent.parallax

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connection settings for the ParallaxAI agent. Defaults point at the public panel; the key can be replaced in Settings. */
class ParallaxPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _baseUrl = MutableStateFlow(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_KEY, DEFAULT_KEY) ?: DEFAULT_KEY)
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _timeoutSeconds = MutableStateFlow(prefs.getInt(KEY_TIMEOUT, 900))
    val timeoutSeconds: StateFlow<Int> = _timeoutSeconds.asStateFlow()

    private val _historyLimit = MutableStateFlow(prefs.getInt(KEY_HISTORY, 12))
    /** How many previous messages are sent as context with each chat (continuous conversations). */
    val historyLimit: StateFlow<Int> = _historyLimit.asStateFlow()

    fun setConnection(base: String, key: String) {
        val cleanBase = base.trim().trimEnd('/').ifEmpty { DEFAULT_BASE }
        val cleanKey = key.trim().ifEmpty { DEFAULT_KEY }
        prefs.edit().putString(KEY_BASE, cleanBase).putString(KEY_KEY, cleanKey).apply()
        _baseUrl.value = cleanBase
        _apiKey.value = cleanKey
    }

    fun setTimeout(seconds: Int) {
        val value = seconds.coerceIn(60, 3600)
        prefs.edit().putInt(KEY_TIMEOUT, value).apply()
        _timeoutSeconds.value = value
    }

    fun setHistoryLimit(limit: Int) {
        val value = limit.coerceIn(0, 40)
        prefs.edit().putInt(KEY_HISTORY, value).apply()
        _historyLimit.value = value
    }

    companion object {
        private const val PREFS = "parallax_ai_prefs"
        private const val KEY_BASE = "base_url"
        private const val KEY_KEY = "api_key"
        private const val KEY_TIMEOUT = "timeout_seconds"
        private const val KEY_HISTORY = "history_limit"

        const val DEFAULT_BASE = "https://ai.parallaxserver.online"
        const val DEFAULT_KEY = "b451dbad82be9edfd821db3b960f3c5d5b404af7290f329bbfcb87dd0133fc87"
    }
}

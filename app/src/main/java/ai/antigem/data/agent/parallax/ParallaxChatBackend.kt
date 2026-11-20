package ai.antigem.data.agent.parallax

import android.app.Application
import android.util.Log
import ai.antigem.data.agent.AgentChatBackend
import ai.antigem.data.agent.ChatSessionStore
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.AiModel
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ParallaxAI chat backend. Jobs are dispatched to the ParallaxAI panel API; the GitHub Actions worker does the work
 * with its tools. To keep a conversation continuous, the recent turns of the chat are sent as context with every new
 * prompt. Conversations and messages are stored locally, so switching chats or restarting the app keeps the history.
 */
class ParallaxChatBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: ParallaxPreferences
) : AgentChatBackend {

    override val kind = AgentKind.PARALLAXAI

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    /** A turn of the chat on screen is running. */
    val isCurrentChatActivelyRunning: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /** The panel has no model picker in the app; the worker uses its configured DeepSeek model. */
    val availableModels: StateFlow<List<AiModel>> = MutableStateFlow<List<AiModel>>(emptyList()).asStateFlow()
    val isRefreshingModels: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    val selectedModelId: StateFlow<String> = MutableStateFlow("deepseek-r1-distill-1.5b").asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val storage = application.applicationContext.getSharedPreferences("parallax_agent_chats", android.content.Context.MODE_PRIVATE)
    private var activeJob: Job? = null

    init {
        loadChats()
        if (_conversations.value.isEmpty()) startNewChat()
        else selectConversation(_conversations.value.first().id)
    }

    // ---------------------------------------------------------------- chat lifecycle

    override fun startNewChat() {
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            agent = AgentKind.PARALLAXAI,
            modelId = selectedModelId.value
        )
        _conversations.value = listOf(conv) + _conversations.value
        store.currentConversation.value = conv
        store.conversations.value = allConversations()
        _messages.value = emptyList()
        persistMessages(conv.id, emptyList())
        persistChats()
        _conversationError.value = null
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        store.currentConversation.value = conv
        store.conversations.value = allConversations()
        _messages.value = loadMessages(id)
        _conversationError.value = null
    }

    /** Used by ChatViewModel when switching to another agent. */
    fun detach() {
        activeJob?.cancel()
        activeJob = null
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        activeJob?.takeIf { store.currentConversation.value?.id == id }?.cancel()
        _conversations.value = _conversations.value.filterNot { it.id == id }
        storage.edit().remove("messages_$id").apply()
        persistChats()
        if (store.currentConversation.value?.id == id) {
            _conversations.value.firstOrNull()?.let { selectConversation(it.id) } ?: startNewChat()
        } else {
            store.conversations.value = allConversations()
        }
    }

    override fun forkConversation(id: String) {
        val source = _conversations.value.firstOrNull { it.id == id } ?: return
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            title = source.title + " (copy)",
            parentConversationId = source.id,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            isRunning = false
        )
        _conversations.value = listOf(copy) + _conversations.value
        persistMessages(copy.id, loadMessages(id))
        persistChats()
        selectConversation(copy.id)
    }

    fun renameConversation(id: String, title: String) {
        updateConversation(id) { it.copy(title = title, updatedAt = System.currentTimeMillis()) }
    }

    fun setArchived(id: String, archived: Boolean) {
        updateConversation(id) { it.copy(archived = archived) }
    }

    /** Messages of a chat that is not open (sidebar export / share). */
    fun messagesOf(id: String): List<ChatMessage> = loadMessages(id)

    override fun retryConnections() {
        _conversationError.value = null
    }

    override fun onAppForegrounded() {
        loadChats()
        store.conversations.value = allConversations()
    }

    override fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        _isStreaming.value = false
        val conv = store.currentConversation.value ?: return
        updateConversation(conv.id) { it.copy(isRunning = false) }
        _messages.value = _messages.value.map {
            if (it.isStreaming) it.copy(content = it.content.ifBlank { "Stopped." }, isStreaming = false) else it
        }
    }

    // ---------------------------------------------------------------- sending

    override fun sendMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty()) return
        var conv = store.currentConversation.value
        if (conv == null || conv.agent != AgentKind.PARALLAXAI) {
            startNewChat()
            conv = store.currentConversation.value!!
        }
        val conversation = store.currentConversation.value!!
        if (_isStreaming.value) {
            _conversationError.value = "A reply is still running. Wait or stop it before sending another message."
            return
        }
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            conversationId = conversation.id,
            role = MessageRole.USER,
            content = text,
            createdAt = now
        )
        val placeholder = ChatMessage(
            conversationId = conversation.id,
            role = MessageRole.ASSISTANT,
            content = "Queued…",
            isStreaming = true,
            createdAt = now + 1
        )
        val updated = _messages.value + listOf(userMessage, placeholder)
        _messages.value = updated
        persistMessages(conversation.id, updated)
        val titled = if (conversation.title == "New Chat") text.take(48) else conversation.title
        updateConversation(conversation.id) { it.copy(title = titled, isRunning = true, updatedAt = now) }
        _conversationError.value = null
        _isStreaming.value = true

        activeJob = backendScope.launch {
            try {
                val prompt = buildPrompt(text, updated.dropLast(1), prefs.historyLimit.value)
                val reply = withContext(Dispatchers.IO) { submitAndWait(prompt, conversation.id) }
                finishTurn(conversation.id, placeholder.id, reply, failed = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishTurn(conversation.id, placeholder.id, "Stopped.", failed = false)
            } catch (e: Exception) {
                Log.e(TAG, "ParallaxAI chat failed", e)
                finishTurn(conversation.id, placeholder.id, "ParallaxAI error: ${e.message}", failed = true)
                _conversationError.value = e.message
            }
        }
    }

    /** Sends the new prompt together with the recent turns so the model keeps the thread. */
    private fun buildPrompt(text: String, history: List<ChatMessage>, limit: Int): String {
        val recent = if (limit <= 0) emptyList() else history.takeLast(limit)
        if (recent.isEmpty()) return text
        val context = recent.joinToString("\n") { m ->
            (if (m.role == MessageRole.USER) "User" else "Assistant") + ": " + m.content.take(2000)
        }
        return "Continuing the same conversation. Previous turns:\n$context\n\nUser: $text"
    }

    private fun submitAndWait(prompt: String, conversationId: String): String {
        val base = prefs.baseUrl.value.trim().trimEnd('/')
        val key = prefs.apiKey.value.trim()
        val jobJson = JSONObject().put("kind", "chat").put("prompt", prompt).toString()
        val client = http.newBuilder().callTimeout(prefs.timeoutSeconds.value.toLong(), TimeUnit.SECONDS).build()
        val post = Request.Builder()
            .url("$base/api/v1/jobs")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(jobJson.toRequestBody("application/json".toMediaType()))
            .build()
        val jobId = client.newCall(post).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${body.take(300)}")
            JSONObject(body).optString("id").also {
                if (it.isEmpty()) throw IllegalStateException("Panel did not return a job id: ${body.take(300)}")
            }
        }
        onProgress(conversationId, "Sent ($jobId) — waiting…")

        val deadline = System.currentTimeMillis() + prefs.timeoutSeconds.value * 1000L
        var lastStatus = ""
        while (System.currentTimeMillis() < deadline) {
            val get = Request.Builder()
                .url("$base/api/v1/jobs/$jobId")
                .header("Authorization", "Bearer $key")
                .get().build()
            val body = client.newCall(get).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${text.take(200)}")
                text
            }
            val job = JSONObject(body)
            when (val status = job.optString("status")) {
                "done" -> return job.optString("result").ifEmpty { "(empty reply)" }
                "failed", "dispatch_failed" -> throw IllegalStateException(
                    job.optString("error").ifEmpty { "job $status" }
                )
                else -> {
                    if (status != lastStatus) {
                        lastStatus = status
                        onProgress(conversationId, status.replaceFirstChar { it.uppercase() } + "… ($jobId)")
                    }
                    Thread.sleep(2000)
                }
            }
        }
        throw IllegalStateException("Timed out waiting for $jobId")
    }

    private fun onProgress(conversationId: String, text: String) {
        if (store.currentConversation.value?.id != conversationId) return
        _messages.value = _messages.value.map { if (it.isStreaming) it.copy(content = text) else it }
    }

    private fun finishTurn(conversationId: String, placeholderId: String, text: String, failed: Boolean) {
        if (store.currentConversation.value?.id == conversationId) {
            val final = _messages.value.map { m ->
                if (m.id == placeholderId) m.copy(content = text, isStreaming = false, modelId = selectedModelId.value)
                else m
            }
            _messages.value = final
            persistMessages(conversationId, final)
            _isStreaming.value = false
            _conversationError.value = if (failed) text else null
        }
        updateConversation(conversationId) { it.copy(isRunning = false, updatedAt = System.currentTimeMillis()) }
        activeJob = null
    }

    private fun updateConversation(id: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == id) transform(it) else it }
        store.conversations.value = allConversations()
        persistChats()
    }

    private fun allConversations(): List<Conversation> = _conversations.value

    // ---------------------------------------------------------------- local storage

    private fun loadChats() {
        val raw = storage.getString("chats", null) ?: return
        runCatching {
            val arr = JSONObject(raw).optJSONArray("chats") ?: return
            _conversations.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Conversation(
                    id = o.optString("id"),
                    title = o.optString("title", "New Chat"),
                    agent = AgentKind.PARALLAXAI,
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt", o.optLong("createdAt"))
                ).takeIf { it.id.isNotEmpty() }
            }
        }.onFailure { Log.w(TAG, "Could not load ParallaxAI chats", it) }
    }

    private fun persistChats() {
        val arr = JSONArray()
        _conversations.value.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("createdAt", c.createdAt)
                .put("updatedAt", c.updatedAt))
        }
        storage.edit().putString("chats", JSONObject().put("chats", arr).toString()).apply()
    }

    private fun loadMessages(id: String): List<ChatMessage> {
        val raw = storage.getString("messages_$id", null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("messages") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val role = runCatching { MessageRole.valueOf(o.optString("role")) }.getOrDefault(MessageRole.USER)
                ChatMessage(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    conversationId = id,
                    role = role,
                    content = o.optString("content"),
                    createdAt = o.optLong("createdAt"),
                    modelId = o.optString("modelId").ifEmpty { null }
                )
            }
        }.getOrElse { Log.w(TAG, "Could not load ParallaxAI messages", it); emptyList() }
    }

    private fun persistMessages(id: String, list: List<ChatMessage>) {
        val arr = JSONArray()
        list.filterNot { it.isStreaming }.forEach { m ->
            arr.put(JSONObject()
                .put("id", m.id)
                .put("role", m.role.name)
                .put("content", m.content)
                .put("createdAt", m.createdAt)
                .put("modelId", m.modelId ?: ""))
        }
        storage.edit().putString("messages_$id", JSONObject().put("messages", arr).toString()).apply()
    }

    private companion object {
        const val TAG = "ParallaxChatBackend"
    }
}

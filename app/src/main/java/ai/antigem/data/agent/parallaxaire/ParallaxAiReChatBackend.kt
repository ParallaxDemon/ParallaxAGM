package ai.antigem.data.agent.parallaxaire

import android.app.Application
import android.util.Log
import ai.antigem.data.agent.AgentChatBackend
import ai.antigem.data.agent.ChatSessionStore
import ai.antigem.domain.model.AgentKind
import ai.antigem.domain.model.ChatMessage
import ai.antigem.domain.model.Conversation
import ai.antigem.domain.model.MessageRole
import ai.antigem.domain.model.ToolCall
import ai.antigem.domain.model.ToolType
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
import java.io.BufferedReader
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct Parallax AI RE backend.
 *
 * Talks to an OpenAI-compatible llama-server (default: http://10.166.8.221:8080/v1) without OpenCode.
 * Supports streaming chat completions and a local tool-calling loop for phone actions.
 */
class ParallaxAiReChatBackend(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: ParallaxAiRePreferences,
) : AgentChatBackend {

    override val kind: AgentKind = AgentKind.PARALLAX_AI_RE

    private val storage = application.getSharedPreferences("parallax_ai_re_chats", 0)

    private val _conversations = MutableStateFlow(loadConversations())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    override val currentConversation: StateFlow<Conversation?> = store.currentConversation
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    val isCurrentChatActivelyRunning: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _availableModels = MutableStateFlow(listOf(ai.antigem.domain.model.AiModel(id = ParallaxAiRePreferences.DEFAULT_MODEL, displayName = "Huihui Qwen3.5 4B Abliterated (llama-server)")))
    val availableModels: StateFlow<List<ai.antigem.domain.model.AiModel>> = _availableModels.asStateFlow()
    val enabledModels: StateFlow<List<ai.antigem.domain.model.AiModel>> = _availableModels.asStateFlow()
    private val _selectedModelId = MutableStateFlow(ParallaxAiRePreferences.DEFAULT_MODEL)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    val isRefreshingModels: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var activeJob: Job? = null
    private val cancelled = AtomicBoolean(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // streaming
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        scope.launch {
            conversations.collect { list ->
                val existing = store.conversations.value.filter { it.agent != AgentKind.PARALLAX_AI_RE }
                store.conversations.value = (existing + list).sortedByDescending { it.updatedAt }
            }
        }
        scope.launch {
            _messages.collect { msgs ->
                if (store.currentConversation.value?.agent == AgentKind.PARALLAX_AI_RE) {
                    store.messages.value = msgs
                }
            }
        }
        scope.launch {
            _conversationError.collect { err ->
                if (store.currentConversation.value?.agent == AgentKind.PARALLAX_AI_RE) {
                    store.conversationError.value = err
                }
            }
        }

    }

    fun detach() {
        activeJob?.cancel()
        activeJob = null
        cancelled.set(true)
        _isStreaming.value = false
        _isLoadingConversation.value = false
    }

    override fun startNewChat() {
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "Parallax AI RE Chat",
            agent = AgentKind.PARALLAX_AI_RE,
            modelId = prefs.model.value,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        _conversations.value = listOf(conv) + _conversations.value
        persistChats()
        store.currentConversation.value = conv
        _messages.value = emptyList()
        _conversationError.value = null
        persistMessages(conv.id, emptyList())
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id } ?: return
        store.currentConversation.value = conv
        _conversationError.value = null
        _isLoadingConversation.value = true
        scope.launch {
            val msgs = withContext(Dispatchers.IO) { loadMessagesInternal(id) }
            if (store.currentConversation.value?.id == id) {
                _messages.value = msgs
                _isLoadingConversation.value = false
            }
        }
    }

    override fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        persistChats()
        storage.edit().remove("messages_$id").apply()
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.value = null
            _messages.value = emptyList()
            _isStreaming.value = false
        }
    }

    override fun forkConversation(id: String) {
        val src = _conversations.value.find { it.id == id } ?: return
        val msgs = loadMessagesInternal(id)
        val copy = src.copy(
            id = UUID.randomUUID().toString(),
            title = src.title + " (fork)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        _conversations.value = listOf(copy) + _conversations.value
        persistChats()
        persistMessages(copy.id, msgs.map { it.copy(id = UUID.randomUUID().toString(), conversationId = copy.id) })
        selectConversation(copy.id)
    }

    fun renameConversation(id: String, title: String) {
        _conversations.value = _conversations.value.map { if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it }
        persistChats()
        if (store.currentConversation.value?.id == id) store.currentConversation.value = store.currentConversation.value?.copy(title = title)
    }

    fun setArchived(id: String, archived: Boolean) {
        _conversations.value = _conversations.value.map { if (it.id == id) it.copy(archived = archived) else it }
        persistChats()
        if (store.currentConversation.value?.id == id) store.currentConversation.value = store.currentConversation.value?.copy(archived = archived)
    }

    fun messagesOf(id: String): List<ChatMessage> = loadMessagesInternal(id)
    suspend fun loadMessages(id: String): List<ChatMessage> = withContext(Dispatchers.IO) { loadMessagesInternal(id) }

    override fun retryConnections() {
        _lastError.value = null
        _conversationError.value = null
    }

    override fun onAppForegrounded() { /* no-op */ }

    override fun stopStreaming() {
        cancelled.set(true)
        activeJob?.cancel()
        activeJob = null
        _isStreaming.value = false
        // mark last streaming assistant as not streaming
        _messages.value = _messages.value.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        store.currentConversation.value?.let { persistMessages(it.id, _messages.value) }
    }

    override fun sendMessage(content: String) {
        val text = content.trim()
        if (text.isBlank()) return
        val conv = store.currentConversation.value ?: run { startNewChat(); store.currentConversation.value } ?: return
        if (activeJob?.isActive == true) return

        cancelled.set(false)
        _conversationError.value = null
        _lastError.value = null

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = MessageRole.USER,
            content = text,
            createdAt = System.currentTimeMillis(),
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantId,
            conversationId = conv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            createdAt = System.currentTimeMillis(),
            modelId = prefs.model.value,
        )
        val newMessages = _messages.value + userMsg + assistantMsg
        _messages.value = newMessages
        _isStreaming.value = true
        touchConversation(conv.id, titleFrom(text, conv.title))
        persistMessages(conv.id, newMessages)

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                runToolLoop(conv.id, assistantId)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    Log.w(TAG, "Parallax AI RE failed", e)
                    val msg = e.message ?: "Request failed"
                    _lastError.value = msg
                    withContext(Dispatchers.Main) {
                        _conversationError.value = msg
                        updateAssistant(conv.id, assistantId) { it.copy(isStreaming = false, content = it.content.ifBlank { "Error: $msg" }) }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isStreaming.value = false
                    val final = _messages.value.map { if (it.id == assistantId) it.copy(isStreaming = false) else it }
                    _messages.value = final
                    persistMessages(conv.id, final)
                    touchConversation(conv.id, null)
                }
            }
        }
    }

    private fun touchConversation(id: String, title: String?) {
        val now = System.currentTimeMillis()
        _conversations.value = _conversations.value.map {
            if (it.id == id) it.copy(updatedAt = now, title = title ?: it.title) else it
        }.sortedByDescending { it.updatedAt }
        persistChats()
        val cur = store.currentConversation.value
        if (cur?.id == id) store.currentConversation.value = cur.copy(updatedAt = now, title = title ?: cur.title)
    }

    private fun titleFrom(text: String, existing: String): String? {
        if (existing != "Parallax AI RE Chat" && existing.isNotBlank()) return null
        val t = text.replace('\n', ' ').trim().take(48)
        return if (t.isBlank()) null else t
    }

    private suspend fun runToolLoop(convId: String, assistantId: String) {
        val maxTurns = 12
        for (turn in 0 until maxTurns) {
            if (cancelled.get() || !scope.isActive) return
            val result = streamCompletion(convId, assistantId)
            val toolCalls = result.toolCalls
            if (toolCalls.isEmpty() || cancelled.get()) return

            // append assistant message with tool calls as completed in UI, then add tool results and continue
            val assistantWithTools = _messages.value.find { it.id == assistantId }
            val toolCallModels = toolCalls.map { tc ->
                ToolCall(
                    id = tc.id,
                    name = tc.name,
                    toolType = toolTypeFor(tc.name),
                    command = tc.arguments.ifBlank { "{}" },
                    status = "RUNNING",
                )
            }
            withContext(Dispatchers.Main) {
                updateAssistant(convId, assistantId) { it.copy(toolCalls = toolCallModels, isStreaming = true) }
            }

            val toolResults = mutableListOf<Pair<ToolCallReq, String>>()
            for ((idx, tc) in toolCalls.withIndex()) {
                if (cancelled.get()) return
                val output = executeTool(tc)
                toolResults += tc to output
                withContext(Dispatchers.Main) {
                    updateAssistant(convId, assistantId) { msg ->
                        val updated = msg.toolCalls.mapIndexed { i, c ->
                            if (i == idx) c.copy(status = "SUCCESS", output = output.take(8000)) else c
                        }
                        msg.copy(toolCalls = updated)
                    }
                }
            }

            // persist tool interaction into message history for next request
            // We represent tool calls/results as synthetic messages in local history by encoding into assistant+tool messages.
            val historyMsg = ChatMessage(
                id = assistantId,
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = result.content,
                toolCalls = toolCallModels.mapIndexed { i, c -> c.copy(status = "SUCCESS", output = toolResults[i].second.take(8000)) },
                isStreaming = false,
                createdAt = System.currentTimeMillis(),
                modelId = prefs.model.value,
            )
            // Replace assistant message in store with finalized tool-call message, then add tool result message and new streaming assistant
            val toolMsgs = toolResults.map { (tc, out) ->
                ChatMessage(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = MessageRole.TOOL,
                    content = "[tool_call_id:${tc.id}]\n${out.take(12000)}",
                    createdAt = System.currentTimeMillis(),
                )
            }
            val nextAssistantId = UUID.randomUUID().toString()
            val nextAssistant = ChatMessage(
                id = nextAssistantId,
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
                createdAt = System.currentTimeMillis(),
                modelId = prefs.model.value,
            )
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.map { if (it.id == assistantId) historyMsg else it } + toolMsgs + nextAssistant
                persistMessages(convId, _messages.value)
            }
            // continue loop with new assistant id by recursing logic: simplest to update assistantId via local var trick
            return runToolLoopContinuation(convId, nextAssistantId, turn + 1, maxTurns)
        }
    }

    private suspend fun runToolLoopContinuation(convId: String, assistantId: String, turn: Int, maxTurns: Int) {
        if (turn >= maxTurns || cancelled.get() || !scope.isActive) return
        val result = streamCompletion(convId, assistantId)
        val toolCalls = result.toolCalls
        if (toolCalls.isEmpty() || cancelled.get()) return
        val toolCallModels = toolCalls.map { tc ->
            ToolCall(id = tc.id, name = tc.name, toolType = toolTypeFor(tc.name), command = tc.arguments.ifBlank { "{}" }, status = "RUNNING")
        }
        withContext(Dispatchers.Main) { updateAssistant(convId, assistantId) { it.copy(toolCalls = toolCallModels, isStreaming = true) } }
        val toolResults = mutableListOf<Pair<ToolCallReq, String>>()
        for ((idx, tc) in toolCalls.withIndex()) {
            if (cancelled.get()) return
            val output = executeTool(tc)
            toolResults += tc to output
            withContext(Dispatchers.Main) {
                updateAssistant(convId, assistantId) { msg ->
                    msg.copy(toolCalls = msg.toolCalls.mapIndexed { i, c -> if (i == idx) c.copy(status = "SUCCESS", output = output.take(8000)) else c })
                }
            }
        }
        val historyMsg = _messages.value.find { it.id == assistantId }?.copy(
            toolCalls = toolCallModels.mapIndexed { i, c -> c.copy(status = "SUCCESS", output = toolResults[i].second.take(8000)) },
            isStreaming = false,
        ) ?: return
        val toolMsgs = toolResults.map { (tc, out) ->
            ChatMessage(
                id = UUID.randomUUID().toString(), conversationId = convId, role = MessageRole.TOOL,
                content = "[tool_call_id:${tc.id}]\n${out.take(12000)}",
                createdAt = System.currentTimeMillis(),
            )
        }
        val nextAssistantId = UUID.randomUUID().toString()
        val nextAssistant = ChatMessage(
            id = nextAssistantId, conversationId = convId, role = MessageRole.ASSISTANT,
            content = "", isStreaming = true, createdAt = System.currentTimeMillis(), modelId = prefs.model.value,
        )
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map { if (it.id == assistantId) historyMsg else it } + toolMsgs + nextAssistant
            persistMessages(convId, _messages.value)
        }
        runToolLoopContinuation(convId, nextAssistantId, turn + 1, maxTurns)
    }

    private data class ToolCallReq(val id: String, val name: String, val arguments: String) {
        fun argsPreview(): String = arguments.take(300)
        fun jsonArgs(): JSONObject = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    }
    private data class CompletionResult(val content: String, val toolCalls: List<ToolCallReq>)

    private suspend fun streamCompletion(convId: String, assistantId: String): CompletionResult {
        val base = prefs.baseUrl.value.trim().trimEnd('/')
        val url = if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
        val model = prefs.model.value.trim().ifEmpty { ParallaxAiRePreferences.DEFAULT_MODEL }
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("temperature", 0.2)
            put("max_tokens", 2048)
            put("messages", buildMessagesJson(convId, assistantId))
            put("tools", toolsJson())
            put("tool_choice", "auto")
        }
        val reqBuilder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        val key = prefs.apiKey.value.trim()
        if (key.isNotBlank()) reqBuilder.header("Authorization", "Bearer $key")
        val req = reqBuilder.post(body.toString().toRequestBody("application/json".toMediaType())).build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(1000) ?: "HTTP ${resp.code}"
                throw RuntimeException("llama-server HTTP ${resp.code}: $err")
            }
            _isConnected.value = true
            val source = resp.body?.source() ?: throw RuntimeException("Empty response body")
            val content = StringBuilder()
            val toolCalls = linkedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // index -> (id,name,args)
            while (!source.exhausted()) {
                if (cancelled.get()) return CompletionResult(content.toString(), emptyList())
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val choices = obj.optJSONArray("choices") ?: continue
                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val chunk = delta.optString("content", "")
                if (chunk.isNotEmpty()) {
                    content.append(chunk)
                    withContext(Dispatchers.Main) {
                        updateAssistant(convId, assistantId) { it.copy(content = content.toString(), isStreaming = true) }
                    }
                }
                val tcArr = delta.optJSONArray("tool_calls")
                if (tcArr != null) {
                    for (i in 0 until tcArr.length()) {
                        val tc = tcArr.optJSONObject(i) ?: continue
                        val idx = tc.optInt("index", i)
                        val entry = toolCalls.getOrPut(idx) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                        if (tc.has("id")) entry.first.append(tc.optString("id"))
                        val fn = tc.optJSONObject("function")
                        if (fn != null) {
                            if (fn.has("name")) entry.second.append(fn.optString("name"))
                            if (fn.has("arguments")) entry.third.append(fn.optString("arguments"))
                        }
                    }
                }
            }
            val tcs = toolCalls.entries.sortedBy { it.key }.map { (_, v) ->
                ToolCallReq(v.first.toString().ifBlank { "call_${UUID.randomUUID()}" }, v.second.toString(), v.third.toString().ifBlank { "{}" })
            }.filter { it.name.isNotBlank() }
            return CompletionResult(content.toString(), tcs)
        }
    }

    private fun buildMessagesJson(convId: String, currentAssistantId: String): JSONArray {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", prefs.systemPrompt.value))
        val msgs = _messages.value.filter { it.conversationId == convId && it.id != currentAssistantId }
        // keep last 40 messages max to fit context
        val trimmed = if (msgs.size > 40) msgs.takeLast(40) else msgs
        for (m in trimmed) {
            when (m.role) {
                MessageRole.USER -> arr.put(JSONObject().put("role", "user").put("content", m.content))
                MessageRole.ASSISTANT -> {
                    if (m.toolCalls.isNotEmpty()) {
                        val o = JSONObject().put("role", "assistant").put("content", m.content)
                        val tcArr = JSONArray()
                        m.toolCalls.forEachIndexed { i, tc ->
                            tcArr.put(JSONObject()
                                .put("id", tc.id.ifBlank { "call_$i" })
                                .put("type", "function")
                                .put("function", JSONObject().put("name", tc.name).put("arguments", tc.command.takeIf { it.startsWith("{") } ?: "{}")))
                        }
                        o.put("tool_calls", tcArr)
                        arr.put(o)
                    } else {
                        arr.put(JSONObject().put("role", "assistant").put("content", m.content))
                    }
                }
                MessageRole.TOOL -> {
                    val content = m.content
                    val mId = Regex("^\\[tool_call_id:(.+?)\\]\\n", RegexOption.DOT_MATCHES_ALL).find(content)
                    if (mId != null) {
                        val tcId = mId.groupValues[1]
                        val body = content.removePrefix(mId.value)
                        arr.put(JSONObject().put("role", "tool").put("tool_call_id", tcId).put("content", body))
                    } else {
                        arr.put(JSONObject().put("role", "user").put("content", content))
                    }
                }
                else -> {}
            }
        }
        return arr
    }

    private fun toolsJson(): JSONArray = JSONArray().apply {
        put(toolDef("shell", "Execute a shell command on the Android device. Use for phone actions, file ops, package management, git, adb, etc.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().put("type", "string").put("description", "Shell command to run"))
                put("workdir", JSONObject().put("type", "string").put("description", "Optional working directory"))
                put("timeout_sec", JSONObject().put("type", "integer").put("description", "Timeout seconds (default 120)"))
            })
            put("required", JSONArray().put("command"))
        }))
        put(toolDef("read_file", "Read a text file from the device.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", JSONObject().put("type", "string").put("description", "Absolute or relative file path"))
                put("offset", JSONObject().put("type", "integer").put("description", "Line offset (0-based)"))
                put("limit", JSONObject().put("type", "integer").put("description", "Max lines (default 400)"))
            })
            put("required", JSONArray().put("path"))
        }))
        put(toolDef("write_file", "Write content to a file (creates/overwrites).", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", JSONObject().put("type", "string").put("description", "File path"))
                put("content", JSONObject().put("type", "string").put("description", "Full file content"))
            })
            put("required", JSONArray().put("path").put("content"))
        }))
        put(toolDef("edit_file", "Replace exact text in a file.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", JSONObject().put("type", "string"))
                put("old_string", JSONObject().put("type", "string"))
                put("new_string", JSONObject().put("type", "string"))
                put("replace_all", JSONObject().put("type", "boolean"))
            })
            put("required", JSONArray().put("path").put("old_string").put("new_string"))
        }))
        put(toolDef("list_dir", "List directory entries.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("path", JSONObject().put("type", "string").put("description", "Directory path"))
            })
            put("required", JSONArray().put("path"))
        }))
        put(toolDef("grep", "Search file contents with regex.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("pattern", JSONObject().put("type", "string"))
                put("path", JSONObject().put("type", "string"))
                put("glob", JSONObject().put("type", "string"))
                put("limit", JSONObject().put("type", "integer"))
            })
            put("required", JSONArray().put("pattern"))
        }))
        put(toolDef("glob", "Find files by glob pattern.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("pattern", JSONObject().put("type", "string").put("description", "Glob pattern like **/*.kt"))
                put("path", JSONObject().put("type", "string").put("description", "Base directory"))
            })
            put("required", JSONArray().put("pattern"))
        }))
        put(toolDef("web_fetch", "Fetch a URL via HTTP GET.", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("url", JSONObject().put("type", "string"))
                put("headers", JSONObject().put("type", "object"))
            })
            put("required", JSONArray().put("url"))
        }))
    }

    private fun toolDef(name: String, desc: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))

    private fun toolTypeFor(name: String): ToolType = when (name) {
        "shell" -> ToolType.BASH
        "read_file" -> ToolType.VIEW_FILE
        "write_file", "edit_file" -> ToolType.EDIT_FILE
        "list_dir" -> ToolType.LIST_DIR
        "grep" -> ToolType.GREP_SEARCH
        "glob" -> ToolType.FIND
        "web_fetch" -> ToolType.READ_URL
        else -> ToolType.UNKNOWN
    }

    private fun executeTool(tc: ToolCallReq): String = runCatching {
        val a = tc.jsonArgs()
        when (tc.name) {
            "shell" -> execShell(a.optString("command"), a.optString("workdir"), a.optInt("timeout_sec", 120))
            "read_file" -> toolReadFile(a.optString("path"), a.optInt("offset", 0), a.optInt("limit", 400))
            "write_file" -> toolWriteFile(a.optString("path"), a.optString("content"))
            "edit_file" -> toolEditFile(a.optString("path"), a.optString("old_string"), a.optString("new_string"), a.optBoolean("replace_all", false))
            "list_dir" -> toolListDir(a.optString("path"))
            "grep" -> execShell(buildGrepCmd(a), a.optString("path"), 60)
            "glob" -> execShell(buildGlobCmd(a), a.optString("path"), 60)
            "web_fetch" -> toolWebFetch(a.optString("url"))
            else -> "Unknown tool: ${tc.name}"
        }
    }.getOrElse { "Tool error: ${it.message}" }

    private fun buildGrepCmd(a: JSONObject): String {
        val pat = a.optString("pattern").replace("'", "'\\''")
        val g = a.optString("glob").replace("'", "'\\''")
        val lim = a.optInt("limit", 100).coerceIn(1, 500)
        val base = a.optString("path").ifBlank { "." }.replace("'", "'\\''")
        return if (g.isNotBlank()) "rg -n --max-count $lim --glob '$g' '$pat' '$base' 2>&1 | head -n 200"
        else "rg -n --max-count $lim '$pat' '$base' 2>&1 | head -n 200"
    }

    private fun buildGlobCmd(a: JSONObject): String {
        val pat = a.optString("pattern").replace("'", "'\\''")
        val base = a.optString("path").ifBlank { "." }.replace("'", "'\\''")
        return "cd '$base' && (rg --files -g '$pat' 2>&1 || find . -name '$pat' 2>&1) | head -n 200"
    }

    private fun execShell(command: String, workdir: String?, timeoutSec: Int): String {
        if (command.isBlank()) return "Error: empty command"
        val shell = when {
            File("/data/data/com.termux/files/usr/bin/bash").canExecute() -> "/data/data/com.termux/files/usr/bin/bash"
            File("/system/bin/sh").exists() -> "/system/bin/sh"
            else -> "sh"
        }
        val pb = ProcessBuilder(shell, "-lc", command)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PATH"] = "/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:" + (env["PATH"] ?: "/system/bin:/system/xbin")
        env["HOME"] = env["HOME"] ?: "/data/data/com.termux/files/home"
        env["TMPDIR"] = "/data/user/0/com.termux/files/usr/tmp"
        val wd = workdir?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.isDirectory }
        if (wd != null) pb.directory(wd)
        val proc = pb.start()
        val out = StringBuilder()
        proc.inputStream.bufferedReader().use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                if (out.length < 12000) out.appendLine(line)
            }
        }
        val ok = proc.waitFor(timeoutSec.coerceIn(1, 600).toLong(), TimeUnit.SECONDS)
        if (!ok) {
            proc.destroyForcibly()
            return out.toString().take(12000) + "\n[timeout after ${timeoutSec}s]"
        }
        val code = proc.exitValue()
        val text = out.toString().trimEnd()
        return if (code == 0) text.ifBlank { "(no output, exit 0)" } else "$text\n[exit $code]".trim()
    }

    private fun resolvePath(path: String): File {
        val p = path.trim()
        val f = File(p)
        if (f.isAbsolute) return f
        val base = store.currentConversation.value?.workspaceUri?.removePrefix("file://")?.takeIf { it.isNotBlank() }
            ?: "/data/data/com.termux/files/home"
        return File(base, p)
    }

    private fun toolReadFile(path: String, offset: Int, limit: Int): String {
        val f = resolvePath(path)
        if (!f.exists()) return "Error: file not found: ${f.absolutePath}"
        if (!f.isFile) return "Error: not a file: ${f.absolutePath}"
        val lines = f.readLines()
        val start = offset.coerceAtLeast(0).coerceAtMost(lines.size)
        val end = (start + limit.coerceIn(1, 2000)).coerceAtMost(lines.size)
        return lines.subList(start, end).mapIndexed { i, l -> "${start + i + 1}: $l" }.joinToString("\n")
    }

    private fun toolWriteFile(path: String, content: String): String {
        val f = resolvePath(path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return "Wrote ${content.length} chars to ${f.absolutePath}"
    }

    private fun toolEditFile(path: String, oldStr: String, newStr: String, replaceAll: Boolean): String {
        val f = resolvePath(path)
        if (!f.exists()) return "Error: file not found: ${f.absolutePath}"
        val text = f.readText()
        if (!text.contains(oldStr)) return "Error: old_string not found in ${f.absolutePath}"
        val updated = if (replaceAll) text.replace(oldStr, newStr) else text.replaceFirst(oldStr, newStr)
        f.writeText(updated)
        return "Edited ${f.absolutePath}"
    }

    private fun toolListDir(path: String): String {
        val f = resolvePath(path)
        if (!f.exists()) return "Error: not found: ${f.absolutePath}"
        if (!f.isDirectory) return "Error: not a directory: ${f.absolutePath}"
        return f.listFiles()?.sortedBy { it.name }?.joinToString("\n") {
            (if (it.isDirectory) "d " else "- ") + it.name + if (it.isDirectory) "/" else ""
        }?.ifBlank { "(empty)" } ?: "(empty)"
    }

    private fun toolWebFetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "ParallaxAI-RE/1.0").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string()?.take(12000) ?: ""
            return "HTTP ${resp.code}\n$body"
        }
    }

    private inline fun updateAssistant(convId: String, assistantId: String, transform: (ChatMessage) -> ChatMessage) {
        val cur = store.currentConversation.value ?: return
        if (cur.id != convId) return
        _messages.value = _messages.value.map { if (it.id == assistantId) transform(it) else it }
    }

    // persistence
    private fun persistChats() {
        val arr = JSONArray()
        _conversations.value.forEach { c ->
            arr.put(JSONObject()
                .put("id", c.id)
                .put("title", c.title)
                .put("createdAt", c.createdAt)
                .put("updatedAt", c.updatedAt)
                .put("modelId", c.modelId)
                .put("workspaceUri", c.workspaceUri))
        }
        storage.edit().putString("chats", arr.toString()).apply()
    }

    private fun loadConversations(): List<Conversation> {
        val raw = storage.getString("chats", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Conversation(
                    id = o.optString("id").ifBlank { return@mapNotNull null },
                    title = o.optString("title", "Parallax AI RE Chat"),
                    agent = AgentKind.PARALLAX_AI_RE,
                    modelId = o.optString("modelId", ParallaxAiRePreferences.DEFAULT_MODEL),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    workspaceUri = o.optString("workspaceUri", ""),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun loadMessagesInternal(id: String): List<ChatMessage> {
        val raw = storage.getString("messages_$id", null) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("messages") ?: JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val role = runCatching { MessageRole.valueOf(o.optString("role", "USER")) }.getOrDefault(MessageRole.USER)
                ChatMessage(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    conversationId = id,
                    role = role,
                    content = o.optString("content", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    modelId = o.optString("modelId").ifBlank { null },
                )
            }
        }.getOrElse { emptyList() }
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

    companion object {
        private const val TAG = "ParallaxAiReBackend"
    }
}

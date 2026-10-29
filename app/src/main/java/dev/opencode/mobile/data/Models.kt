package dev.opencode.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Роль сообщения в истории чата. */
enum class Role { USER, ASSISTANT, TOOL }

/** Ссылка на вызов инструмента, который запросила модель. */
data class ToolCallRef(
    val id: String,
    val name: String,
    val arguments: String,
    // Gemini 3 requires its opaque signature back with a tool call within the same turn.
    val googleThoughtSignature: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("arguments", arguments)
        .apply { googleThoughtSignature?.let { put("googleThoughtSignature", it) } }

    companion object {
        fun fromJson(o: JSONObject): ToolCallRef = ToolCallRef(
            id = o.optString("id"),
            name = o.optString("name"),
            arguments = o.optString("arguments", "{}"),
            googleThoughtSignature = if (o.isNull("googleThoughtSignature")) null
                else o.optString("googleThoughtSignature").takeIf { it.isNotEmpty() }
        )
    }
}

/** Complete Anthropic content blocks, in original order, tied to their source. */
data class AnthropicTrace(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val blocks: List<String>
) {
    fun toJson(): JSONObject = JSONObject().put("providerId", providerId).put("baseUrl", baseUrl)
        .put("model", model).put("blocks", JSONArray(blocks))

    companion object {
        fun fromJson(o: JSONObject): AnthropicTrace {
            val blocks = mutableListOf<String>()
            o.optJSONArray("blocks")?.let { arr ->
                for (i in 0 until arr.length()) blocks.add(arr.optString(i))
            }
            return AnthropicTrace(o.optString("providerId"), o.optString("baseUrl"), o.optString("model"), blocks)
        }
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val reasoning: String = "",
    val reasoningDurationMs: Long? = null,
    val anthropicTrace: AnthropicTrace? = null,
    val toolCalls: List<ToolCallRef> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolOk: Boolean? = null,
    // Client-side errors are displayed locally and never sent back as model output.
    val errorStatus: Int? = null,
    val streaming: Boolean = false,
    val expanded: Boolean = false,
    val ts: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("role", role.name)
        o.put("content", content)
        if (reasoning.isNotEmpty()) o.put("reasoning", reasoning)
        reasoningDurationMs?.let { o.put("reasoningDurationMs", it) }
        anthropicTrace?.let { o.put("anthropicTrace", it.toJson()) }
        o.put("ts", ts)
        o.put("expanded", expanded)
        if (toolCalls.isNotEmpty()) {
            val arr = JSONArray()
            toolCalls.forEach { arr.put(it.toJson()) }
            o.put("toolCalls", arr)
        }
        toolCallId?.let { o.put("toolCallId", it) }
        toolName?.let { o.put("toolName", it) }
        toolArgs?.let { o.put("toolArgs", it) }
        toolOk?.let { o.put("toolOk", it) }
        errorStatus?.let { o.put("errorStatus", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            val calls = mutableListOf<ToolCallRef>()
            o.optJSONArray("toolCalls")?.let { arr ->
                for (i in 0 until arr.length()) calls.add(ToolCallRef.fromJson(arr.getJSONObject(i)))
            }
            return ChatMessage(
                id = o.optString("id", UUID.randomUUID().toString()),
                role = runCatching { Role.valueOf(o.optString("role", "USER")) }.getOrDefault(Role.USER),
                content = o.optString("content", ""),
                reasoning = o.optString("reasoning", ""),
                reasoningDurationMs = if (!o.isNull("reasoningDurationMs")) o.optLong("reasoningDurationMs") else null,
                anthropicTrace = o.optJSONObject("anthropicTrace")?.let { AnthropicTrace.fromJson(it) },
                toolCalls = calls,
                toolCallId = if (o.has("toolCallId")) o.optString("toolCallId") else null,
                toolName = if (o.has("toolName")) o.optString("toolName") else null,
                toolArgs = if (o.has("toolArgs")) o.optString("toolArgs") else null,
                toolOk = if (o.has("toolOk")) o.optBoolean("toolOk") else null,
                errorStatus = if (!o.isNull("errorStatus")) o.optInt("errorStatus") else null,
                expanded = o.optBoolean("expanded", false),
                ts = o.optLong("ts", System.currentTimeMillis())
            )
        }
    }
}

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новая сессия",
    val titleManuallySet: Boolean = false,
    val cwd: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList()
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        if (titleManuallySet) o.put("titleManuallySet", true)
        o.put("cwd", cwd)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        val arr = JSONArray()
        messages.forEach { arr.put(it.toJson()) }
        o.put("messages", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatSession {
            val msgs = mutableListOf<ChatMessage>()
            o.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) msgs.add(ChatMessage.fromJson(arr.getJSONObject(i)))
            }
            val ms = o.optLong("createdAt", System.currentTimeMillis())
            return ChatSession(
                id = o.optString("id", UUID.randomUUID().toString()),
                title = o.optString("title", "Сессия"),
                titleManuallySet = o.optBoolean("titleManuallySet", false),
                cwd = o.optString("cwd", "/sdcard"),
                createdAt = ms,
                updatedAt = o.optLong("updatedAt", ms),
                messages = msgs
            )
        }
    }
}

data class SessionSummary(val id: String, val title: String, val cwd: String, val updatedAt: Long)

data class TodoItem(val content: String, val status: String)

data class FileEntry(val name: String, val path: String, val isDir: Boolean, val size: Long = 0)

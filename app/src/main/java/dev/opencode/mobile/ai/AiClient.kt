package dev.opencode.mobile.ai

import dev.opencode.mobile.data.AnthropicTrace
import dev.opencode.mobile.data.ModelReasoningCatalog
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ReasoningLevel
import dev.opencode.mobile.data.ReasoningProtocol
import dev.opencode.mobile.data.TokenUsage
import dev.opencode.mobile.tools.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val googleThoughtSignature: String? = null
)

data class AiMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val anthropicTrace: AnthropicTrace? = null,
    // Local bookkeeping; never serialized as a vendor-specific message field.
    val syntheticSummary: Boolean = false
)

data class AiRequest(
    val model: String,
    val system: String,
    val messages: List<AiMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.2,
    val maxTokens: Int = 8192,
    val stream: Boolean = true,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF
)

data class AiResponse(
    val content: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val finishReason: String? = null,
    val reasoning: String = "",
    val anthropicBlocks: List<String> = emptyList(),
    val usage: TokenUsage? = null
)

class AiException(message: String, val status: Int = 0) : Exception(message)

interface AiClient {
    suspend fun complete(
        req: AiRequest,
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit
    ): AiResponse
    fun cancel()
}

object AiClients {
    fun create(provider: Provider): AiClient {
        ProviderUrlPolicy.error(provider.baseUrl, provider.apiKey)?.let { throw AiException(it) }
        return when (provider.kind) {
            "anthropic" -> AnthropicClient(provider)
            else -> OpenAiCompatibleClient(provider)
        }
    }

    fun endpoint(baseUrl: String, path: String): String {
        var b = baseUrl.trim()
        if (b.isEmpty()) b = "https://api.openai.com/v1"
        b = b.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return b + p
    }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

// Android's JSONObject.optString turns a JSON null into the literal text "null".
private fun JSONObject.textOrEmpty(key: String): String = if (isNull(key)) "" else optString(key, "")

internal val sharedHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    // Retries are managed explicitly so a partially received stream is never replayed.
    .retryOnConnectionFailure(false)
    .build()

/** Клиент для OpenAI Chat Completions и всех совместимых API (OpenRouter, Groq, Ollama, LM Studio, vLLM). */
class OpenAiCompatibleClient(private val provider: Provider) : AiClient {

    @Volatile private var currentCall: Call? = null
    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        runCatching { currentCall?.cancel() }
    }

    override suspend fun complete(req: AiRequest, onReasoning: suspend (String) -> Unit, onDelta: suspend (String) -> Unit): AiResponse = withContext(Dispatchers.IO) {
        val payload = buildPayload(req)
        val builder = Request.Builder()
            .url(AiClients.endpoint(provider.baseUrl, "chat/completions"))
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
        if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${provider.apiKey}")
        if (provider.baseUrl.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", "https://github.com/ccndjfgjs/Dustin-Fisher-Murphy")
            builder.header("X-Title", "OpenCode Mobile")
        }
        RequestRetry.execute(builder.build(), onCall = { currentCall = it }, isCancelled = { cancelled }) { resp ->
            val body = resp.body ?: throw AiException("Пустой ответ сервера", resp.code)
            if (!resp.isSuccessful) {
                val err = runCatching { resp.peekBody(8192).string() }.getOrDefault("")
                throw AiException("HTTP ${resp.code}: ${err.take(800)}", resp.code)
            }
            parseStream(body.source(), onReasoning, onDelta)
        }
    }

    internal fun buildPayload(req: AiRequest): JSONObject {
        val root = JSONObject()
        root.put("model", req.model)
        root.put("stream", req.stream)
        if (req.stream && (provider.streamUsage ?: ProviderRequestPolicy.defaultStreamUsage(provider.baseUrl))) {
            root.put("stream_options", JSONObject().put("include_usage", true))
        }
        val protocol = ModelReasoningCatalog.capability(provider, req.model)?.protocol
        if (protocol == ReasoningProtocol.OPENAI_EFFORT) {
            // OpenAI o-series reject the legacy max_tokens and non-default temperature.
            root.put("max_completion_tokens", req.maxTokens)
            if (req.reasoningLevel != ReasoningLevel.OFF) root.put("reasoning_effort", req.reasoningLevel.name.lowercase())
        } else {
            root.put("temperature", req.temperature)
            root.put("max_tokens", req.maxTokens)
        }
        if (ProviderRequestPolicy.disableDeepSeekThinking(provider.baseUrl)) {
            val enabled = protocol == ReasoningProtocol.DEEPSEEK && req.reasoningLevel != ReasoningLevel.OFF
            root.put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
            if (enabled && req.model.startsWith("deepseek-v4")) {
                root.put("reasoning_effort", if (req.reasoningLevel == ReasoningLevel.HIGH) "max" else "high")
            }
        }
        if (ProviderRequestPolicy.disableAlibabaThinking(provider.baseUrl, req.model)) {
            val enabled = protocol == ReasoningProtocol.ALIBABA_QWEN && req.reasoningLevel != ReasoningLevel.OFF
            root.put("enable_thinking", enabled)
            if (enabled && (req.model.startsWith("qwen3.7") || req.model.startsWith("qwen3.6"))) {
                root.put("reasoning_effort", req.reasoningLevel.name.lowercase())
            }
        }
        val messages = JSONArray()
        if (req.system.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", req.system))
        }
        for (m in req.messages) {
            when (m.role) {
                "tool" -> messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.toolCallId ?: "")
                        .put("content", m.content)
                )
                "assistant" -> {
                    val o = JSONObject().put("role", "assistant")
                    o.put("content", if (m.content.isBlank()) JSONObject.NULL else m.content)
                    if (m.toolCalls.isNotEmpty()) {
                        val calls = JSONArray()
                        m.toolCalls.forEach { tc ->
                            val call = JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function", JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments.ifBlank { "{}" })
                                )
                            if (ProviderRequestPolicy.isGoogleGemini(provider.baseUrl) && tc.googleThoughtSignature != null) {
                                call.put("extra_content", JSONObject().put(
                                    "google", JSONObject().put("thought_signature", tc.googleThoughtSignature)
                                ))
                            }
                            calls.put(call)
                        }
                        o.put("tool_calls", calls)
                    }
                    messages.put(o)
                }
                else -> messages.put(JSONObject().put("role", "user").put("content", m.content))
            }
        }
        root.put("messages", messages)
        if (req.tools.isNotEmpty()) {
            val tools = JSONArray()
            req.tools.forEach { tools.put(it.toOpenAiJson()) }
            root.put("tools", tools)
            root.put("tool_choice", "auto")
        }
        return root
    }

    internal suspend fun parseStream(
        source: BufferedSource,
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit
    ): AiResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val args = LinkedHashMap<Int, StringBuilder>()
        val names = HashMap<Int, String>()
        val ids = HashMap<Int, String>()
        val signatures = HashMap<Int, String>()
        var finish: String? = null
        var sawSse = false
        var done = false
        var usage: TokenUsage? = null
        val raw = StringBuilder()

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                sawSse = true
                val data = line.substring(5).trim()
                if (data == "[DONE]") { done = true; break }
                if (data.isEmpty()) continue
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                if (obj.has("error")) {
                    val err = obj.opt("error")
                    throw AiException(err.toString())
                }
                UsageParser.openAi(obj.optJSONObject("usage"), ProviderRequestPolicy.isOpenRouter(provider.baseUrl))?.let { usage = it }
                val choices = obj.optJSONArray("choices") ?: continue
                val choice = choices.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
                if (delta != null) {
                    val thought = delta.textOrEmpty("reasoning_content").ifEmpty { delta.textOrEmpty("reasoning") }
                    if (thought.isNotEmpty()) {
                        reasoning.append(thought)
                        onReasoning(thought)
                    }
                    val text = delta.textOrEmpty("content")
                    if (text.isNotEmpty()) {
                        content.append(text)
                        onDelta(text)
                    }
                    delta.optJSONArray("tool_calls")?.let { calls ->
                        for (i in 0 until calls.length()) {
                            val tc = calls.optJSONObject(i) ?: continue
                            val id = tc.textOrEmpty("id")
                            // Some Gemini-compatible chunks omit the OpenAI index but include an ID.
                            val index = if (!tc.isNull("index")) tc.optInt("index", i)
                                else if (id.isNotBlank()) ids.entries.firstOrNull { it.value == id }?.key
                                    ?: if (i !in ids && i !in args) i else ((ids.keys + args.keys).maxOrNull() ?: -1) + 1
                                else i
                            if (id.isNotBlank()) ids[index] = id
                            tc.optJSONObject("extra_content")?.optJSONObject("google")
                                ?.textOrEmpty("thought_signature")?.takeIf { it.isNotEmpty() }
                                ?.let { signatures[index] = it }
                            val fn = tc.optJSONObject("function")
                            val name = fn?.textOrEmpty("name").orEmpty()
                            if (name.isNotBlank()) names[index] = name
                            val partial = fn?.textOrEmpty("arguments").orEmpty()
                            if (partial.isNotEmpty()) {
                                args.getOrPut(index) { StringBuilder() }.append(partial)
                            } else if (!args.containsKey(index)) {
                                args[index] = StringBuilder()
                            }
                        }
                    }
                }
                choice.textOrEmpty("finish_reason").takeIf { it.isNotBlank() }?.let { finish = it }
            } else if (!sawSse && line.isNotBlank()) {
                if (raw.length < 4_000_000) raw.append(line).append('\n')
            }
        }

        if (!sawSse) return parseNonStream(raw.toString())
        if (!done && finish == null) throw AiException("Поток ответа прерван до завершения")

        val calls = args.keys.sorted().map { idx ->
            AiToolCall(
                id = ids[idx] ?: "call_$idx",
                name = names[idx].orEmpty(),
                arguments = args[idx]?.toString().orEmpty().ifBlank { "{}" },
                googleThoughtSignature = signatures[idx]
            )
        }.filter { it.name.isNotBlank() }
        return AiResponse(content.toString(), calls, finish, reasoning.toString(), usage = usage)
    }

    internal fun parseNonStream(raw: String): AiResponse {
        if (raw.isBlank()) throw AiException("Пустой ответ модели")
        val obj = runCatching { JSONObject(raw) }.getOrElse { throw AiException("Не удалось разобрать ответ: ${raw.take(300)}") }
        if (obj.has("error")) throw AiException(obj.opt("error").toString())
        val message = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException("В ответе нет choices[0].message")
        val content = message.textOrEmpty("content")
        val calls = mutableListOf<AiToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val tc = arr.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                calls.add(
                    AiToolCall(
                        id = tc.textOrEmpty("id").ifBlank { "call_$i" },
                        name = fn.textOrEmpty("name"),
                        arguments = fn.textOrEmpty("arguments").ifBlank { "{}" },
                        googleThoughtSignature = tc.optJSONObject("extra_content")?.optJSONObject("google")
                            ?.textOrEmpty("thought_signature")?.takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        val reasoning = message.textOrEmpty("reasoning_content").ifEmpty { message.textOrEmpty("reasoning") }
        return AiResponse(content, calls, reasoning = reasoning,
            usage = UsageParser.openAi(obj.optJSONObject("usage"), ProviderRequestPolicy.isOpenRouter(provider.baseUrl)))
    }
}

/** Клиент Anthropic Messages API. */
class AnthropicClient(private val provider: Provider) : AiClient {

    @Volatile private var currentCall: Call? = null
    @Volatile private var cancelled = false

    override fun cancel() {
        cancelled = true
        runCatching { currentCall?.cancel() }
    }

    override suspend fun complete(req: AiRequest, onReasoning: suspend (String) -> Unit, onDelta: suspend (String) -> Unit): AiResponse = withContext(Dispatchers.IO) {
        val base = provider.baseUrl.trim().trimEnd('/').ifEmpty { "https://api.anthropic.com" }
        val url = if (base.endsWith("/v1")) "$base/messages" else "$base/v1/messages"
        val payload = buildPayload(req)
        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .header("anthropic-version", "2023-06-01")
        if (provider.apiKey.isNotBlank()) builder.header("x-api-key", provider.apiKey)
        if (ModelReasoningCatalog.capability(provider, req.model)?.protocol == ReasoningProtocol.ANTHROPIC_MANUAL &&
            req.reasoningLevel != ReasoningLevel.OFF && !req.model.contains("haiku", ignoreCase = true)) {
            builder.header("anthropic-beta", "interleaved-thinking-2025-05-14")
        }
        RequestRetry.execute(builder.build(), onCall = { currentCall = it }, isCancelled = { cancelled }) { resp ->
            val body = resp.body ?: throw AiException("Пустой ответ сервера", resp.code)
            if (!resp.isSuccessful) {
                val err = runCatching { resp.peekBody(8192).string() }.getOrDefault("")
                throw AiException("HTTP ${resp.code}: ${err.take(800)}", resp.code)
            }
            if (req.stream) parseStream(body.source(), onReasoning, onDelta) else parseAnthropicJson(body.string())
        }
    }

    private fun parseAnthropicJson(raw: String): AiResponse {
        if (raw.isBlank()) throw AiException("Пустой ответ модели")
        val obj = runCatching { JSONObject(raw) }.getOrElse { throw AiException("Не удалось разобрать ответ: ${raw.take(300)}") }
        if (obj.optString("type") == "error") {
            throw AiException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
        }
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = mutableListOf<String>()
        val calls = mutableListOf<AiToolCall>()
        obj.optJSONArray("content")?.let { arr ->
            for (i in 0 until arr.length()) {
                val block = arr.optJSONObject(i) ?: continue
                blocks.add(block.toString())
                when (block.optString("type")) {
                    "text" -> content.append(block.textOrEmpty("text"))
                    "thinking" -> reasoning.append(block.textOrEmpty("thinking"))
                    "tool_use" -> calls.add(
                        AiToolCall(
                            id = block.optString("id", "toolu_$i"),
                            name = block.optString("name", ""),
                            arguments = block.optJSONObject("input")?.toString() ?: "{}"
                        )
                    )
                }
            }
        }
        val usage = UsageParser.Anthropic().apply { update(obj.optJSONObject("usage")) }.result()
        return AiResponse(content.toString(), calls, obj.optString("stop_reason"), reasoning.toString(), blocks, usage)
    }

    internal fun buildPayload(req: AiRequest): JSONObject {
        val root = JSONObject()
        root.put("model", req.model)
        root.put("stream", req.stream)
        root.put("max_tokens", req.maxTokens)
        val protocol = ModelReasoningCatalog.capability(provider, req.model)?.protocol
        val thinkingEnabled = req.reasoningLevel != ReasoningLevel.OFF &&
            (protocol == ReasoningProtocol.ANTHROPIC_MANUAL || protocol == ReasoningProtocol.ANTHROPIC_ADAPTIVE)
        when {
            thinkingEnabled && protocol == ReasoningProtocol.ANTHROPIC_MANUAL -> {
                val desired = when (req.reasoningLevel) {
                    ReasoningLevel.LOW -> 1024
                    ReasoningLevel.MEDIUM -> 2048
                    else -> 4096
                }
                if (req.maxTokens <= 1024) throw AiException("Для рассуждения Anthropic max_tokens должен превышать 1024")
                root.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", desired.coerceAtMost(req.maxTokens - 1)))
            }
            thinkingEnabled && protocol == ReasoningProtocol.ANTHROPIC_ADAPTIVE -> {
                root.put("thinking", JSONObject().put("type", "adaptive"))
                root.put("output_config", JSONObject().put("effort", req.reasoningLevel.name.lowercase()))
            }
            protocol == ReasoningProtocol.ANTHROPIC_ADAPTIVE -> root.put("thinking", JSONObject().put("type", "disabled"))
        }
        if (!thinkingEnabled) root.put("temperature", req.temperature)
        if (req.system.isNotBlank()) root.put("system", req.system)

        val messages = JSONArray()
        var lastRole: String? = null
        var lastContent: JSONArray? = null

        fun addBlock(role: String, block: JSONObject) {
            if (lastRole == role && lastContent != null) {
                lastContent!!.put(block)
            } else {
                val arr = JSONArray().put(block)
                messages.put(JSONObject().put("role", role).put("content", arr))
                lastContent = arr
                lastRole = role
            }
        }

        for (m in req.messages) {
            when (m.role) {
                "assistant" -> {
                    val trace = m.anthropicTrace
                    val replay = if (thinkingEnabled && trace != null && trace.providerId == provider.id &&
                        trace.baseUrl.trimEnd('/') == provider.baseUrl.trimEnd('/') && trace.model == req.model
                    ) runCatching { trace.blocks.map { JSONObject(it) } }.getOrNull() else null
                    if (!replay.isNullOrEmpty()) {
                        replay.forEach { addBlock("assistant", it) }
                        continue
                    }
                    if (m.content.isNotBlank()) addBlock("assistant", JSONObject().put("type", "text").put("text", m.content))
                    m.toolCalls.forEach { tc ->
                        val input = runCatching { JSONObject(tc.arguments.ifBlank { "{}" }) }.getOrDefault(JSONObject())
                        addBlock(
                            "assistant",
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                .put("input", input)
                        )
                    }
                    if (m.content.isBlank() && m.toolCalls.isEmpty()) {
                        addBlock("assistant", JSONObject().put("type", "text").put("text", "…"))
                    }
                }
                "tool" -> addBlock(
                    "user",
                    JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", m.toolCallId ?: "")
                        .put("content", m.content.ifBlank { "(пусто)" })
                )
                else -> addBlock("user", JSONObject().put("type", "text").put("text", m.content))
            }
        }
        root.put("messages", messages)

        if (req.tools.isNotEmpty()) {
            val tools = JSONArray()
            req.tools.forEach { tools.put(it.toAnthropicJson()) }
            root.put("tools", tools)
        }
        return root
    }

    internal suspend fun parseStream(
        source: BufferedSource,
        onReasoning: suspend (String) -> Unit = {},
        onDelta: suspend (String) -> Unit
    ): AiResponse {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val blocks = sortedMapOf<Int, JSONObject>()
        val toolJson = mutableMapOf<Int, StringBuilder>()
        val usage = UsageParser.Anthropic()
        var finish: String? = null
        var done = false

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.substring(5).trim()
            if (data.isEmpty()) continue
            val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (obj.optString("type")) {
                "error" -> throw AiException(obj.optJSONObject("error")?.optString("message") ?: "Ошибка API")
                "message_start" -> usage.update(obj.optJSONObject("message")?.optJSONObject("usage"))
                "content_block_start" -> {
                    val idx = obj.optInt("index", 0)
                    val block = obj.optJSONObject("content_block") ?: continue
                    blocks[idx] = JSONObject(block.toString())
                    when (block.optString("type")) {
                        "tool_use" -> toolJson[idx] = StringBuilder()
                        "text" -> block.textOrEmpty("text").takeIf { it.isNotEmpty() }?.let {
                            content.append(it); onDelta(it)
                        }
                        "thinking" -> block.textOrEmpty("thinking").takeIf { it.isNotEmpty() }?.let {
                            reasoning.append(it); onReasoning(it)
                        }
                    }
                }
                "content_block_delta" -> {
                    val idx = obj.optInt("index", 0)
                    val delta = obj.optJSONObject("delta") ?: continue
                    val block = blocks[idx] ?: continue
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.textOrEmpty("text")
                            block.put("text", block.textOrEmpty("text") + text)
                            if (text.isNotEmpty()) { content.append(text); onDelta(text) }
                        }
                        "thinking_delta" -> {
                            val text = delta.textOrEmpty("thinking")
                            block.put("thinking", block.textOrEmpty("thinking") + text)
                            if (text.isNotEmpty()) { reasoning.append(text); onReasoning(text) }
                        }
                        "signature_delta" -> block.put("signature", block.textOrEmpty("signature") + delta.textOrEmpty("signature"))
                        "input_json_delta" -> toolJson.getOrPut(idx) { StringBuilder() }.append(delta.textOrEmpty("partial_json"))
                    }
                }
                "content_block_stop" -> {
                    val idx = obj.optInt("index", 0)
                    val partial = toolJson[idx]?.toString().orEmpty()
                    if (partial.isNotBlank()) {
                        val input = runCatching { JSONObject(partial) }.getOrElse { throw AiException("Поток аргументов инструмента повреждён") }
                        blocks[idx]?.put("input", input)
                    }
                }
                "message_delta" -> {
                    finish = obj.optJSONObject("delta")?.textOrEmpty("stop_reason")?.ifBlank { finish }
                    usage.update(obj.optJSONObject("usage"))
                }
                "message_stop" -> { done = true; break }
            }
        }
        if (!done) throw AiException("Поток ответа прерван до завершения")
        val calls = blocks.mapNotNull { (idx, block) ->
            if (block.optString("type") != "tool_use") null else AiToolCall(
                id = block.optString("id", "toolu_$idx"),
                name = block.optString("name", ""),
                arguments = block.optJSONObject("input")?.toString() ?: "{}"
            ).takeIf { it.name.isNotBlank() }
        }
        return AiResponse(content.toString(), calls, finish, reasoning.toString(), blocks.values.map { it.toString() }, usage.result())
    }
}

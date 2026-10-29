package dev.opencode.mobile.mcp

import dev.opencode.mobile.ai.ProviderUrlPolicy
import dev.opencode.mobile.data.McpServer
import dev.opencode.mobile.tools.ToolOutcome
import dev.opencode.mobile.tools.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** MCP Streamable HTTP (2025-03-26): initialize, tools/list and tools/call only. */
class McpHttpClient(val server: McpServer) {
    data class Tool(val modelName: String, val remoteName: String, val spec: ToolSpec)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false
    private var session: String? = null
    private var initialized = false
    private var nextId = 0

    fun cancel() { cancelled = true; call?.cancel() }

    suspend fun connect(): List<Tool> = withContext(Dispatchers.IO) {
        ProviderUrlPolicy.error(server.url, server.bearerToken)?.let { throw IllegalArgumentException(it) }
        val info = JSONObject().put("protocolVersion", PROTOCOL).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "OpenCode Mobile").put("version", "0.3.1"))
        val result = rpc("initialize", info, initialize = true)
        require(result.optString("protocolVersion") == PROTOCOL) { "MCP-сервер не согласовал поддерживаемую версию $PROTOCOL" }
        initialized = true
        notifyInitialized()
        val tools = mutableListOf<Tool>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        repeat(5) {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val page = rpc("tools/list", params)
            val items = page.optJSONArray("tools")
            if (items != null) for (i in 0 until items.length()) {
                if (tools.size >= 50) break
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val schema = item.optJSONObject("inputSchema") ?: continue
                if (name.isBlank() || name.length > 300 || schema.toString().length > 16_000 || schema.optString("type") != "object") continue
                val modelName = toolName(server.id, name)
                if (tools.any { it.modelName == modelName }) continue
                val description = redact(item.optString("description")).take(1200)
                val title = server.name.replace(Regex("[\\r\\n\\p{Cntrl}]"), " ").take(80)
                tools.add(Tool(modelName, name, ToolSpec(modelName, "MCP $title: $description", schema)))
            }
            cursor = if (page.isNull("nextCursor")) null else page.optString("nextCursor").ifBlank { null }
            if (cursor == null || !seenCursors.add(cursor!!) || tools.size >= 50) return@withContext tools
        }
        tools
    }

    suspend fun invoke(tool: Tool, args: JSONObject): ToolOutcome = withContext(Dispatchers.IO) {
        try {
            val result = rpc("tools/call", JSONObject().put("name", tool.remoteName).put("arguments", args))
            val content = result.optJSONArray("content")
            val parts = mutableListOf<String>()
            if (content != null) for (i in 0 until minOf(content.length(), 100)) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> parts.add(redact(block.optString("text")).take(16_000))
                    else -> parts.add("[Неподдерживаемый тип содержимого MCP: ${block.optString("type").take(80)}]")
                }
            }
            result.optJSONObject("structuredContent")?.let { parts.add(redact(it.toString()).take(16_000)) }
            val ok = !result.optBoolean("isError", false)
            ToolOutcome(ok, parts.joinToString("\n").take(32_000).ifBlank { "(пустой результат MCP)" },
                if (ok) "MCP: ${server.name}" else "Ошибка MCP")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolOutcome(false, "Ошибка MCP ${server.name}: ${redact(e.message.orEmpty()).take(1000)}", "Ошибка MCP")
        }
    }

    private fun notifyInitialized() {
        send(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), null, false)
    }

    private fun rpc(method: String, params: JSONObject, initialize: Boolean = false): JSONObject {
        val id = ++nextId
        val envelope = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val response = send(envelope, id, initialize) ?: error("MCP не вернул ответ на $method")
        response.optJSONObject("error")?.let { error("MCP: ${redact(it.optString("message")).take(500)}") }
        return response.optJSONObject("result") ?: error("MCP не вернул result для $method")
    }

    private fun send(envelope: JSONObject, id: Int?, initialize: Boolean): JSONObject? {
        if (cancelled) throw CancellationException("MCP отменён")
        val builder = Request.Builder().url(server.url.trim())
            .post(envelope.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (server.bearerToken.isNotBlank()) builder.header("Authorization", "Bearer ${server.bearerToken}")
        session?.let { builder.header("MCP-Session-Id", it) }
        if (initialized) builder.header("MCP-Protocol-Version", PROTOCOL)
        val request = http.newCall(builder.build())
        call = request
        try {
            if (cancelled) { request.cancel(); throw CancellationException("MCP отменён") }
            request.execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}${if (response.code == 404) " (MCP-сессия завершена)" else ""}")
                if (initialize) response.header("MCP-Session-Id")?.let { value ->
                    require(value.length in 1..256 && value.all { it.code in 0x21..0x7e }) { "Некорректный MCP-Session-Id" }
                    session = value
                }
                if (id == null) return null
                return parseResponse(response, id)
            }
        } catch (e: java.io.IOException) {
            if (cancelled || request.isCanceled()) throw CancellationException("MCP отменён").apply { initCause(e) }
            throw e
        } finally { call = null }
    }

    private fun parseResponse(response: Response, id: Int): JSONObject {
        val body = response.body ?: error("Пустой ответ MCP")
        val source = body.source()
        if (response.header("Content-Type").orEmpty().substringBefore(';').trim().equals("text/event-stream", true)) {
            val data = StringBuilder()
            var total = 0
            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict(MAX_RESPONSE.toLong())
                total += line.length
                require(total <= MAX_RESPONSE) { "Ответ MCP слишком велик" }
                if (line.isEmpty()) {
                    parseEvent(data.toString(), id)?.let { return it }
                    data.setLength(0)
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(5).trimStart())
                }
            }
            return parseEvent(data.toString(), id) ?: error("Поток MCP завершился без ответа")
        }
        val text = readBounded(source)
        val obj = JSONObject(text)
        require(obj.opt("id")?.toString() == id.toString()) { "Несовпадение JSON-RPC id" }
        return obj
    }

    private fun parseEvent(data: String, id: Int): JSONObject? {
        if (data.isBlank()) return null
        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return null
        if (obj.has("method") && obj.has("id")) error("Запросы сервера к клиенту в MCP пока не поддерживаются")
        return obj.takeIf { it.opt("id")?.toString() == id.toString() }
    }

    private fun readBounded(source: BufferedSource): String {
        val buffer = okio.Buffer()
        while (true) {
            val count = source.read(buffer, minOf(8192L, MAX_RESPONSE.toLong() + 1 - buffer.size))
            if (count < 0) break
            require(buffer.size <= MAX_RESPONSE) { "Ответ MCP слишком велик" }
        }
        return buffer.readUtf8()
    }

    private fun redact(text: String): String = if (server.bearerToken.isEmpty()) text else text.replace(server.bearerToken, "[токен скрыт]")

    companion object {
        private const val PROTOCOL = "2025-03-26"
        private const val MAX_RESPONSE = 1_000_000
        internal fun toolName(serverId: String, remote: String): String {
            fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val safe = remote.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifBlank { "tool" }
            return "mcp_${hash(serverId)}_${safe}_${hash(remote)}"
        }
    }
}

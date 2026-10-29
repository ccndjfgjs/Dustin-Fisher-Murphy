package dev.opencode.mobile.ai

import dev.opencode.mobile.data.AnthropicTrace
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ModelReasoningCatalog
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ReasoningLevel
import dev.opencode.mobile.data.ReasoningProtocol
import dev.opencode.mobile.data.Role
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningTest {
    private val openAi = Provider(id = "openai", name = "OpenAI", baseUrl = "https://api.openai.com/v1", model = "o4-mini")
    private val claude = Provider(id = "claude", name = "Claude", kind = "anthropic", baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-5")
    private fun request(model: String, level: ReasoningLevel = ReasoningLevel.OFF, messages: List<AiMessage> = emptyList()) =
        AiRequest(model = model, system = "", messages = messages, tools = emptyList(), reasoningLevel = level)

    @Test fun catalogueFlagsOnlyKnownModelsAndEndpoints() {
        assertEquals(ReasoningProtocol.OPENAI_EFFORT, ModelReasoningCatalog.capability(openAi)?.protocol)
        assertTrue(ModelReasoningCatalog.capability(openAi)!!.alwaysOn)
        assertNull(ModelReasoningCatalog.capability(openAi.copy(model = "gpt-4o")))
        assertNull(ModelReasoningCatalog.capability(openAi.copy(baseUrl = "https://api.openai.com.attacker.test")))
        assertEquals(ReasoningProtocol.ANTHROPIC_MANUAL, ModelReasoningCatalog.capability(claude)?.protocol)
        assertEquals(ReasoningProtocol.ANTHROPIC_ADAPTIVE, ModelReasoningCatalog.capability(claude.copy(model = "claude-opus-4-8"))?.protocol)
        assertNull(ModelReasoningCatalog.capability(claude.copy(model = "unknown")))
        val deep = openAi.copy(baseUrl = "https://api.deepseek.com", model = "deepseek-flash")
        assertFalse(ModelReasoningCatalog.toolsRestricted(deep, deep.model, ReasoningLevel.OFF))
        assertTrue(ModelReasoningCatalog.toolsRestricted(deep, deep.model, ReasoningLevel.LOW))
        assertFalse(ModelReasoningCatalog.toolsRestricted(deep.copy(baseUrl = "https://example.com"), deep.model, ReasoningLevel.LOW))
    }

    @Test fun requestOptionsFollowProviderAndModel() {
        val open = OpenAiCompatibleClient(openAi).buildPayload(request(openAi.model, ReasoningLevel.HIGH))
        assertEquals("high", open.getString("reasoning_effort"))
        assertTrue(open.has("max_completion_tokens"))
        assertFalse(open.has("temperature"))
        assertFalse(open.has("max_tokens"))
        val deep = openAi.copy(baseUrl = "https://api.deepseek.com", model = "deepseek-v4-pro")
        val on = OpenAiCompatibleClient(deep).buildPayload(request(deep.model, ReasoningLevel.MEDIUM))
        assertEquals("enabled", on.getJSONObject("thinking").getString("type"))
        assertEquals("high", on.getString("reasoning_effort"))
        assertEquals("disabled", OpenAiCompatibleClient(deep).buildPayload(request(deep.model)).getJSONObject("thinking").getString("type"))
        val qwen = openAi.copy(baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1", model = "qwen3.7-plus")
        assertTrue(OpenAiCompatibleClient(qwen).buildPayload(request(qwen.model, ReasoningLevel.LOW)).getBoolean("enable_thinking"))
        assertFalse(OpenAiCompatibleClient(qwen).buildPayload(request(qwen.model)).getBoolean("enable_thinking"))
        val manual = AnthropicClient(claude).buildPayload(request(claude.model, ReasoningLevel.MEDIUM))
        assertEquals(2048, manual.getJSONObject("thinking").getInt("budget_tokens"))
        assertFalse(manual.has("temperature"))
        val adaptiveProvider = claude.copy(model = "claude-opus-4-8")
        val adaptive = AnthropicClient(adaptiveProvider).buildPayload(request(adaptiveProvider.model, ReasoningLevel.HIGH))
        assertEquals("adaptive", adaptive.getJSONObject("thinking").getString("type"))
        assertEquals("high", adaptive.getJSONObject("output_config").getString("effort"))
        assertEquals("disabled", AnthropicClient(adaptiveProvider).buildPayload(request(adaptiveProvider.model)).getJSONObject("thinking").getString("type"))
    }

    @Test fun openAiStreamsReasoningSeparatelyAndRejectsTruncation() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"reasoning_content":"first "}}]}
            data: {"choices":[{"delta":{"reasoning":"second","content":"answer"},"finish_reason":"stop"}]}
            data: [DONE]
        """.trimIndent() + "\n"
        val thoughts = mutableListOf<String>()
        val text = mutableListOf<String>()
        val client = OpenAiCompatibleClient(openAi)
        val result = client.parseStream(Buffer().writeUtf8(stream), onReasoning = { thoughts.add(it) }) { text.add(it) }
        assertEquals("first second", result.reasoning)
        assertEquals(listOf("first ", "second"), thoughts)
        assertEquals(listOf("answer"), text)
        val plain = client.parseStream(Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n")) { }
        assertEquals("", plain.reasoning)
        assertEquals("hidden", client.parseNonStream("""{"choices":[{"message":{"content":"ok","reasoning":"hidden"}}]}""").reasoning)
        try {
            client.parseStream(Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"partial\"}}]}\n")) { }
            fail("truncated response must not be accepted")
        } catch (expected: AiException) { assertTrue(expected.message!!.contains("прерван")) }
    }

    @Test fun anthropicSignedBlocksSurviveStreamingAndAreScopedToOrigin() = runBlocking {
        val stream = """
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"мысль"}}
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque+/="}}
            data: {"type":"content_block_stop","index":0}
            data: {"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"private+/="}}
            data: {"type":"content_block_stop","index":1}
            data: {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}
            data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"смотрю"}}
            data: {"type":"content_block_stop","index":2}
            data: {"type":"content_block_start","index":3,"content_block":{"type":"tool_use","id":"tool-1","name":"read_file","input":{}}}
            data: {"type":"content_block_delta","index":3,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a\"}"}}
            data: {"type":"content_block_stop","index":3}
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
            data: {"type":"message_stop"}
        """.trimIndent() + "\n"
        val thoughts = mutableListOf<String>()
        val client = AnthropicClient(claude)
        val result = client.parseStream(Buffer().writeUtf8(stream), onReasoning = { thoughts.add(it) }) { }
        assertEquals(listOf("мысль"), thoughts)
        assertEquals("смотрю", result.content)
        assertEquals(listOf("thinking", "redacted_thinking", "text", "tool_use"), result.anthropicBlocks.map { JSONObject(it).getString("type") })
        assertEquals("opaque+/=", JSONObject(result.anthropicBlocks[0]).getString("signature"))
        assertEquals("private+/=", JSONObject(result.anthropicBlocks[1]).getString("data"))
        assertEquals("a", JSONObject(result.toolCalls.single().arguments).getString("path"))
        val trace = AnthropicTrace(claude.id, claude.baseUrl, claude.model, result.anthropicBlocks)
        val saved = ChatMessage(role = Role.ASSISTANT, content = result.content, reasoning = result.reasoning, reasoningDurationMs = 1200, anthropicTrace = trace)
        val restored = ChatMessage.fromJson(JSONObject(saved.toJson().toString()))
        assertEquals("мысль", restored.reasoning)
        assertEquals(1200L, restored.reasoningDurationMs)
        assertEquals(trace, restored.anthropicTrace)
        val assistant = AiMessage(role = "assistant", content = result.content, toolCalls = result.toolCalls, anthropicTrace = trace)
        val history = listOf(AiMessage(role = "user", content = "hi"), assistant, AiMessage(role = "tool", toolCallId = "tool-1", content = "ok"))
        fun payload(provider: Provider, model: String, level: ReasoningLevel): String =
            AnthropicClient(provider).buildPayload(request(model, level, history)).toString()
        val replay = JSONObject(payload(claude, claude.model, ReasoningLevel.LOW))
            .getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("opaque+/=", replay.getJSONObject(0).getString("signature"))
        assertEquals("private+/=", replay.getJSONObject(1).getString("data"))
        assertFalse(payload(claude.copy(id = "other"), claude.model, ReasoningLevel.LOW).contains("opaque"))
        assertFalse(payload(claude.copy(baseUrl = "https://example.com"), claude.model, ReasoningLevel.LOW).contains("opaque"))
        assertFalse(payload(claude, "claude-opus-4-5", ReasoningLevel.LOW).contains("opaque"))
        assertFalse(payload(claude, claude.model, ReasoningLevel.OFF).contains("opaque"))
        assertFalse(OpenAiCompatibleClient(openAi).buildPayload(request(openAi.model, ReasoningLevel.HIGH, history)).toString().contains("opaque"))
        try {
            client.parseStream(Buffer().writeUtf8(stream.substringBefore("data: {\"type\":\"message_stop\"}"))) { }
            fail("truncated Anthropic stream must not be accepted")
        } catch (expected: AiException) { assertTrue(expected.message!!.contains("прерван")) }
    }
}

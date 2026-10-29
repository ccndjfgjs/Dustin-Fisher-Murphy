package dev.opencode.mobile.ai

import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ModelPricing
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.TokenUsage
import dev.opencode.mobile.data.UsageCost
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    private val open = Provider(name = "open", baseUrl = "https://api.openai.com/v1", model = "m")
    private val claude = Provider(name = "anthropic", kind = "anthropic", baseUrl = "https://api.anthropic.com", model = "m")

    @Test fun openAiReadsFinalUsageChunkWithoutChoices() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}
            data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":30}}}
            data: [DONE]
        """.trimIndent() + "\n"
        val client = OpenAiCompatibleClient(open)
        val usage = client.parseStream(Buffer().writeUtf8(stream)) {}.usage!!
        assertEquals(100L, usage.inputTokens)
        assertEquals(20L, usage.outputTokens)
        assertEquals(30L, usage.cachedInputTokens)
        assertNull(usage.costUsd)
        val payload = client.buildPayload(AiRequest("m", "", emptyList(), emptyList()))
        assertTrue(payload.getJSONObject("stream_options").getBoolean("include_usage"))
        val custom = OpenAiCompatibleClient(open.copy(baseUrl = "https://custom.example/v1"))
        assertFalse(custom.buildPayload(AiRequest("m", "", emptyList(), emptyList())).has("stream_options"))
        assertTrue(OpenAiCompatibleClient(open.copy(baseUrl = "https://custom.example/v1", streamUsage = true))
            .buildPayload(AiRequest("m", "", emptyList(), emptyList())).has("stream_options"))
    }

    @Test fun openRouterCostIsOnlyAcceptedFromItsDocumentedHost() {
        val body = """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":2,"cost":0.012}}"""
        val usage = OpenAiCompatibleClient(open.copy(baseUrl = "https://openrouter.ai/api/v1")).parseNonStream(body).usage!!
        assertEquals(0.012, usage.costUsd!!, 0.000001)
        assertNull(OpenAiCompatibleClient(open).parseNonStream(body).usage!!.costUsd)
        assertNull(OpenAiCompatibleClient(open.copy(baseUrl = "https://openrouter.ai.evil.test")).parseNonStream(body).usage!!.costUsd)
    }

    @Test fun anthropicUsageIsCumulativeAndIncludesCache() = runBlocking {
        val stream = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":20,"cache_creation_input_tokens":10,"output_tokens":0}}}
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"ok"}}
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}
            data: {"type":"message_delta","usage":{"output_tokens":8}}
            data: {"type":"message_stop"}
        """.trimIndent() + "\n"
        val usage = AnthropicClient(claude).parseStream(Buffer().writeUtf8(stream)) {}.usage!!
        assertEquals(130L, usage.inputTokens)
        assertEquals(8L, usage.outputTokens)
        assertEquals(20L, usage.cachedInputTokens)
        assertEquals(10L, usage.cacheWriteTokens)
        assertEquals(usage, ChatMessage.fromJson(ChatMessage(role = Role.ASSISTANT, usage = usage).toJson()).usage)
    }

    @Test fun estimationRequiresAllRelevantPricesAndNeverOverridesProviderCost() {
        val usage = TokenUsage(130, 8, 20, 10)
        val partial = ModelPricing("m", 3.0, 15.0)
        assertNull(UsageCost.calculate(usage, partial)!!.costUsd)
        val rates = partial.copy(cachedInput = 0.3, cacheWrite = 3.75)
        val calculated = UsageCost.calculate(usage, rates)!!
        assertTrue(calculated.estimated)
        assertEquals((100 * 3.0 + 8 * 15.0 + 20 * 0.3 + 10 * 3.75) / 1_000_000, calculated.costUsd!!, 1e-10)
        val billed = usage.copy(costUsd = 0.01)
        assertEquals(billed, UsageCost.calculate(billed, rates))
        assertEquals(rates, ModelPricing.fromJson(JSONObject(rates.toJson().toString())))
    }
}

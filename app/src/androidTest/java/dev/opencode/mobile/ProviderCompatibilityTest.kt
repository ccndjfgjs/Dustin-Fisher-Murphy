package dev.opencode.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.ai.AiMessage
import dev.opencode.mobile.ai.AiRequest
import dev.opencode.mobile.ai.AiToolCall
import dev.opencode.mobile.ai.OpenAiCompatibleClient
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.ToolCallRef
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCompatibilityTest {
    private fun client(url: String) = OpenAiCompatibleClient(Provider(name = "test", baseUrl = url, model = "model"))
    private fun request(model: String, messages: List<AiMessage> = emptyList()) =
        AiRequest(model = model, system = "", messages = messages, tools = emptyList())

    @Test fun vendorOptionsAreScopedToTheRightEndpointsAndModels() {
        val deepseek = client("https://api.deepseek.com").buildPayload(request("deepseek-flash"))
        assertEquals("disabled", deepseek.getJSONObject("thinking").getString("type"))
        val alibaba = client("https://coding-intl.dashscope.aliyuncs.com/v1").buildPayload(request("qwen3.7-plus"))
        assertFalse(alibaba.getBoolean("enable_thinking"))
        assertFalse(client("https://coding-intl.dashscope.aliyuncs.com/v1").buildPayload(request("kimi-k2.5")).has("enable_thinking"))
        val generic = client("https://example.com/v1").buildPayload(request("deepseek-flash"))
        assertFalse(generic.has("thinking"))
        assertFalse(generic.has("enable_thinking"))
    }

    @Test fun jsonNullDoesNotBecomeVisibleTextAndStreamKeepsGeminiSignature() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call-a","function":{"name":"read_file","arguments":"{\"path\":"},"extra_content":{"google":{"thought_signature":"opaque-123"}}}]}}]}
            data: {"choices":[{"delta":{"content":"ok","tool_calls":[{"index":0,"function":{"arguments":"\"foo\"}"}}]},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent() + "\n"
        val deltas = mutableListOf<String>()
        val response = client("https://generativelanguage.googleapis.com/v1beta/openai")
            .parseStream(Buffer().writeUtf8(stream)) { deltas.add(it) }
        assertEquals("ok", response.content)
        assertEquals(listOf("ok"), deltas)
        assertEquals("opaque-123", response.toolCalls.single().googleThoughtSignature)
        assertEquals("{\"path\":\"foo\"}", response.toolCalls.single().arguments)
        assertEquals("tool_calls", response.finishReason)
        val nonStream = client("https://example.com/v1").parseNonStream("""{"choices":[{"message":{"content":null}}]}""")
        assertEquals("", nonStream.content)
    }

    @Test fun geminiCallsWithoutIndicesAreKeptSeparate() = runBlocking {
        val stream = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"a","function":{"name":"read_file","arguments":"{}"},"extra_content":{"google":{"thought_signature":"sig-a"}}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"id":"b","function":{"name":"list_dir","arguments":"{}"}}]}}]}
            data: [DONE]
        """.trimIndent() + "\n"
        val response = client("https://generativelanguage.googleapis.com/v1beta/openai")
            .parseStream(Buffer().writeUtf8(stream)) { }
        assertEquals(listOf("a", "b"), response.toolCalls.map { it.id })
        assertEquals("sig-a", response.toolCalls.first().googleThoughtSignature)
    }

    @Test fun signatureSurvivesSessionAndIsOnlySentToGoogle() {
        val signature = "opaque+/=="
        val call = ToolCallRef("id", "read_file", "{}", signature)
        val session = ChatSession(cwd = "/tmp", messages = listOf(ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call))))
        val restored = ChatSession.fromJson(JSONObject(session.toJson().toString()))
        assertEquals(signature, restored.messages.single().toolCalls.single().googleThoughtSignature)
        assertNull(ToolCallRef.fromJson(JSONObject("""{"id":"old","name":"read_file","arguments":"{}"}""")).googleThoughtSignature)
        val messages = listOf(AiMessage(role = "assistant", toolCalls = listOf(AiToolCall("id", "read_file", "{}", signature))))
        fun firstCall(url: String): JSONObject = client(url).buildPayload(request("model", messages))
            .getJSONArray("messages").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0)
        val google = firstCall("https://generativelanguage.googleapis.com/v1beta/openai")
        assertEquals(signature, google.getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertFalse(firstCall("https://example.com/v1").has("extra_content"))
        assertTrue(restored.messages.single().toolCalls.single().arguments.isNotBlank())
    }
}

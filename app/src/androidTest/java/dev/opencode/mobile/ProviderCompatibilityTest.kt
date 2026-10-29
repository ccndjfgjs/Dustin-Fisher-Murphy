package dev.opencode.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.ai.AiClients
import dev.opencode.mobile.ai.AiMessage
import dev.opencode.mobile.ai.AiRequest
import dev.opencode.mobile.ai.AiToolCall
import dev.opencode.mobile.ai.OpenAiCompatibleClient
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.ToolCallRef
import dev.opencode.mobile.tools.Tools
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

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

    @Test fun compatibleClientSendsToolHistoryOverHttpToLocalServer() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val stream = """
                data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call-1","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                data: [DONE]
            """.trimIndent() + "\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"Готово"}}]}"""))
            val url = server.url("/v1").toString()
            val ai = AiClients.create(Provider(name = "local", baseUrl = url, model = "local-model"))
            val first = request("local-model", listOf(AiMessage(role = "user", content = "Проверь"))).copy(tools = Tools.specs)
            val response = ai.complete(first) { }
            assertEquals("read_file", response.toolCalls.single().name)
            val next = first.copy(
                stream = false,
                messages = first.messages + AiMessage(role = "assistant", toolCalls = response.toolCalls) +
                    AiMessage(role = "tool", toolCallId = "call-1", content = "файл прочитан")
            )
            assertEquals("Готово", ai.complete(next) { }.content)
            val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            val secondRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", firstRequest.method)
            assertEquals("/v1/chat/completions", firstRequest.path)
            assertTrue(JSONObject(firstRequest.body.readUtf8()).getJSONArray("tools").length() > 0)
            val body = JSONObject(secondRequest.body.readUtf8())
            assertFalse(body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            assertEquals("call-1", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertEquals("call-1", messages.getJSONObject(2).getString("tool_call_id"))
        }
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

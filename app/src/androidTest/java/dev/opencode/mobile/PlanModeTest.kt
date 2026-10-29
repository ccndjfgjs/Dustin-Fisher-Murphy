package dev.opencode.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.agent.AgentEngine
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.TodoItem
import dev.opencode.mobile.data.ToolCallRef
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlanModeTest {
    @Test fun maliciousToolCallsAreDeniedDespiteAutoApproval() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val root = File(ctx.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(root, "data.txt").apply { writeText("before") }
        try {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setBody("""
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"bad","function":{"name":"write_file","arguments":"{\"path\":\"data.txt\",\"content\":\"after\"}"}}]},"finish_reason":"tool_calls"}]}
                    data: [DONE]

                """.trimIndent()))
                server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"план\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n"))
                var approvals = 0
                var denied = ""
                val listener = object : AgentEngine.Listener {
                    override fun onStatus(text: String) = Unit
                    override fun onAssistantStart() = Unit
                    override fun onAssistantDelta(text: String) = Unit
                    override fun onAssistantDone(message: ChatMessage) = Unit
                    override fun onToolStart(call: ToolCallRef) = Unit
                    override fun onToolEnd(call: ToolCallRef, ok: Boolean, output: String) { denied = output; assertFalse(ok) }
                    override fun onTodo(items: List<TodoItem>) = Unit
                    override fun onCommandOutputChunk(chunk: String) = Unit
                    override suspend fun approve(call: ToolCallRef): Boolean { approvals++; return true }
                }
                val provider = Provider(name = "test", baseUrl = server.url("/v1").toString(), model = "test")
                AgentEngine().run(provider, provider.model, listOf(ChatMessage(role = Role.USER, content = "edit")),
                    root.path, canUseRoot = true, autoApprove = true, maxIterations = 2, systemAppend = "",
                    streaming = true, planMode = true, listener = listener)
                assertEquals("before", file.readText())
                assertEquals(0, approvals)
                assertTrue(denied, denied.contains("заблокирован"))
                val request = JSONObject(server.takeRequest().body.readUtf8())
                val names = request.getJSONArray("tools").let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).getJSONObject("function").getString("name") }
                }
                assertFalse(names.contains("write_file"))
                assertFalse(names.contains("run_command"))
            }
        } finally { root.deleteRecursively() }
    }
}

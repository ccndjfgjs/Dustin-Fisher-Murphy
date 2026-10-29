package dev.opencode.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.agent.AgentEngine
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.McpServer
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.TodoItem
import dev.opencode.mobile.data.ToolCallRef
import dev.opencode.mobile.mcp.McpHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpAgentTest {
    @Test fun mcpCallAlwaysNeedsApprovalEvenWhenAutoApproveIsOn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        MockWebServer().use { mcp -> MockWebServer().use { model ->
            mcp.start(); model.start()
            mcp.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26"}}"""))
            mcp.enqueue(MockResponse().setResponseCode(202))
            mcp.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"mutate","description":"change something","inputSchema":{"type":"object","properties":{}}}]}}"""))
            val config = McpServer(id = "server-one", name = "trusted", url = mcp.url("/mcp").toString())
            val name = McpHttpClient.toolName(config.id, "mutate")
            val function = JSONObject().put("name", name).put("arguments", "{\"value\":42}")
            val call = JSONObject().put("index", 0).put("id", "c1").put("function", function)
            val choice = JSONObject().put("delta", JSONObject().put("tool_calls", org.json.JSONArray().put(call))).put("finish_reason", "tool_calls")
            model.enqueue(MockResponse().setBody("data: ${JSONObject().put("choices", org.json.JSONArray().put(choice))}\ndata: [DONE]\n"))
            model.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n"))
            var approvals = 0
            var approvalArgs = ""
            val listener = object : AgentEngine.Listener {
                override fun onStatus(text: String) = Unit
                override fun onAssistantStart() = Unit
                override fun onAssistantDelta(text: String) = Unit
                override fun onAssistantDone(message: ChatMessage) = Unit
                override fun onToolStart(call: ToolCallRef) = Unit
                override fun onToolEnd(call: ToolCallRef, ok: Boolean, output: String) = Unit
                override fun onTodo(items: List<TodoItem>) = Unit
                override fun onCommandOutputChunk(chunk: String) = Unit
                override suspend fun approve(call: ToolCallRef): Boolean { approvals++; approvalArgs = call.arguments; return false }
            }
            val provider = Provider(name = "model", baseUrl = model.url("/v1").toString(), model = "m")
            AgentEngine().run(provider, "m", listOf(ChatMessage(role = Role.USER, content = "test")), context.cacheDir.path,
                canUseRoot = false, autoApprove = true, maxIterations = 2, systemAppend = "", streaming = true,
                mcpServers = listOf(config), listener = listener)
            assertEquals(1, approvals)
            assertTrue(approvalArgs.contains("mutate"))
            assertTrue(approvalArgs.contains("42"))
            assertEquals(3, mcp.requestCount) // initialize, initialized, tools/list; no tools/call
        } }
    }
}

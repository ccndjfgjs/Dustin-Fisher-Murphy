package dev.opencode.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.McpServer
import dev.opencode.mobile.mcp.McpHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpClientTest {
    @Test fun negotiatesSessionDiscoversSseToolAndCallsIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("MCP-Session-Id", "session-123")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"lookup\",\"description\":\"lookup a value\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}],\"nextCursor\":null}}\n\n"))
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"found"}]}}"""))
            val client = McpHttpClient(McpServer(name = "local", url = server.url("/mcp").toString()))
            val tool = client.connect().single()
            assertTrue(tool.modelName.startsWith("mcp_"))
            val result = client.invoke(tool, JSONObject().put("query", "x"))
            assertTrue(result.output, result.ok)
            assertEquals("found", result.output)
            val initialize = server.takeRequest()
            assertTrue(initialize.body.readUtf8().contains("initialize"))
            val notification = server.takeRequest()
            assertEquals("session-123", notification.getHeader("MCP-Session-Id"))
            assertEquals("2025-03-26", notification.getHeader("MCP-Protocol-Version"))
            val list = server.takeRequest()
            assertEquals("tools/list", JSONObject(list.body.readUtf8()).getString("method"))
            val call = server.takeRequest()
            assertEquals("session-123", call.getHeader("MCP-Session-Id"))
            assertEquals("tools/call", JSONObject(call.body.readUtf8()).getString("method"))
        }
    }

    @Test fun refusesBearerTokenOverHttpBeforeConnecting() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = McpHttpClient(McpServer(name = "local", url = server.url("/mcp").toString(), bearerToken = "secret"))
            assertTrue(runCatching { client.connect() }.isFailure)
            assertEquals(0, server.requestCount)
        }
    }
}

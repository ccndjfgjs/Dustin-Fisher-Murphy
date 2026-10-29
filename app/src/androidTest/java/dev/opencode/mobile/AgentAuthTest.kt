package dev.opencode.mobile

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentAuthTest {
    @Test fun unauthorizedResponseGivesSafeActionableErrorAndPersistsItsStatus() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("server-secret-should-not-be-shown"))
            val provider = Provider(name = "test", baseUrl = server.url("/v1").toString(), model = "test")
            val messages = mutableListOf<ChatMessage>()
            val listener = object : AgentEngine.Listener {
                override fun onStatus(text: String) = Unit
                override fun onAssistantStart() = Unit
                override fun onAssistantDelta(text: String) = Unit
                override fun onAssistantDone(message: ChatMessage) { messages.add(message) }
                override fun onToolStart(call: ToolCallRef) = Unit
                override fun onToolEnd(call: ToolCallRef, ok: Boolean, output: String) = Unit
                override fun onTodo(items: List<TodoItem>) = Unit
                override fun onCommandOutputChunk(chunk: String) = Unit
                override suspend fun approve(call: ToolCallRef) = false
            }
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            AgentEngine().run(
                provider = provider,
                model = provider.model,
                history = listOf(ChatMessage(role = Role.USER, content = "hello")),
                cwd = context.cacheDir.absolutePath,
                canUseRoot = false,
                autoApprove = false,
                maxIterations = 1,
                systemAppend = "",
                streaming = true,
                listener = listener
            )
            val message = messages.single()
            assertEquals(401, message.errorStatus)
            assertTrue(message.content.contains("Проверьте API-ключ"))
            assertFalse(message.content.contains("server-secret-should-not-be-shown"))
            assertEquals(401, ChatMessage.fromJson(JSONObject(message.toJson().toString())).errorStatus)
        }
    }
}

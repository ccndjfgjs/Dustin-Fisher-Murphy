package dev.opencode.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.ai.AiRequest
import dev.opencode.mobile.ai.OpenAiCompatibleClient
import dev.opencode.mobile.data.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestRetryTest {
    private fun provider(server: MockWebServer) = Provider(name = "test", baseUrl = server.url("/v1").toString(), model = "m")
    private val request = AiRequest("m", "", emptyList(), emptyList())

    @Test fun retries503BeforeResponseAndHonorsZeroRetryAfter() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\ndata: [DONE]\n"))
            val result = OpenAiCompatibleClient(provider(server)).complete(request) {}
            assertEquals("ok", result.content)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun neverRetriesAuthenticationOrAnInterruptedSuccessfulStream() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            assertTrue(runCatching { OpenAiCompatibleClient(provider(server)).complete(request) {} }.isFailure)
            assertEquals(1, server.requestCount)
            val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n" + "x".repeat(20_000)
            server.enqueue(MockResponse().setBody(partial).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            assertTrue(runCatching { OpenAiCompatibleClient(provider(server)).complete(request) {} }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }
}

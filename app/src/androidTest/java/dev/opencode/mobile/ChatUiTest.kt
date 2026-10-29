package dev.opencode.mobile

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.Provider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun dismissStoragePrompt() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("chat-input").fetchSemanticsNodes().isNotEmpty()
        }
        if (rule.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Позже").performClick()
        }
    }

    @Test fun simulatedAgentRunShowsToolAndCopiesCode() {
        dismissStoragePrompt()
        val app = ApplicationProvider.getApplicationContext<OpenCodeApp>()
        rule.waitUntil(10_000) { app.prefs.settings.value.lastSessionId != null }
        val previousSettings = app.prefs.settings.value
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""
                data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call-1","function":{"name":"list_dir","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                data: [DONE]

            """.trimIndent()))
            server.enqueue(MockResponse().setBody("""
                data: {"choices":[{"delta":{"content":"Отчёт готов.\n```kotlin\nprintln(42)\n```"},"finish_reason":"stop"}]}
                data: [DONE]

            """.trimIndent()))
            try {
                val local = Provider(name = "Тестовый сервер", baseUrl = server.url("/v1").toString(), model = "local-test-model")
                app.prefs.update { it.copy(providers = it.providers + local, activeProviderId = local.id, streaming = true) }
                rule.onNodeWithContentDescription("Новый чат").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithText("local-test-model").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("chat-input").performTextInput("Проверь структуру")
                rule.onNodeWithContentDescription("Отправить сообщение").performClick()
                rule.waitUntil(20_000) { server.requestCount >= 2 }
                rule.waitUntil(20_000) { rule.onAllNodesWithText("Отчёт готов.", substring = true).fetchSemanticsNodes().isNotEmpty() }
                rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription("Остановить ответ").fetchSemanticsNodes().isEmpty() }
                rule.onNodeWithContentDescription("Скопировать код").performClick()
                rule.runOnIdle {
                    val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals("println(42)", clipboard.primaryClip?.getItemAt(0)?.coerceToText(app).toString())
                }
                rule.onNodeWithTag("chat-messages").performScrollToNode(hasText("Просмотр папки"))
                rule.onNodeWithText("list_dir").assertDoesNotExist()
                rule.onNodeWithText("Просмотр папки").performClick()
                rule.onNodeWithText("list_dir").assertIsDisplayed()
            } finally {
                val testSession = app.prefs.settings.value.lastSessionId
                app.prefs.update { previousSettings }
                if (testSession != null && testSession != previousSettings.lastSessionId) app.sessions.delete(testSession)
            }
        }
    }

    @Test fun draftSurvivesOpeningCatalogAndReturningToChat() {
        dismissStoragePrompt()
        rule.onNodeWithTag("chat-input").performTextInput("Мой черновик")
        rule.onNodeWithContentDescription("Выбрать модель").performClick()
        rule.onNodeWithText("Добавить или настроить провайдера").performClick()
        rule.onNodeWithTag("provider-search").performTextInput("Alibaba Cloud Coding")
        rule.onNodeWithText("Alibaba Cloud · Coding Plan").assertIsDisplayed().performClick()
        rule.onNodeWithTag("provider-url").assertTextContains("coding-intl.dashscope.aliyuncs.com", substring = true)
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithContentDescription("Чат").performClick()
        rule.onNodeWithTag("chat-input").assertTextContains("Мой черновик")
    }

}

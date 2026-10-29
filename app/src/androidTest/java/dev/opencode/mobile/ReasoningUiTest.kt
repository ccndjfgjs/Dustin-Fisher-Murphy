package dev.opencode.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ReasoningLevel
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.ui.AssistantMessage
import dev.opencode.mobile.ui.ChatComposer
import dev.opencode.mobile.ui.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReasoningUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun streamingReasoningStartsOpenThenClosesAndCanBeCopied() {
        var message by mutableStateOf(ChatMessage(role = Role.ASSISTANT, reasoning = "Проверяю шаги", streaming = true))
        lateinit var clipboard: ClipboardManager
        rule.setContent {
            clipboard = LocalClipboardManager.current
            OpenCodeTheme { AssistantMessage(message, onSettings = {}) }
        }
        rule.onNodeWithText("Проверяю шаги").assertExists()
        rule.runOnIdle { message = message.copy(streaming = false, content = "Готово", reasoningDurationMs = 1200) }
        rule.onNodeWithText("Проверяю шаги").assertDoesNotExist()
        rule.onNodeWithText("Рассуждение · 1 с").performClick()
        rule.onNodeWithText("Проверяю шаги").assertExists()
        rule.onNodeWithContentDescription("Скопировать рассуждение").performClick()
        rule.runOnIdle { assertEquals("Проверяю шаги", clipboard.getText()?.text) }
    }

    @Test fun supportedDeepSeekShowsRestrictionAndAllowsLevelChange() {
        var level by mutableStateOf(ReasoningLevel.OFF)
        val provider = Provider(name = "DeepSeek", baseUrl = "https://api.deepseek.com", model = "deepseek-flash")
        rule.setContent {
            OpenCodeTheme {
                ChatComposer(value = "hello", onValueChange = {}, provider = provider, busy = false,
                    onModelClick = {}, onSend = {}, onStop = {}, reasoningLevel = level, onReasoningLevelChange = { level = it })
            }
        }
        rule.onNodeWithText("Рассуждение: Выкл").performClick()
        rule.onNodeWithText("Среднее").performClick()
        rule.onNodeWithText("Рассуждение: Среднее").assertExists()
        rule.onNodeWithText("DeepSeek: инструменты отключены", substring = true).assertExists()
        rule.runOnIdle { assertEquals(ReasoningLevel.MEDIUM, level) }
    }

    @Test fun unsupportedModelDoesNotOfferReasoningSettings() {
        val provider = Provider(name = "Other", baseUrl = "https://example.com/v1", model = "unknown")
        rule.setContent {
            OpenCodeTheme { ChatComposer(value = "", onValueChange = {}, provider = provider, busy = false, onModelClick = {}, onSend = {}, onStop = {}) }
        }
        rule.onNodeWithText("Рассуждение:", substring = true).assertDoesNotExist()
    }
}

package dev.opencode.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test fun draftSurvivesOpeningCatalogAndReturningToChat() {
        dismissStoragePrompt()
        rule.onNodeWithTag("chat-input").performTextInput("Мой черновик")
        rule.onNodeWithContentDescription("Выбрать модель").performClick()
        rule.onNodeWithText("Добавить или настроить провайдера").performClick()
        rule.onNodeWithTag("provider-search").performTextInput("Alibaba Cloud Coding")
        rule.onNodeWithText("Alibaba Cloud · Coding Plan").assertIsDisplayed().performClick()
        rule.onNodeWithTag("provider-url").assertTextContains("coding-intl.dashscope.aliyuncs.com")
        rule.onNodeWithText("Отмена").performClick()
        rule.onNodeWithContentDescription("Чат").performClick()
        rule.onNodeWithTag("chat-input").assertTextContains("Мой черновик")
    }

}

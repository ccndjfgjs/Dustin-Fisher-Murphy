package dev.opencode.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.SessionSummary
import dev.opencode.mobile.ui.SessionsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionsSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun searchRenameAndExportRequireExplicitActions() {
        val one = SessionSummary("one", "Первый", "/workspace", 0)
        var query by mutableStateOf("")
        var renamed = ""
        var exported = ""
        rule.setContent {
            MaterialTheme {
                SessionsSheet(
                    sessions = if ("Первый".contains(query, true)) listOf(one) else emptyList(),
                    query = query, activeId = "one", busy = false, formatTime = { "сейчас" },
                    onQuery = { query = it }, onDismiss = {}, onNew = {}, onOpen = {},
                    onRename = { _, title -> renamed = title }, onExport = { exported = it }, onDelete = {}
                )
            }
        }
        rule.onNodeWithTag("session-search").performTextInput("нет")
        rule.onNodeWithText("Ничего не найдено").assertExists()
        rule.onNodeWithTag("session-search").performTextClearance()
        rule.onNodeWithContentDescription("Переименовать чат Первый").performClick()
        rule.onNodeWithTag("session-rename").performTextClearance()
        rule.onNodeWithTag("session-rename").performTextInput("Другое")
        rule.onNodeWithText("Сохранить").performClick()
        rule.runOnIdle { assertEquals("Другое", renamed) }
        rule.onNodeWithContentDescription("Экспортировать чат Первый").performClick()
        rule.runOnIdle { assertEquals("", exported) }
        rule.onNodeWithText("Выбрать файл").performClick()
        rule.runOnIdle { assertEquals("one", exported) }
    }
}

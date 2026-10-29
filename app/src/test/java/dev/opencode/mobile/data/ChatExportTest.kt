package dev.opencode.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportTest {
    @Test fun exportsConversationAndToolResultsWithoutSettings() {
        val session = ChatSession(title = "Проект", cwd = "/workspace", messages = listOf(
            ChatMessage(role = Role.USER, content = "Покажи файл"),
            ChatMessage(role = Role.ASSISTANT, content = "Готово", reasoning = "Проверю"),
            ChatMessage(role = Role.TOOL, toolName = "read_file", toolArgs = "{path: a}", content = "содержимое")
        ))
        val export = ChatExport.markdown(session)
        assertTrue(export.contains("# Проект"))
        assertTrue(export.contains("Покажи файл"))
        assertTrue(export.contains("Проверю"))
        assertTrue(export.contains("read_file"))
        assertTrue(export.contains("содержимое"))
        assertFalse(export.contains("apiKey"))
    }
}

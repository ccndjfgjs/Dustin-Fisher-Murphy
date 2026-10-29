package dev.opencode.mobile.agent

import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.ToolCallRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAdapterTest {
    @Test fun fillsInInterruptedToolResultsAndDropsOrphans() {
        val history = listOf(
            ChatMessage(role = Role.TOOL, content = "orphan", toolCallId = "old"),
            ChatMessage(role = Role.USER, content = "проверь"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(
                ToolCallRef("first", "read_file", "{}"),
                ToolCallRef("second", "run_command", "{}")
            )),
            ChatMessage(role = Role.TOOL, content = "успешно", toolCallId = "second"),
            ChatMessage(role = Role.USER, content = "продолжай")
        )
        val result = HistoryAdapter.toAiMessages(history)
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), result.map { it.role })
        assertEquals("first", result[2].toolCallId)
        assertTrue(result[2].content.contains("прервано"))
        assertEquals("second", result[3].toolCallId)
        assertEquals("успешно", result[3].content)
    }

    @Test fun keepsNormalConversation() {
        val history = listOf(
            ChatMessage(role = Role.USER, content = "hello"),
            ChatMessage(role = Role.ASSISTANT, content = "hi")
        )
        assertEquals(listOf("hello", "hi"), HistoryAdapter.toAiMessages(history).map { it.content })
    }
}

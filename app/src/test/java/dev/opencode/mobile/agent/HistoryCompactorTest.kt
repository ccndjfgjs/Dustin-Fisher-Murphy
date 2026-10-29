package dev.opencode.mobile.agent

import dev.opencode.mobile.ai.AiMessage
import dev.opencode.mobile.ai.AiToolCall
import org.junit.Assert.*
import org.junit.Test

class HistoryCompactorTest {
    @Test fun shortHistoryIsUnchanged() {
        val input = listOf(AiMessage("user", "hello"), AiMessage("assistant", "hi"))
        val result = HistoryCompactor.compact(input)
        assertFalse(result.compacted)
        assertEquals(input, result.messages)
    }

    @Test fun compactionPreservesCurrentRequestAndWholeToolExchanges() {
        val input = mutableListOf<AiMessage>()
        for (i in 0 until 50) {
            input.add(AiMessage("user", "request $i"))
            input.add(AiMessage("assistant", toolCalls = listOf(AiToolCall("call-$i", "read_file", "{path:$i}"))))
            input.add(AiMessage("tool", "result $i", toolCallId = "call-$i", toolName = "read_file"))
        }
        val copy = input.toList()
        val result = HistoryCompactor.compact(input)
        assertTrue(result.compacted)
        assertEquals(copy, input) // saved history remains untouched
        assertTrue(result.messages.first().syntheticSummary)
        assertTrue(result.messages.first().content.contains("Оригинал сохранён"))
        assertTrue(result.messages.any { it.role == "user" && it.content == "request 49" })
        result.messages.forEachIndexed { index, msg ->
            if (msg.role == "assistant" && msg.toolCalls.isNotEmpty()) {
                assertEquals("tool", result.messages[index + 1].role)
                assertEquals(msg.toolCalls.single().id, result.messages[index + 1].toolCallId)
            }
        }
    }

    @Test fun longSingleTurnKeepsTheUserRequestAndRecentSteps() {
        val input = mutableListOf(AiMessage("user", "important " + "x".repeat(120_000)))
        repeat(45) { i ->
            input.add(AiMessage("assistant", toolCalls = listOf(AiToolCall("c$i", "list_dir", "{}"))))
            input.add(AiMessage("tool", "step $i", toolCallId = "c$i", toolName = "list_dir"))
        }
        val result = HistoryCompactor.compact(input)
        assertTrue(result.compacted)
        assertEquals(input.first().content, result.messages.first { it.role == "user" && !it.syntheticSummary }.content)
        assertTrue(result.messages.any { it.content == "step 44" })
        assertTrue(result.messages.first().content.contains("step 0"))
    }
}

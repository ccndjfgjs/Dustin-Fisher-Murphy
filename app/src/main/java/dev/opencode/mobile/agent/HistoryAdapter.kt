package dev.opencode.mobile.agent

import dev.opencode.mobile.ai.AiMessage
import dev.opencode.mobile.ai.AiToolCall
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Role

/** Reconstruct valid tool-call exchanges even if a previous run was interrupted. */
internal object HistoryAdapter {
    fun toAiMessages(history: List<ChatMessage>): List<AiMessage> {
        val out = mutableListOf<AiMessage>()
        var index = 0
        while (index < history.size) {
            val message = history[index]
            when (message.role) {
                Role.USER -> out.add(AiMessage(role = "user", content = message.content))
                Role.ASSISTANT -> {
                    if (message.toolCalls.isEmpty() && message.content.isBlank()) {
                        index++
                        continue
                    }
                    out.add(AiMessage(
                        role = "assistant",
                        content = message.content,
                        toolCalls = message.toolCalls.map { AiToolCall(it.id, it.name, it.arguments) }
                    ))
                    if (message.toolCalls.isNotEmpty()) {
                        val results = mutableMapOf<String, ChatMessage>()
                        var next = index + 1
                        while (next < history.size && history[next].role == Role.TOOL) {
                            val result = history[next]
                            result.toolCallId?.let { results[it] = result }
                            next++
                        }
                        message.toolCalls.forEach { call ->
                            val result = results[call.id]
                            out.add(AiMessage(
                                role = "tool",
                                content = clamp(result?.content ?: INTERRUPTED),
                                toolCallId = call.id,
                                toolName = call.name
                            ))
                        }
                        index = next
                        continue
                    }
                }
                // A tool result without a preceding assistant tool call is invalid for both APIs.
                Role.TOOL -> Unit
            }
            index++
        }
        return out
    }

    private fun clamp(text: String, limit: Int = 16_000): String =
        if (text.length <= limit) text else text.take(limit) + "\n… [вывод обрезан, ${text.length - limit} символов]"

    private const val INTERRUPTED = "Нет результата: выполнение было прервано. Проверьте состояние файлов перед повтором."
}

package dev.opencode.mobile.data

/** Human-readable export. It contains conversation data, not provider credentials from settings. */
object ChatExport {
    fun markdown(session: ChatSession): String = buildString {
        append("# ").append(session.title.replace('\n', ' ')).append("\n\n")
        append("Рабочая папка: `").append(session.cwd.replace("`", "\\`")).append("`\n\n")
        session.messages.forEach { message ->
            val label = when (message.role) {
                Role.USER -> "Вы"
                Role.ASSISTANT -> "Ассистент"
                Role.TOOL -> "Инструмент: ${message.toolName.orEmpty()}"
            }
            append("## ").append(label.replace('\n', ' ')).append("\n\n")
            if (message.reasoning.isNotBlank()) {
                append("### Рассуждение\n\n").append(message.reasoning).append("\n\n")
            }
            message.usage?.let { usage ->
                append("Токены: вход ").append(usage.inputTokens ?: "—").append(", выход ").append(usage.outputTokens ?: "—")
                usage.costUsd?.let { append("; ").append(if (usage.estimated) "оценка" else "по API").append(" USD ").append(it) }
                append("\n\n")
            }
            if (message.toolArgs != null) append("Аргументы: ").append(message.toolArgs).append("\n\n")
            if (message.content.isNotBlank()) append(message.content).append("\n\n")
            message.attachments.forEach { attachment ->
                val longest = Regex("`+").findAll(attachment.content).map { it.value.length }.maxOrNull() ?: 0
                val fence = "`".repeat(maxOf(3, longest + 1))
                append("### Файл: ").append(attachment.path.replace('\n', ' ')).append("\n\n")
                append(fence).append('\n').append(attachment.content).append('\n').append(fence).append("\n\n")
            }
            if (message.toolCalls.isNotEmpty()) {
                message.toolCalls.forEach { call ->
                    append("Вызов инструмента: ").append(call.name).append("\n\n")
                    append(call.arguments).append("\n\n")
                }
            }
        }
    }
}

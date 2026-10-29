package dev.opencode.mobile.agent

import dev.opencode.mobile.ai.AiMessage

/**
 * Lossy, local extractive compaction. It never edits saved history and never breaks an
 * assistant tool-call/result group. The current user request and recent groups survive intact.
 */
internal object HistoryCompactor {
    private const val MAX_MESSAGES = 40
    private const val TRIGGER_CHARS = 96_000
    private const val KEEP_CHARS = 64_000
    private const val MIN_GROUPS = 12
    private const val MAX_GROUPS = 24

    data class Result(val messages: List<AiMessage>, val compacted: Boolean)

    fun compact(input: List<AiMessage>): Result {
        if (input.size <= MAX_MESSAGES && input.sumOf { weight(it).toLong() } <= TRIGGER_CHARS) return Result(input, false)
        val groups = group(input)
        var start = groups.size
        var chars = 0L
        var kept = 0
        while (start > 0) {
            val next = groups[start - 1].sumOf { weight(it).toLong() }
            if (kept >= MIN_GROUPS && (kept >= MAX_GROUPS || chars + next > KEEP_CHARS)) break
            start--
            chars += next
            kept++
        }
        if (start == 0) return Result(input, false)
        val latestUser = groups.indexOfLast { group -> group.any { it.role == "user" && !it.syntheticSummary } }
        val removed = groups.subList(0, start).filterIndexed { index, _ -> index != latestUser }
        if (removed.isEmpty()) return Result(input, false)
        val summary = AiMessage(role = "user", content = summarize(removed), syntheticSummary = true)
        val retainedUser = if (latestUser in 0 until start) groups[latestUser] else emptyList()
        val output = listOf(summary) + retainedUser + groups.subList(start, groups.size).flatten()
        if (output.size >= input.size && output.sumOf { weight(it).toLong() } >= input.sumOf { weight(it).toLong() }) {
            return Result(input, false)
        }
        return Result(output, true)
    }

    private fun group(messages: List<AiMessage>): List<List<AiMessage>> {
        val out = mutableListOf<List<AiMessage>>()
        var i = 0
        while (i < messages.size) {
            val block = mutableListOf(messages[i])
            if (messages[i].role == "assistant" && messages[i].toolCalls.isNotEmpty()) {
                var next = i + 1
                while (next < messages.size && messages[next].role == "tool") block.add(messages[next++])
                i = next
            } else i++
            out.add(block)
        }
        return out
    }

    private fun weight(m: AiMessage): Int = m.content.length + m.toolCalls.sumOf { it.arguments.length + it.name.length } +
        (m.anthropicTrace?.blocks?.sumOf { it.length } ?: 0)

    private fun fragment(text: String, max: Int = 260): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= max) return clean
        return clean.take(max / 2) + " … " + clean.takeLast(max / 2)
    }

    private fun summarize(groups: List<List<AiMessage>>): String {
        // Keep both the initial intent and the latest decisions when there are many groups.
        val chosen = if (groups.size <= 24) groups else groups.take(3) + groups.takeLast(21)
        return buildString {
            append("[Автоматически сжатая локальная история: ниже только выборочные фрагменты; детали могли быть потеряны. Оригинал сохранён в чате.]\n")
            if (groups.size > chosen.size) append("[Часть старых шагов пропущена.]\n")
            chosen.forEach { group ->
                group.forEach { msg ->
                    when (msg.role) {
                        "user" -> append("Пользователь: ").append(fragment(msg.content)).append('\n')
                        "assistant" -> {
                            if (msg.content.isNotBlank()) append("Ассистент: ").append(fragment(msg.content)).append('\n')
                            if (msg.toolCalls.isNotEmpty()) append("Инструменты: ")
                                .append(msg.toolCalls.joinToString { it.name + " " + fragment(it.arguments, 120) }).append('\n')
                        }
                        "tool" -> append("Результат ").append(msg.toolName.orEmpty()).append(": ").append(fragment(msg.content, 180)).append('\n')
                    }
                }
            }
            append("[Конец сжатого контекста]")
        }.take(10_000)
    }
}

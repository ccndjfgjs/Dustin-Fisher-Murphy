package dev.opencode.mobile.ai

import dev.opencode.mobile.data.TokenUsage
import org.json.JSONObject

internal object UsageParser {
    private fun count(o: JSONObject?, key: String): Long? {
        val value = o?.opt(key) as? Number ?: return null
        val number = value.toDouble()
        if (!number.isFinite() || number < 0 || number > Long.MAX_VALUE.toDouble() || number % 1.0 != 0.0) return null
        return value.toLong()
    }

    fun openAi(o: JSONObject?, openRouter: Boolean): TokenUsage? {
        o ?: return null
        val input = count(o, "prompt_tokens")
        val output = count(o, "completion_tokens")
        val cached = count(o.optJSONObject("prompt_tokens_details"), "cached_tokens") ?: 0
        // OpenRouter documents usage.cost in USD. Other gateways may use different units.
        val cost = if (openRouter && o.has("cost") && !o.isNull("cost"))
            o.optDouble("cost").takeIf { it.isFinite() && it >= 0 } else null
        if (input == null && output == null && cost == null) return null
        return TokenUsage(inputTokens = input, outputTokens = output, cachedInputTokens = cached, costUsd = cost)
    }

    /** Anthropic deltas are cumulative; missing fields must not erase earlier values. */
    class Anthropic {
        private var input: Long? = null
        private var output: Long? = null
        private var cached: Long? = null
        private var write: Long? = null

        fun update(o: JSONObject?) {
            if (o == null) return
            count(o, "input_tokens")?.let { input = it }
            count(o, "output_tokens")?.let { output = it }
            count(o, "cache_read_input_tokens")?.let { cached = it }
            count(o, "cache_creation_input_tokens")?.let { write = it }
        }

        fun result(): TokenUsage? {
            if (input == null && output == null && cached == null && write == null) return null
            val totalInput = input?.let { runCatching { Math.addExact(Math.addExact(it, cached ?: 0), write ?: 0) }.getOrNull() }
            return TokenUsage(totalInput, output, cached ?: 0, write ?: 0)
        }
    }
}

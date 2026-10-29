package dev.opencode.mobile.data

import org.json.JSONObject

/** Counts for one model response. Cached tokens are included in inputTokens. */
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val costUsd: Double? = null,
    val estimated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        inputTokens?.let { put("inputTokens", it) }
        outputTokens?.let { put("outputTokens", it) }
        put("cachedInputTokens", cachedInputTokens)
        put("cacheWriteTokens", cacheWriteTokens)
        costUsd?.let { put("costUsd", it) }
        if (estimated) put("estimated", true)
    }
    companion object {
        fun fromJson(o: JSONObject) = TokenUsage(
            inputTokens = if (o.has("inputTokens") && !o.isNull("inputTokens")) o.optLong("inputTokens") else null,
            outputTokens = if (o.has("outputTokens") && !o.isNull("outputTokens")) o.optLong("outputTokens") else null,
            cachedInputTokens = o.optLong("cachedInputTokens", 0),
            cacheWriteTokens = o.optLong("cacheWriteTokens", 0),
            costUsd = if (o.has("costUsd") && !o.isNull("costUsd")) o.optDouble("costUsd").takeIf { it.isFinite() && it >= 0 } else null,
            estimated = o.optBoolean("estimated", false)
        )
    }
}

/** User-supplied prices for exactly one model, in USD per million tokens. */
data class ModelPricing(
    val model: String,
    val input: Double,
    val output: Double,
    val cachedInput: Double? = null,
    val cacheWrite: Double? = null
) {
    fun toJson(): JSONObject = JSONObject().put("model", model).put("input", input).put("output", output).apply {
        cachedInput?.let { put("cachedInput", it) }
        cacheWrite?.let { put("cacheWrite", it) }
    }
    companion object {
        fun fromJson(o: JSONObject): ModelPricing? {
            fun number(key: String): Double? = if (!o.has(key) || o.isNull(key)) null else
                o.optDouble(key).takeIf { it.isFinite() && it >= 0 }
            val model = o.optString("model").trim()
            val input = number("input") ?: return null
            val output = number("output") ?: return null
            if (model.isEmpty()) return null
            return ModelPricing(model, input, output, number("cachedInput"), number("cacheWrite"))
        }
    }
}

object UsageCost {
    fun calculate(usage: TokenUsage?, pricing: ModelPricing?): TokenUsage? {
        if (usage == null || usage.costUsd != null || pricing == null) return usage
        val input = usage.inputTokens ?: return usage
        val output = usage.outputTokens ?: return usage
        val cached = usage.cachedInputTokens
        val write = usage.cacheWriteTokens
        if (input < 0 || output < 0 || cached < 0 || write < 0 || cached > input || write > input - cached) return usage
        val cachedRate = if (cached > 0) pricing.cachedInput ?: return usage else 0.0
        val writeRate = if (write > 0) pricing.cacheWrite ?: return usage else 0.0
        val amount = ((input - cached - write) * pricing.input + output * pricing.output +
            cached * cachedRate + write * writeRate) / 1_000_000.0
        return if (amount.isFinite() && amount >= 0) usage.copy(costUsd = amount, estimated = true) else usage
    }
}

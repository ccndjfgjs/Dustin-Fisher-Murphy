package dev.opencode.mobile.data

import dev.opencode.mobile.ai.ProviderRequestPolicy

/** Opt-in capabilities for known model/endpoint combinations, not a guess for arbitrary IDs. */
enum class ReasoningProtocol { ANTHROPIC_MANUAL, ANTHROPIC_ADAPTIVE, OPENAI_EFFORT, DEEPSEEK, ALIBABA_QWEN }
enum class ReasoningLevel(val label: String) {
    OFF("Выкл"), LOW("Низкое"), MEDIUM("Среднее"), HIGH("Высокое");

    companion object {
        fun from(value: String): ReasoningLevel = entries.firstOrNull { it.name.equals(value, true) } ?: OFF
    }
}

data class ModelReasoningCapability(val protocol: ReasoningProtocol, val alwaysOn: Boolean = false)

object ModelReasoningCatalog {
    private val manualClaude = setOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-4-5", "claude-opus-4-5", "claude-sonnet-3-7")
    private val adaptiveClaude = setOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5")
    private val openAi = setOf("o3", "o3-mini", "o4-mini")
    private val deepSeek = setOf("deepseek-flash", "deepseek-v4-pro", "deepseek-chat", "deepseek-reasoner")
    private val alibabaHybrid = setOf("qwen3.7-plus", "qwen3.6-plus", "qwen3.5-plus")

    fun capability(provider: Provider, model: String = provider.model): ModelReasoningCapability? {
        val id = model.trim().lowercase()
        return when {
            provider.kind == "anthropic" && id in manualClaude -> ModelReasoningCapability(ReasoningProtocol.ANTHROPIC_MANUAL)
            provider.kind == "anthropic" && id in adaptiveClaude -> ModelReasoningCapability(ReasoningProtocol.ANTHROPIC_ADAPTIVE)
            ProviderRequestPolicy.isOpenAi(provider.baseUrl) && id in openAi -> ModelReasoningCapability(ReasoningProtocol.OPENAI_EFFORT, alwaysOn = true)
            ProviderRequestPolicy.disableDeepSeekThinking(provider.baseUrl) && id in deepSeek -> ModelReasoningCapability(ReasoningProtocol.DEEPSEEK)
            ProviderRequestPolicy.disableAlibabaThinking(provider.baseUrl, id) && id in alibabaHybrid -> ModelReasoningCapability(ReasoningProtocol.ALIBABA_QWEN)
            else -> null
        }
    }

    /** DeepSeek requires replaying non-Anthropic reasoning during tool use, which we do not do. */
    fun toolsRestricted(provider: Provider, model: String, level: ReasoningLevel): Boolean =
        level != ReasoningLevel.OFF && capability(provider, model)?.protocol == ReasoningProtocol.DEEPSEEK
}

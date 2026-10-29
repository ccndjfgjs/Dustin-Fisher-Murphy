package dev.opencode.mobile.ai

import java.net.URI
import java.util.Locale

/**
 * Only opt known, documented hybrid models out of thinking. The agent does not replay
 * reasoning_content. Do not send vendor-specific parameters to other compatible endpoints.
 */
internal object ProviderRequestPolicy {
    private val alibabaHosts = setOf(
        "dashscope-intl.aliyuncs.com",
        "dashscope-us.aliyuncs.com",
        "dashscope.aliyuncs.com",
        "coding-intl.dashscope.aliyuncs.com",
        "coding.dashscope.aliyuncs.com"
    )
    private val alibabaHybridModels = setOf("qwen3.7-plus", "qwen3.6-plus", "qwen3.5-plus")

    private fun host(baseUrl: String): String =
        runCatching { URI(baseUrl.trim()).host?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")

    fun disableDeepSeekThinking(baseUrl: String): Boolean = host(baseUrl) == "api.deepseek.com"

    fun disableAlibabaThinking(baseUrl: String, model: String): Boolean {
        val host = host(baseUrl)
        val isAlibaba = host in alibabaHosts || host.endsWith(".maas.aliyuncs.com")
        return isAlibaba && model in alibabaHybridModels
    }

    fun isGoogleGemini(baseUrl: String): Boolean = host(baseUrl) == "generativelanguage.googleapis.com"
}

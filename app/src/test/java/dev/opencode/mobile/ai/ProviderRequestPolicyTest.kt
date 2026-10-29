package dev.opencode.mobile.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestPolicyTest {
    @Test fun deepSeekThinkingIsDisabledOnlyOnOfficialHost() {
        assertTrue(ProviderRequestPolicy.disableDeepSeekThinking("https://api.deepseek.com/v1"))
        assertTrue(ProviderRequestPolicy.disableDeepSeekThinking("https://API.DEEPSEEK.COM"))
        assertFalse(ProviderRequestPolicy.disableDeepSeekThinking("https://api.deepseek.com.attacker.test/v1"))
        assertFalse(ProviderRequestPolicy.disableDeepSeekThinking("https://openrouter.ai/api/v1"))
        assertFalse(ProviderRequestPolicy.disableDeepSeekThinking("not a URL"))
    }

    @Test fun alibabaOptOutIsRestrictedToKnownHybridModelsAndHosts() {
        assertTrue(ProviderRequestPolicy.disableAlibabaThinking("https://coding-intl.dashscope.aliyuncs.com/v1", "qwen3.7-plus"))
        assertTrue(ProviderRequestPolicy.disableAlibabaThinking("https://coding.dashscope.aliyuncs.com/v1", "qwen3.6-plus"))
        assertTrue(ProviderRequestPolicy.disableAlibabaThinking("https://dashscope-us.aliyuncs.com/compatible-mode/v1", "qwen3.7-plus"))
        assertTrue(ProviderRequestPolicy.disableAlibabaThinking("https://workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1", "qwen3.5-plus"))
        assertFalse(ProviderRequestPolicy.disableAlibabaThinking("https://coding.dashscope.aliyuncs.com/v1", "kimi-k2.5"))
        assertFalse(ProviderRequestPolicy.disableAlibabaThinking("https://coding.dashscope.aliyuncs.com/v1", "qwen3-coder-plus"))
        assertFalse(ProviderRequestPolicy.disableAlibabaThinking("https://coding.dashscope.aliyuncs.com.fake.test/v1", "qwen3.7-plus"))
        // An EU workspace placeholder must be replaced before it can be saved or used.
        assertTrue(ProviderUrlPolicy.error("https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1", "key") != null)
    }

    @Test fun googleSignaturesAreOnlyReplayedToGeminiApi() {
        assertTrue(ProviderRequestPolicy.isGoogleGemini("https://generativelanguage.googleapis.com/v1beta/openai"))
        assertFalse(ProviderRequestPolicy.isGoogleGemini("https://generativelanguage.googleapis.com.fake.test"))
        assertFalse(ProviderRequestPolicy.isGoogleGemini("https://openrouter.ai/api/v1"))
    }
}

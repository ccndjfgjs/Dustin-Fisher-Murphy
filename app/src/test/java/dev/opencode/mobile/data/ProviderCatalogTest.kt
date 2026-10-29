package dev.opencode.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test fun catalogHasDistinctPresetsAndSeparateAlibabaPlans() {
        val presets = ProviderCatalog.presets
        assertEquals(presets.size, presets.map { it.id }.distinct().size)
        val alibaba = presets.filter { it.id.startsWith("alibaba-") }
        assertEquals(4, alibaba.size)
        assertEquals(
            setOf(
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://coding-intl.dashscope.aliyuncs.com/v1",
                "https://coding.dashscope.aliyuncs.com/v1"
            ),
            alibaba.map { it.baseUrl }.toSet()
        )
        assertTrue(alibaba.all { it.note.isNotBlank() && it.model.isNotBlank() })
        assertTrue(alibaba.filter { "coding" in it.id }.all { it.model == "qwen3.7-plus" })
    }

    @Test fun catalogSearchAndNewInstancesDoNotReuseCredentialsOrIds() {
        assertEquals(4, ProviderCatalog.search("ALIBABA cloud").size)
        assertEquals("deepseek", ProviderCatalog.search("DEEPSEEK-FLASH").single().id)
        assertTrue(ProviderCatalog.search("does-not-exist").isEmpty())
        val preset = ProviderCatalog.presets.first()
        val first = preset.createProvider()
        val second = preset.createProvider()
        assertNotEquals(first.id, second.id)
        assertTrue(first.apiKey.isEmpty())
        assertEquals(preset.extraModels, first.extraModels)
    }

    @Test fun switchingProviderAndModelPreservesKeysAndEveryPreviousModel() {
        val one = Provider(id = "one", name = "One", baseUrl = "https://example.com/v1", apiKey = "secret-1", model = "model-a", extraModels = listOf("model-b"))
        val two = Provider(id = "two", name = "Two", baseUrl = "https://example.org/v1", apiKey = "secret-2", model = "model-c", extraModels = listOf("model-d"))
        val original = AppSettings(providers = listOf(one, two), activeProviderId = one.id)
        val switched = original.withSelectedModel(two.id, "model-d")
        assertEquals(two.id, switched.activeProviderId)
        assertEquals("model-d", switched.activeProvider.model)
        assertEquals(setOf("model-c", "model-d"), switched.activeProvider.models.toSet())
        assertEquals("secret-2", switched.activeProvider.apiKey)
        assertEquals(one, switched.providers.first())
        assertEquals(original, original.withSelectedModel("missing", "model-x"))
        assertEquals(original, original.withSelectedModel(two.id, "  "))
        val custom = switched.withSelectedModel(two.id, " custom-model ")
        assertEquals("custom-model", custom.activeProvider.model)
        assertEquals(setOf("model-c", "model-d", "custom-model"), custom.activeProvider.models.toSet())
        assertFalse(custom.activeProvider.extraModels.contains("custom-model"))
    }
}

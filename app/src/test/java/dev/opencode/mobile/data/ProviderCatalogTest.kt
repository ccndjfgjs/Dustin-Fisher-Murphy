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
        assertEquals(6, alibaba.size)
        assertEquals(
            setOf(
                "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
                "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
                "https://{WorkspaceId}.eu-central-1.maas.aliyuncs.com/compatible-mode/v1",
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
        assertEquals(6, ProviderCatalog.search("ALIBABA cloud").size)
        assertEquals("deepseek", ProviderCatalog.search("DEEPSEEK-FLASH").single().id)
        assertTrue(ProviderCatalog.search("does-not-exist").isEmpty())
        val preset = ProviderCatalog.presets.first()
        val first = preset.createProvider()
        val second = preset.createProvider()
        assertNotEquals(first.id, second.id)
        assertTrue(first.apiKey.isEmpty())
        assertEquals(preset.extraModels, first.extraModels)
    }

    @Test fun requestedServicesUseDocumentedEditablePresets() {
        val byId = ProviderCatalog.presets.associateBy { it.id }
        val expected = mapOf(
            "dahl" to Triple("https://inference.dahl.global/v1", "openai", "MiniMaxAI/MiniMax-M2.7"),
            "space-bunny" to Triple("https://spacebunnymodel.com/api/v1", "openai", "space-bunny-alpha"),
            "agentrouter-openai" to Triple("https://agentrouter.org/v1", "openai", "deepseek-v4-flash"),
            "agentrouter-anthropic" to Triple("https://agentrouter.org", "anthropic", "claude-opus-5")
        )
        expected.forEach { (id, values) ->
            val preset = requireNotNull(byId[id])
            assertEquals(values.first, preset.baseUrl)
            assertEquals(values.second, preset.kind)
            assertEquals(values.third, preset.model)
            assertTrue(preset.note.isNotBlank())
            val first = preset.createProvider()
            val second = preset.createProvider()
            assertNotEquals(first.id, second.id)
            assertEquals("", first.apiKey)
            assertEquals(preset.kind, first.kind)
            assertEquals(preset.baseUrl, first.baseUrl)
            assertEquals(preset.model, first.model)
            assertEquals(preset.extraModels, first.extraModels)
        }
        assertEquals(listOf("deepseek-ai/DeepSeek-V4-Flash-0731", "zai-org/GLM-5.3-Flash"), byId.getValue("dahl").extraModels)
        assertEquals(listOf("space-bunny-flash"), byId.getValue("space-bunny").extraModels)
        assertEquals(listOf("glm-5.3"), byId.getValue("agentrouter-openai").extraModels)
        assertEquals(listOf("claude-opus-4-8"), byId.getValue("agentrouter-anthropic").extraModels)
    }

    @Test fun requestedServicesAreSearchableWithoutAlteringExistingAccounts() {
        assertEquals("dahl", ProviderCatalog.search("DAHL inference").single().id)
        assertEquals("space-bunny", ProviderCatalog.search("space bunny").single().id)
        assertEquals(
            setOf("agentrouter-openai", "agentrouter-anthropic"),
            ProviderCatalog.search("AgentRouter").map { it.id }.toSet()
        )
        val existing = Provider(id = "existing", name = "Saved", baseUrl = "https://example.com/v1", apiKey = "existing-key", model = "model")
        val settings = AppSettings(providers = listOf(existing), activeProviderId = existing.id)
        ProviderCatalog.presets.filter { it.id in setOf("dahl", "space-bunny", "agentrouter-openai", "agentrouter-anthropic") }
            .forEach { it.createProvider() }
        assertEquals(listOf(existing), settings.providers)
        assertEquals("existing", settings.activeProviderId)
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

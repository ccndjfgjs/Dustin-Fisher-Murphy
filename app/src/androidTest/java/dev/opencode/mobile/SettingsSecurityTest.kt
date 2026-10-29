package dev.opencode.mobile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.AppSettings
import dev.opencode.mobile.data.Prefs
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.data.ProviderCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsSecurityTest {
    private lateinit var context: Context
    private lateinit var name: String
    private lateinit var alias: String
    private val secret = "test-key-not-for-production"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        name = "test_${UUID.randomUUID()}"
        alias = "test_${UUID.randomUUID()}"
    }

    @After fun tearDown() {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }

    private fun settings(): AppSettings {
        val provider = Provider(id = "test", name = "Test", baseUrl = "https://example.com/v1", apiKey = secret, model = "test")
        return AppSettings(providers = listOf(provider), activeProviderId = provider.id)
    }

    @Test fun encryptsAndReloadsSettings() {
        val prefs = Prefs(context, name, alias)
        prefs.update { settings() }
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains(secret))
        assertEquals(secret, Prefs(context, name, alias).settings.value.activeProvider.apiKey)
    }

    @Test fun migratesOldPlaintextWithoutLosingKey() {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        sp.edit().putString("settings_v1", settings().toJson().toString()).commit()
        val migrated = Prefs(context, name, alias)
        assertEquals(secret, migrated.settings.value.activeProvider.apiKey)
        assertFalse(sp.contains("settings_v1"))
        assertFalse(sp.all.toString().contains(secret))
        assertEquals(secret, Prefs(context, name, alias).settings.value.activeProvider.apiKey)
    }

    @Test fun addingPresetKeepsExistingProviderAndEncryptedKey() {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        sp.edit().putString("settings_v1", settings().toJson().toString()).commit()
        val prefs = Prefs(context, name, alias)
        assertEquals(1, prefs.settings.value.providers.size) // Catalog does not mutate saved settings.
        val preset = ProviderCatalog.presets.first { it.id == "alibaba-coding-intl" }.createProvider()
            .copy(apiKey = "coding-plan-test-key")
        prefs.update { it.copy(providers = it.providers + preset, activeProviderId = preset.id) }
        val restored = Prefs(context, name, alias).settings.value
        assertEquals(settings().providers.single(), restored.providers.first())
        assertEquals("coding-plan-test-key", restored.activeProvider.apiKey)
        assertFalse(sp.all.toString().contains(secret))
        assertFalse(sp.all.toString().contains("coding-plan-test-key"))
    }

    @Test fun corruptedCiphertextIsNotOverwritten() {
        val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        sp.edit().putString("settings_v2_encrypted", "v1:broken:data").commit()
        val prefs = Prefs(context, name, alias)
        assertNotNull(prefs.storageError.value)
        prefs.update { settings() }
        assertEquals("v1:broken:data", sp.getString("settings_v2_encrypted", null))
        assertTrue(prefs.settings.value.activeProvider.apiKey == secret)
    }
}

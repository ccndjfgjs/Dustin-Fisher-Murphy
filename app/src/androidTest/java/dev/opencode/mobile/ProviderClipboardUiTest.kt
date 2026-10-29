package dev.opencode.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.ui.OpenCodeTheme
import dev.opencode.mobile.ui.ProviderDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderClipboardUiTest {
    @get:Rule val rule = createComposeRule()
    private val provider = Provider(name = "Test", baseUrl = "https://example.com/v1", model = "test")

    private fun copy(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    @Test fun explicitPasteTrimsAndShowHideDoesNotChangeTheKey() {
        var saved: Provider? = null
        copy("  \n  dummy-key  \n")
        rule.setContent {
            OpenCodeTheme { ProviderDialog(provider, isNew = false, onDismiss = {}, onSave = { saved = it }) }
        }
        rule.onNodeWithTag("paste-provider-key").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Показать API-ключ").performScrollTo().performClick()
        rule.onNodeWithTag("provider-key").assertTextContains("dummy-key")
        rule.onNodeWithContentDescription("Скрыть API-ключ").performClick()
        rule.onNodeWithText("Сохранить").performClick()
        rule.runOnIdle { assertEquals("dummy-key", saved?.apiKey) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun ctrlVPastesIntoPasswordField() {
        var saved: Provider? = null
        copy("dummy-hardware-key")
        rule.setContent {
            OpenCodeTheme { ProviderDialog(provider, isNew = false, onDismiss = {}, onSave = { saved = it }) }
        }
        rule.onNodeWithTag("provider-key").performScrollTo().performClick().performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.V)
            keyUp(Key.V)
            keyUp(Key.CtrlLeft)
        }
        rule.onNodeWithText("Сохранить").performClick()
        rule.runOnIdle { assertEquals("dummy-hardware-key", saved?.apiKey) }
    }

    @Test fun emptyClipboardExplainsHowToFixPaste() {
        copy("  \n ")
        rule.setContent {
            OpenCodeTheme { ProviderDialog(provider, isNew = false, onDismiss = {}, onSave = {}) }
        }
        rule.onNodeWithTag("paste-provider-key").performScrollTo().performClick()
        rule.onNodeWithText("Не удалось вставить ключ", substring = true).assertExists()
    }
}

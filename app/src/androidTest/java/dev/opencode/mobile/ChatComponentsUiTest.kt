package dev.opencode.mobile

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.mobile.data.AppSettings
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.ui.MarkdownText
import dev.opencode.mobile.ui.ModelPickerSheet
import dev.opencode.mobile.ui.OpenCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatComponentsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun modelPickerKeepsPreviouslySelectedModel() {
        val provider = Provider(id = "p", name = "Example", baseUrl = "https://example.com/v1", model = "model-a", extraModels = listOf("model-b"))
        var settings by mutableStateOf(AppSettings(providers = listOf(provider), activeProviderId = provider.id))
        var open by mutableStateOf(true)
        rule.setContent {
            OpenCodeTheme {
                if (open) ModelPickerSheet(
                    settings = settings, busy = false,
                    onDismiss = { open = false },
                    onSelect = { id, model -> settings = settings.withSelectedModel(id, model) },
                    onAddProvider = { }
                )
            }
        }
        rule.onNodeWithText("model-b").performClick()
        rule.runOnIdle { assertEquals("model-b", settings.activeProvider.model) }
        rule.onNodeWithText("model-a").assertIsDisplayed()
        rule.onNodeWithText("model-a").performClick()
        rule.runOnIdle { assertEquals("model-a", settings.activeProvider.model) }
    }

    @Test fun codeBlockHasWorkingCopyAction() {
        rule.setContent { OpenCodeTheme { MarkdownText("```kotlin\nprintln(42)\n```") } }
        rule.onNodeWithContentDescription("Скопировать код").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("println(42)", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context).toString())
        }
    }
}

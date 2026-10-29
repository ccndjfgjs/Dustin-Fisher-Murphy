package dev.opencode.mobile

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.Provider
import dev.opencode.mobile.ui.ChatComposer
import dev.opencode.mobile.ui.OpenCodeTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val provider = Provider(
        name = "Very long provider name", baseUrl = "https://example.com/v1",
        model = "a-very-long-model-name-that-must-not-push-the-button-off-screen"
    )

    private fun show(size: DpSize, fontScale: Float, busy: Boolean = false, onStop: () -> Unit = {}) {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(size) then DeviceConfigurationOverride.FontScale(fontScale)
            ) {
                OpenCodeTheme {
                    ChatComposer(
                        value = "hello", onValueChange = {}, provider = provider, busy = busy,
                        onModelClick = {}, onSend = {}, onStop = onStop
                    )
                }
            }
        }
    }

    private fun assertCompactAndAligned(screenWidth: Float) {
        val actions = rule.onNodeWithTag("chat-composer-actions").getUnclippedBoundsInRoot()
        val button = rule.onNodeWithTag("chat-send").getUnclippedBoundsInRoot()
        val model = rule.onNodeWithTag("chat-model-picker").getUnclippedBoundsInRoot()
        val actionsWidth = actions.right.value - actions.left.value
        val buttonWidth = button.right.value - button.left.value
        val buttonHeight = button.bottom.value - button.top.value
        val tolerance = actionsWidth * 0.02f
        assertTrue("button should be square", abs(buttonWidth - buttonHeight) < tolerance)
        assertEquals(40f / (screenWidth - 46f), buttonWidth / actionsWidth, 0.025f)
        assertTrue("button should end at the row's right edge", abs(actions.right.value - button.right.value) < tolerance)
        assertTrue("model chip must not overlap the button", model.right.value <= button.left.value + tolerance)
        val modelCenter = (model.top.value + model.bottom.value) / 2f
        val buttonCenter = (button.top.value + button.bottom.value) / 2f
        assertTrue("items should be vertically centered", abs(modelCenter - buttonCenter) < tolerance)
    }

    @Test fun narrowPortraitWithLargeFontKeepsSendButtonCompact() {
        show(DpSize(320.dp, 640.dp), fontScale = 1.8f)
        rule.onNodeWithContentDescription("Отправить сообщение").assertIsDisplayed()
        assertCompactAndAligned(320f)
    }

    @Test fun landscapeKeepsStopButtonCompactAndWorking() {
        var stopped = false
        show(DpSize(640.dp, 320.dp), fontScale = 1f, busy = true, onStop = { stopped = true })
        rule.onNodeWithContentDescription("Остановить ответ").assertIsDisplayed().performClick()
        assertCompactAndAligned(640f)
        rule.runOnIdle { assertTrue(stopped) }
    }

    @Test fun focusedInputDoesNotStretchSendButton() {
        show(DpSize(360.dp, 720.dp), fontScale = 1f)
        rule.onNodeWithTag("chat-input").performClick()
        rule.onNodeWithContentDescription("Отправить сообщение").assertIsDisplayed()
        assertCompactAndAligned(360f)
    }
}

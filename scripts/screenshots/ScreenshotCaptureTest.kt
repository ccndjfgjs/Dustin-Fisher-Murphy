package dev.opencode.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.ToolCallRef
import java.io.File
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on an isolated emulator: seeds a disposable local project and session, no API key. */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @BeforeClass @JvmStatic fun seed() {
            val app = ApplicationProvider.getApplicationContext<OpenCodeApp>()
            val project = File(app.filesDir, "demo-project").apply { mkdirs() }
            File(project, "app/src/main").mkdirs()
            File(project, "README.md").writeText("# Demo project\n")
            File(project, "build.gradle.kts").writeText("// Example project\n")
            val tool = ToolCallRef("demo-read", "read_file", """{"path":"README.md"}""")
            val session = ChatSession(
                id = "screenshot-demo", title = "Проверка проекта", cwd = project.absolutePath,
                messages = listOf(
                    ChatMessage(role = Role.USER, content = "Проверь проект и подскажи, с чего начать."),
                    ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(tool)),
                    ChatMessage(role = Role.TOOL, toolCallId = tool.id, toolName = tool.name, toolArgs = tool.arguments, toolOk = true, content = "# Demo project"),
                    ChatMessage(role = Role.ASSISTANT, content = "Проект готов к работе. Начните с `README.md`, затем проверьте настройки сборки.")
                )
            )
            check(app.sessions.save(session))
            app.prefs.update { it.copy(lastSessionId = session.id, lastWorkspace = project.absolutePath, allowRoot = false, autoApproveTools = false) }
        }
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        // Semantics can be idle before the emulator compositor presents the new screen.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(350)
        val app = ApplicationProvider.getApplicationContext<OpenCodeApp>()
        val dir = File(app.filesDir, "captures").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        checkNotNull(bitmap)
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun captureFourScreens() {
        if (rule.onAllNodesWithText("Позже").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Позже").performClick()
        }
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Проект готов к работе.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("chat")
        rule.onNodeWithContentDescription("Файлы").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("README.md").fetchSemanticsNodes().isNotEmpty() }
        capture("files")
        rule.onNodeWithContentDescription("Терминал").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Локальный shell", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("terminal")
        rule.onNodeWithContentDescription("Настройки").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("ПРОВАЙДЕР И МОДЕЛЬ").fetchSemanticsNodes().isNotEmpty() }
        capture("settings")
    }
}

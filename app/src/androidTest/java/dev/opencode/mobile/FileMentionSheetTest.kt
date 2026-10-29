package dev.opencode.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.ui.FileMentionSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileMentionSheetTest {
    @get:Rule val rule = createComposeRule()

    @Test fun searchesAndChoosesAProjectFile() {
        val app = ApplicationProvider.getApplicationContext<OpenCodeApp>()
        val root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        File(root, "hello world.kt").writeText("hello")
        var selected = ""
        try {
            rule.setContent { MaterialTheme { FileMentionSheet(root.path, {}, { selected = it }) } }
            rule.onNodeWithTag("mention-search").performTextInput("world")
            rule.waitUntil(10_000) { rule.onAllNodesWithText("hello world.kt").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("hello world.kt").performClick()
            rule.runOnIdle { assertEquals("hello world.kt", selected) }
        } finally { root.deleteRecursively() }
    }
}

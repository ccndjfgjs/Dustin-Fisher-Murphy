package dev.opencode.mobile.agent

import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectContextTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun quotedMentionsAreSnapshottedAndRoundTripInHistory() {
        val root = temp.newFolder("project")
        File(root, "my file.kt").writeText("original")
        File(root, "other.txt").writeText("второй")
        val attachments = ProjectContext.attachments(root.path, "Проверь @\"my file.kt\" и @other.txt")
        File(root, "my file.kt").writeText("changed")
        val original = ChatMessage(role = Role.USER, content = "Проверь файлы", attachments = attachments)
        val loaded = ChatMessage.fromJson(original.toJson())
        val sent = HistoryAdapter.toAiMessages(listOf(loaded)).single().content
        assertTrue(sent.contains("original"))
        assertTrue(sent.contains("второй"))
        assertFalse(sent.contains("changed"))
    }

    @Test fun projectRulesAndFileSearchAreLocalAndBounded() {
        val root = temp.newFolder("project")
        File(root, "AGENTS.md").writeText("use tests")
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("hello")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("private")
        assertEquals("use tests", ProjectContext.rules(root.path))
        assertEquals(listOf("src/Main.kt"), ProjectContext.findFiles(root.path, "main"))
        assertFalse(ProjectContext.findFiles(root.path, "config").contains(".git/config"))
        File(root, "AGENTS.md").writeBytes(ByteArray(65537) { 'x'.code.toByte() })
        assertTrue(runCatching { ProjectContext.rules(root.path) }.isFailure)
    }

    @Test fun rejectsTraversalSymlinksOutsideBinaryAndOversizedFiles() {
        val root = temp.newFolder("project")
        val outside = temp.newFile("outside.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath())
        File(root, "binary.bin").writeBytes(byteArrayOf(0, 1, 2))
        File(root, "large.txt").writeBytes(ByteArray(65537) { 65 })
        listOf("@../outside.txt", "@link.txt", "@binary.bin", "@large.txt", "@/etc/passwd").forEach { mention ->
            assertTrue(mention, runCatching { ProjectContext.attachments(root.path, mention) }.isFailure)
        }
        assertFalse(ProjectContext.findFiles(root.path, "link").contains("link.txt"))
    }

    @Test fun rejectsTooManyMentionsAndIgnoresEmailAddresses() {
        val root = temp.newFolder("project")
        assertEquals(emptyList<String>(), ProjectContext.mentions("email@example.com"))
        assertTrue(runCatching { ProjectContext.attachments(root.path, (1..6).joinToString(" ") { "@file$it" }) }.isFailure)
    }
}

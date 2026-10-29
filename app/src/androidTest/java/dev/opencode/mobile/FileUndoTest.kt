package dev.opencode.mobile

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.tools.FileUndoManager
import dev.opencode.mobile.tools.ToolContext
import dev.opencode.mobile.tools.Tools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FileUndoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun restoresEditedBytesAndSurvivesSessionSerialization() = runBlocking {
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(root, "file.txt").apply { writeText("before\n") }
        try {
            val ctx = ToolContext(root.path, useRoot = false, sandboxed = true)
            val result = Tools.execute("write_file", JSONObject().put("path", "file.txt").put("content", "after\n"), ctx)
            assertTrue(result.ok)
            val saved = ChatMessage.fromJson(ChatMessage(role = Role.TOOL, fileUndo = result.fileUndo).toJson()).fileUndo!!
            assertEquals("after\n", file.readText())
            FileUndoManager.undo(root.path, saved)
            assertEquals("before\n", file.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun newFileIsRemovedButLaterEditsAreNeverOverwritten() = runBlocking {
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val ctx = ToolContext(root.path, useRoot = false, sandboxed = true)
            val args = JSONObject().put("path", "new.txt").put("content", "agent")
            val first = Tools.execute("write_file", args, ctx).fileUndo!!
            val file = File(root, "new.txt")
            file.writeText("user change")
            assertTrue(runCatching { FileUndoManager.undo(root.path, first) }.isFailure)
            assertEquals("user change", file.readText())
            file.writeText("agent")
            FileUndoManager.undo(root.path, first)
            assertFalse(file.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedRootAndLinksNeverProduceSnapshots() {
        val root = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val target = File(root, "target").apply { writeText("a") }
        val link = File(root, "link")
        Os.symlink(target.path, link.path)
        try {
            assertTrue(runCatching { FileUndoManager.prepare(root.path, "target", useRoot = true) }.isFailure)
            assertTrue(runCatching { FileUndoManager.prepare(root.path, "link", useRoot = false) }.isFailure)
            assertTrue(runCatching { FileUndoManager.prepare(root.path, "../outside", useRoot = false) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}

package dev.opencode.mobile

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.opencode.mobile.data.ChatMessage
import dev.opencode.mobile.data.ChatSession
import dev.opencode.mobile.data.Role
import dev.opencode.mobile.data.SessionStore
import dev.opencode.mobile.shell.Shell
import dev.opencode.mobile.tools.ToolContext
import dev.opencode.mobile.tools.Tools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeSafetyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun shellWorksOnMinimumApiAndTimesOut() {
        val result = Shell.run("printf hello", useRoot = false, timeoutSec = 5)
        assertTrue(result.output, result.ok)
        assertEquals("hello", result.output)
        val slow = Shell.run("sleep 4", useRoot = false, timeoutSec = 1)
        assertTrue(slow.timedOut)
        assertFalse(slow.ok)
    }

    @Test fun recursiveSearchDoesNotFollowSymlinksOutsideWorkspace() = runBlocking {
        val base = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val workspace = File(base, "workspace").apply { mkdirs() }
        val outside = File(base, "private").apply { mkdirs() }
        File(workspace, "allowed.txt").writeText("ordinary text")
        File(outside, "secret.txt").writeText("sentinel-private-data")
        Os.symlink(outside.absolutePath, File(workspace, "link").absolutePath)
        try {
            val ctx = ToolContext(cwd = workspace.absolutePath, useRoot = false, sandboxed = true)
            val result = Tools.execute("grep", JSONObject().put("pattern", "sentinel-private-data"), ctx)
            assertTrue(result.ok)
            assertFalse(result.output.contains("secret.txt"))
        } finally {
            Shell.delete(base.absolutePath, recursive = true)
        }
    }

    @Test fun sessionSearchIncludesMessageHistoryAndManualTitleRoundTrips() {
        val store = SessionStore(context)
        val token = UUID.randomUUID().toString()
        val session = ChatSession(title = "Название $token", titleManuallySet = true, cwd = context.filesDir.absolutePath,
            messages = listOf(ChatMessage(role = Role.USER, content = "Секретный фрагмент $token")))
        try {
            assertTrue(store.save(session))
            assertTrue(store.load(session.id)!!.titleManuallySet)
            assertTrue(store.search("фрагмент $token").any { it.id == session.id })
            assertTrue(store.search("НАЗВАНИЕ $token").any { it.id == session.id })
            assertFalse(store.search("нет-$token").any { it.id == session.id })
        } finally { store.delete(session.id) }
    }

    @Test fun sessionsRoundTripAndRejectUnsafeIds() {
        val store = SessionStore(context)
        val session = ChatSession(cwd = context.filesDir.absolutePath)
        assertTrue(store.save(session))
        assertEquals(session.id, store.load(session.id)?.id)
        assertFalse(store.save(session.copy(id = "../escape")))
        store.delete(session.id)
    }
}

package dev.opencode.mobile.tools

import dev.opencode.mobile.shell.ShellResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitExecutorTest {
    @get:Rule val temp = TemporaryFolder()

    private fun shell(cwd: String, command: String): ShellResult {
        val process = ProcessBuilder("/bin/sh", "-c", command).directory(File(cwd)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellResult(process.waitFor(), output)
    }

    @Test fun commitsOnlyReviewedIndexAndRejectsChangesAfterApproval() = runBlocking {
        val root = temp.newFolder("repo")
        fun git(command: String): String {
            val result = shell(root.path, "git $command")
            assertTrue(result.output, result.ok)
            return result.output
        }
        git("init -q")
        git("config user.email test@example.test")
        git("config user.name Tester")
        File(root, "file.txt").writeText("first\n")
        git("add file.txt")
        val executor = GitExecutor { cwd, command -> shell(cwd, command) }
        val ctx = ToolContext(root.path, useRoot = false, sandboxed = true)
        val preview = executor.diff(root.path, "git", true)
        assertTrue(preview.output, preview.ok)
        val args = JSONObject().put("message", "first '; touch hacked; '").put("_approved_staged_hash", preview.hash)
        assertFalse(executor.execute("git_commit", args, ctx).ok)
        val committed = executor.execute("git_commit", args, ctx.copy(gitCommitApproved = true))
        assertTrue(committed.output, committed.ok)
        assertFalse(File(root, "hacked").exists())
        assertTrue(git("log -1 --pretty=%s").contains("touch hacked"))
        File(root, "file.txt").writeText("second\n")
        git("add file.txt")
        val stale = executor.diff(root.path, "git", true)
        File(root, "file.txt").writeText("third\n")
        git("add file.txt")
        val rejected = executor.execute("git_commit", JSONObject().put("message", "stale").put("_approved_staged_hash", stale.hash),
            ctx.copy(gitCommitApproved = true))
        assertFalse(rejected.ok)
        assertTrue(rejected.output.contains("изменились"))
        assertTrue(git("diff --cached").contains("third"))
    }

    @Test fun planAndNestedWorkspaceRejectGitExecution() = runBlocking {
        val root = temp.newFolder("repo2")
        shell(root.path, "git init -q")
        val nested = File(root, "nested").apply { mkdirs() }
        var calls = 0
        val executor = GitExecutor { cwd, command -> calls++; shell(cwd, command) }
        val ctx = ToolContext(root.path, useRoot = false, sandboxed = true, planMode = true)
        assertFalse(executor.execute("git_diff", JSONObject(), ctx).ok)
        assertEquals(0, calls)
        assertFalse(executor.diff(nested.path, "git", false).ok)
    }
}

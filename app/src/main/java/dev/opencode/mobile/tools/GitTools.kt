package dev.opencode.mobile.tools

import dev.opencode.mobile.shell.Shell
import dev.opencode.mobile.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Runs a user-supplied Git executable as the app UID; never as root. */
object GitTools {
    data class Preview(val ok: Boolean, val output: String, val hash: String? = null)
    private val executor = GitExecutor { cwd, command ->
        runInterruptible(Dispatchers.IO) { Shell.run(command, cwd = cwd, useRoot = false, timeoutSec = 30) }
    }
    suspend fun diff(cwd: String, executable: String, staged: Boolean): Preview = executor.diff(cwd, executable, staged)
    suspend fun execute(name: String, args: JSONObject, ctx: ToolContext): ToolOutcome = executor.execute(name, args, ctx)
}

/** Injectable process boundary permits real host-Git tests without relying on Git in the Android image. */
internal class GitExecutor(private val runner: suspend (String, String) -> ShellResult) {

    private fun base(executable: String): String {
        require(executable.isNotBlank() && !executable.contains('\u0000') && !executable.contains('\n')) { "Укажите путь к Git в настройках" }
        return "GIT_TERMINAL_PROMPT=0 GIT_OPTIONAL_LOCKS=0 GIT_PAGER=cat " + Shell.quote(executable) +
            " -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.pager=cat -c commit.gpgsign=false"
    }

    private suspend fun run(cwd: String, executable: String, args: String): ShellResult =
        runner(cwd, "${base(executable)} $args")

    private suspend fun rootError(cwd: String, executable: String): String? {
        if (!File(cwd).isDirectory) return "Рабочая папка не существует"
        val root = run(cwd, executable, "rev-parse --show-toplevel")
        if (!root.ok) return "Git недоступен или это не репозиторий. Установите Git и укажите путь к нему в настройках.\n${root.output.take(1000)}"
        val actual = runCatching { File(root.output.trim()).canonicalFile }.getOrNull()
        if (actual != File(cwd).canonicalFile) return "Выберите корень Git-репозитория как рабочую папку."
        return null
    }

    suspend fun diff(cwd: String, executable: String, staged: Boolean): GitTools.Preview {
        rootError(cwd, executable)?.let { return GitTools.Preview(false, it) }
        val flags = "${if (staged) "--cached " else ""}--no-ext-diff --no-textconv --no-color"
        val raw = run(cwd, executable, "diff $flags --raw --no-abbrev --")
        if (!raw.ok || raw.truncated) return GitTools.Preview(false, "Не удалось безопасно получить список изменений Git")
        val result = run(cwd, executable, "diff $flags --")
        if (!result.ok) return GitTools.Preview(false, "Не удалось получить diff: ${result.output.take(2000)}")
        if (result.truncated || (staged && raw.output.length + result.output.length > 16_000))
            return GitTools.Preview(false, "Diff слишком велик для безопасного предпросмотра. Используйте терминал.")
        val text = if (staged && raw.output.isNotBlank()) "Изменения в индексе (OID):\n${raw.output}\nDiff:\n${result.output}" else result.output
        val hash = MessageDigest.getInstance("SHA-256").digest((raw.output + "\n" + result.output).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return GitTools.Preview(true, text, hash)
    }

    suspend fun execute(name: String, args: JSONObject, ctx: ToolContext): ToolOutcome {
        if (ctx.planMode) return ToolOutcome(false, "Git-процессы заблокированы в режиме плана", "Заблокировано")
        val executable = ctx.gitExecutable.trim().ifBlank { "git" }
        if (name == "git_diff") {
            val result = diff(ctx.cwd, executable, args.optBoolean("staged", false))
            return ToolOutcome(result.ok, result.output.ifBlank { "Нет изменений" }, if (result.ok) "Git diff" else "Ошибка Git")
        }
        if (name != "git_commit") return ToolOutcome(false, "Неизвестный Git-инструмент", "Ошибка")
        val message = args.optString("message").trim()
        if (message.isBlank() || message.length > 1000 || message.contains('\u0000')) {
            return ToolOutcome(false, "Сообщение коммита должно содержать от 1 до 1000 символов", "Ошибка Git")
        }
        val expected = args.optString("_approved_staged_hash")
        if (!ctx.gitCommitApproved || expected.isBlank()) return ToolOutcome(false, "Коммит требует отдельного подтверждения с предпросмотром", "Отклонено")
        val preview = diff(ctx.cwd, executable, staged = true)
        if (!preview.ok || preview.hash != expected || preview.output.isBlank()) {
            return ToolOutcome(false, "Подготовленные изменения изменились, пусты или недоступны. Коммит не создан; проверьте diff и подтвердите заново.", "Отклонено")
        }
        val result = run(ctx.cwd, executable, "commit --no-verify -m ${Shell.quote(message)}")
        return ToolOutcome(result.ok, result.output.ifBlank { if (result.ok) "Коммит создан" else "Не удалось создать коммит" },
            if (result.ok) "Коммит создан" else "Ошибка Git")
    }
}

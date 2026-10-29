package dev.opencode.mobile.tools

import android.system.Os
import android.util.Base64
import dev.opencode.mobile.data.FileUndo
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Best-effort rollback of direct app-UID file writes, with content conflict checks. */
object FileUndoManager {
    private const val LIMIT = 1024 * 1024

    data class Before internal constructor(
        val path: String, val existed: Boolean, val bytes: ByteArray, val mode: Int?
    )

    fun prepare(cwd: String, path: String, useRoot: Boolean): Before {
        require(!useRoot) { "Для root-правок откат не поддерживается" }
        val file = checkedFile(cwd, path)
        if (!file.exists()) return Before(file.path, false, byteArrayOf(), null)
        require(file.isFile && file.canRead() && file.canWrite()) { "Файл нельзя сохранить для отката" }
        val bytes = readBounded(file)
        val mode = runCatching { Os.stat(file.path).st_mode and 0x1FF }.getOrNull()
        return Before(file.path, true, bytes, mode)
    }

    fun finish(cwd: String, before: Before): FileUndo {
        val file = checkedFile(cwd, before.path)
        require(file.isFile && file.canRead()) { "Не удалось проверить изменённый файл" }
        return FileUndo(
            path = before.path, existedBefore = before.existed,
            beforeBase64 = Base64.encodeToString(before.bytes, Base64.NO_WRAP),
            beforeHash = hash(before.bytes), afterHash = hash(readBounded(file)), mode = before.mode
        )
    }

    fun undo(cwd: String, record: FileUndo) {
        require(!record.undone) { "Эта правка уже отменена" }
        val file = checkedFile(cwd, record.path)
        require(file.isFile && file.canRead() && hash(readBounded(file)) == record.afterHash) {
            "Файл изменился после действия или недоступен. Откат остановлен."
        }
        if (!record.existedBefore) {
            // Never recursively delete, and never delete a directory or symlink.
            require(checkedFile(cwd, record.path).isFile && hash(readBounded(file)) == record.afterHash) {
                "Файл изменился после действия. Откат остановлен."
            }
            check(file.delete()) { "Не удалось удалить созданный файл" }
            return
        }
        val previous = runCatching { Base64.decode(record.beforeBase64, Base64.NO_WRAP) }
            .getOrElse { throw IllegalStateException("Резервная копия повреждена") }
        require(previous.size <= LIMIT && hash(previous) == record.beforeHash) { "Резервная копия повреждена" }
        val parent = file.parentFile ?: error("Нет родительской папки")
        val temp = File.createTempFile(".opencode-undo-", ".tmp", parent)
        try {
            temp.writeBytes(previous)
            record.mode?.let { Os.chmod(temp.path, it) }
            require(checkedFile(cwd, record.path).isFile && hash(readBounded(file)) == record.afterHash) {
                "Файл изменился после действия. Откат остановлен."
            }
            check(temp.renameTo(file)) { "Не удалось восстановить файл" }
        } finally { temp.delete() }
    }

    private fun checkedFile(cwd: String, path: String): File {
        require(path.isNotBlank()) { "Пустой путь" }
        val root = File(cwd).canonicalFile
        val input = File(path)
        val candidate = if (input.isAbsolute) input.absoluteFile else File(root, path).absoluteFile
        val canonical = candidate.canonicalFile
        val prefix = root.path.trimEnd(File.separatorChar) + File.separator
        require(canonical.path.startsWith(prefix) && canonical != root) { "Откат только внутри рабочей папки" }
        // Reject links at any component, including links that point back inside the workspace.
        require(normalized(candidate.path) == canonical.path) { "Откат через симлинк не поддерживается" }
        return canonical
    }

    private fun normalized(path: String): String {
        val parts = mutableListOf<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun readBounded(file: File): ByteArray {
        require(file.length() <= LIMIT) { "Файл больше 1 МБ: откат недоступен" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= LIMIT) { "Файл больше 1 МБ: откат недоступен" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

package dev.opencode.mobile.agent

import dev.opencode.mobile.data.ChatAttachment
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Explicitly mentioned files are snapshotted; paths and rules never escape the workspace. */
object ProjectContext {
    private const val MAX_FILE_BYTES = 64 * 1024
    private const val MAX_TOTAL_BYTES = 192 * 1024
    private const val MAX_FILES = 5
    private val mention = Regex("(?<!\\S)@(\"(?:\\\\.|[^\"\\\\])*\"|[^\\s@]+)")
    private val ignored = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", ".next", "vendor")

    fun mentions(text: String): List<String> = mention.findAll(text).map { match ->
        val token = match.groupValues[1]
        if (token.startsWith('"')) {
            runCatching { JSONArray("[$token]").getString(0) }
                .getOrElse { throw IllegalArgumentException("Некорректное упоминание файла: $token") }
        } else token
    }.distinct().toList()

    fun attachments(cwd: String, text: String): List<ChatAttachment> {
        val paths = mentions(text)
        require(paths.size <= MAX_FILES) { "Можно прикрепить не более $MAX_FILES файлов за сообщение" }
        var total = 0
        return paths.map { path ->
            val file = insideWorkspace(cwd, path)
            val bytes = readBounded(file, MAX_FILE_BYTES)
            total += bytes.size
            require(total <= MAX_TOTAL_BYTES) { "Прикреплённые файлы вместе больше 192 КБ" }
            ChatAttachment(path = path, content = decode(bytes, path))
        }
    }

    /** AGENTS.md is optional, but a present, unreadable or oversized rules file is not silently ignored. */
    fun rules(cwd: String): String? {
        val root = File(cwd).canonicalFile
        val raw = File(root, "AGENTS.md")
        if (!raw.exists()) return null
        val file = insideWorkspace(cwd, "AGENTS.md")
        return decode(readBounded(file, MAX_FILE_BYTES), "AGENTS.md")
    }

    fun findFiles(cwd: String, query: String): List<String> {
        val root = File(cwd).canonicalFile
        if (!root.isDirectory) return emptyList()
        val results = mutableListOf<String>()
        var visited = 0
        root.walkTopDown().onEnter { dir ->
            visited < 5000 && (dir == root || dir.name !in ignored) && safeCanonical(root, dir) && dir.canonicalFile == dir.absoluteFile
        }.take(5000).forEach { file ->
            visited++
            if (results.size >= 100) return@forEach
            if (file.isFile && safeCanonical(root, file) && file.canonicalFile == file.absoluteFile) {
                val path = file.relativeTo(root).path.replace(File.separatorChar, '/')
                if (path.contains(query.trim(), ignoreCase = true)) results.add(path)
            }
        }
        return results.sorted().take(50)
    }

    private fun safeCanonical(root: File, file: File): Boolean = runCatching {
        val canonical = file.canonicalFile
        canonical.path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator) || canonical == root
    }.getOrDefault(false)

    private fun insideWorkspace(cwd: String, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Нужен относительный путь внутри проекта: $path" }
        val root = File(cwd).canonicalFile
        val file = File(root, path).canonicalFile
        require(file != root && safeCanonical(root, file)) { "Файл за пределами проекта: $path" }
        require(file.isFile && file.canRead()) { "Не удалось прочитать файл: $path" }
        return file
    }

    private fun readBounded(file: File, max: Int): ByteArray {
        require(file.length() <= max) { "Файл больше ${max / 1024} КБ: ${file.name}" }
        val bytes = file.inputStream().use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                require(output.size() + read <= max) { "Файл больше ${max / 1024} КБ: ${file.name}" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        require(bytes.none { it == 0.toByte() }) { "Можно прикреплять только текстовые UTF-8 файлы: ${file.name}" }
        return bytes
    }

    private fun decode(bytes: ByteArray, path: String): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw IllegalArgumentException("Файл не является текстом UTF-8: $path")
    }
}

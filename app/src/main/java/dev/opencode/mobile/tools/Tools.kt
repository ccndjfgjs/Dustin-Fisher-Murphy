package dev.opencode.mobile.tools

import dev.opencode.mobile.shell.Shell
import dev.opencode.mobile.shell.ShellResult
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject
) {
    fun toOpenAiJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters)
        )

    fun toAnthropicJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("input_schema", parameters)
}

data class ToolOutcome(
    val ok: Boolean,
    val output: String,
    val title: String,
    val filesTouched: List<String> = emptyList()
)

data class ToolContext(
    val cwd: String,
    val useRoot: Boolean,
    val sandboxed: Boolean,
    val onChunk: (String) -> Unit = {},
    val onTodo: (List<dev.opencode.mobile.data.TodoItem>) -> Unit = {}
)

object Tools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun schema(props: Map<String, JSONObject>, required: List<String>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject()
            .put("type", "object")
            .put("properties", p)
            .put("required", JSONArray(required))
    }

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "boolean").put("description", desc)

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            name = "read_file",
            description = "Прочитать текстовый файл. Возвращает содержимое с номерами строк. " +
                "offset/limit — диапазон строк (offset с 1).",
            parameters = schema(
                mapOf(
                    "path" to str("Путь к файлу (абсолютный или относительно рабочей папки)"),
                    "offset" to int("Первая строка, начиная с 1"),
                    "limit" to int("Сколько строк прочитать (по умолчанию 400, максимум 2000)")
                ),
                listOf("path")
            )
        ),
        ToolSpec(
            name = "write_file",
            description = "Создать файл или полностью перезаписать его содержимым. Родительские папки создаются автоматически.",
            parameters = schema(
                mapOf(
                    "path" to str("Путь к файлу"),
                    "content" to str("Новое содержимое файла целиком")
                ),
                listOf("path", "content")
            )
        ),
        ToolSpec(
            name = "edit_file",
            description = "Точечно заменить фрагмент в файле. old_string должен встречаться ровно один раз, " +
                "если replace_all=false. Используй достаточно контекста, чтобы совпадение было уникальным.",
            parameters = schema(
                mapOf(
                    "path" to str("Путь к файлу"),
                    "old_string" to str("Точный текст, который нужно заменить"),
                    "new_string" to str("Текст замены"),
                    "replace_all" to bool("Заменить все вхождения")
                ),
                listOf("path", "old_string", "new_string")
            )
        ),
        ToolSpec(
            name = "list_dir",
            description = "Показать содержимое папки (файлы и подпапки).",
            parameters = schema(
                mapOf(
                    "path" to str("Путь к папке, по умолчанию рабочая папка"),
                    "depth" to int("Глубина рекурсии, 1..3 (по умолчанию 1)")
                ),
                emptyList()
            )
        ),
        ToolSpec(
            name = "glob",
            description = "Найти файлы по glob-шаблону, например `**/*.kt` или `src/**/build.gradle.kts`.",
            parameters = schema(
                mapOf(
                    "pattern" to str("Glob-шаблон"),
                    "path" to str("Базовая папка, по умолчанию рабочая")
                ),
                listOf("pattern")
            )
        ),
        ToolSpec(
            name = "grep",
            description = "Поиск по содержимому файлов регулярным выражением (как ripgrep).",
            parameters = schema(
                mapOf(
                    "pattern" to str("Регулярное выражение"),
                    "path" to str("Где искать, по умолчанию рабочая папка"),
                    "include" to str("Фильтр имён файлов, например *.kt"),
                    "max_results" to int("Максимум совпадений (по умолчанию 100)")
                ),
                listOf("pattern")
            )
        ),
        ToolSpec(
            name = "run_command",
            description = "Выполнить shell-команду в рабочей папке. При включённом режиме root — через su. " +
                "Подходит для git, gradle, npm, apt, magisk и любых системных утилит.",
            parameters = schema(
                mapOf(
                    "command" to str("Команда целиком, как в терминале"),
                    "cwd" to str("Рабочая папка (по умолчанию рабочая папка проекта)"),
                    "timeout" to int("Таймаут в секундах (по умолчанию 120, максимум 1800)")
                ),
                listOf("command")
            )
        ),
        ToolSpec(
            name = "delete_path",
            description = "Удалить файл или папку.",
            parameters = schema(
                mapOf(
                    "path" to str("Что удалить"),
                    "recursive" to bool("Удалить папку со всем содержимым")
                ),
                listOf("path")
            )
        ),
        ToolSpec(
            name = "move_path",
            description = "Переместить или переименовать файл/папку.",
            parameters = schema(
                mapOf(
                    "from" to str("Откуда"),
                    "to" to str("Куда")
                ),
                listOf("from", "to")
            )
        ),
        ToolSpec(
            name = "todo_write",
            description = "Обновить план работы (todo-лист). Используй, чтобы вести многошаговые задачи.",
            parameters = JSONObject()
                .put("type", "object")
                .put(
                    "properties", JSONObject().put(
                        "todos", JSONObject()
                            .put("type", "array")
                            .put(
                                "items", JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties", JSONObject()
                                            .put("content", str("Что сделать"))
                                            .put("status", str("pending | in_progress | completed"))
                                    )
                                    .put("required", JSONArray(listOf("content", "status")))
                            )
                    )
                )
                .put("required", JSONArray(listOf("todos")))
        ),
        ToolSpec(
            name = "web_fetch",
            description = "Скачать страницу/документ по URL и вернуть текст (документация, API-справки).",
            parameters = schema(
                mapOf(
                    "url" to str("URL"),
                    "max_chars" to int("Сколько символов вернуть, по умолчанию 20000")
                ),
                listOf("url")
            )
        ),
        ToolSpec(
            name = "device_info",
            description = "Информация об устройстве: Android, ABI, root, SELinux, свободное место.",
            parameters = schema(emptyMap(), emptyList())
        )
    )

    val readOnly: Set<String> = setOf("read_file", "list_dir", "glob", "grep", "web_fetch", "device_info")

    fun resolve(cwd: String, path: String): File {
        val p = File(path)
        return if (p.isAbsolute) p else File(cwd, path)
    }

    suspend fun execute(name: String, args: JSONObject, ctx: ToolContext): ToolOutcome = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "read_file" -> readFile(args, ctx)
                "write_file" -> writeFile(args, ctx)
                "edit_file" -> editFile(args, ctx)
                "list_dir" -> listDir(args, ctx)
                "glob" -> glob(args, ctx)
                "grep" -> grep(args, ctx)
                "run_command" -> runCommand(args, ctx)
                "delete_path" -> deletePath(args, ctx)
                "move_path" -> movePath(args, ctx)
                "todo_write" -> todoWrite(args, ctx)
                "web_fetch" -> webFetch(args)
                "device_info" -> deviceInfo(ctx)
                else -> ToolOutcome(false, "Неизвестный инструмент: $name", "Ошибка")
            }
        } catch (t: Throwable) {
            ToolOutcome(false, "Ошибка инструмента $name: ${t.message ?: t.javaClass.simpleName}", "Ошибка")
        }
    }

    private fun guard(file: File, ctx: ToolContext) {
        if (!ctx.sandboxed) return
        val path = file.canonicalPath
        val allowed = listOf(ctx.cwd, "/sdcard", "/storage/emulated/0", "/data/local/tmp")
        if (allowed.none { path == it || path.startsWith(it.trimEnd('/') + "/") }) {
            throw IllegalStateException("В режиме без root доступ ограничен рабочей папкой и /sdcard")
        }
    }

    private fun numbered(content: String, offset: Int, limit: Int): String {
        val lines = content.split('\n')
        val from = (offset - 1).coerceAtLeast(0)
        if (from >= lines.size) return "[файл содержит ${lines.size} строк]"
        val to = (from + limit).coerceAtMost(lines.size)
        val sb = StringBuilder()
        for (i in from until to) {
            sb.append((i + 1).toString().padStart(5)).append("  ").append(lines[i])
            if (i != to - 1) sb.append('\n')
        }
        if (to < lines.size) sb.append("\n… (ещё ${lines.size - to} строк)")
        return sb.toString()
    }

    private fun readFile(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val file = resolve(ctx.cwd, args.getString("path"))
        guard(file, ctx)
        val offset = args.optInt("offset", 1).coerceAtLeast(1)
        val limit = args.optInt("limit", 400).coerceIn(1, 2000)
        val text = Shell.readText(file.absolutePath)
            ?: return ToolOutcome(false, "Не удалось прочитать ${file.absolutePath} (нет файла или прав)", "Чтение не удалось")
        return ToolOutcome(
            true,
            "Файл: ${file.absolutePath}\n" + numbered(text, offset, limit),
            "Прочитано ${file.name}"
        )
    }

    private fun writeFile(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val file = resolve(ctx.cwd, args.getString("path"))
        guard(file, ctx)
        val content = args.optString("content", "")
        file.parentFile?.mkdirs()
        val ok = Shell.writeText(file.absolutePath, content)
        return if (ok) {
            ToolOutcome(true, "Записано ${content.length} символов в ${file.absolutePath}", "Изменён ${file.name}")
        } else {
            ToolOutcome(false, "Не удалось записать ${file.absolutePath}", "Ошибка записи")
        }
    }

    private fun editFile(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val file = resolve(ctx.cwd, args.getString("path"))
        guard(file, ctx)
        val oldStr = args.getString("old_string")
        val newStr = args.optString("new_string", "")
        val replaceAll = args.optBoolean("replace_all", false)
        val current = Shell.readText(file.absolutePath)
            ?: return ToolOutcome(false, "Не удалось прочитать ${file.absolutePath}", "Ошибка")
        val count = countOccurrences(current, oldStr)
        if (count == 0) {
            return ToolOutcome(false, "Фрагмент не найден в ${file.absolutePath}. Проверь текст (пробелы/отступы).", "Не найдено")
        }
        if (count > 1 && !replaceAll) {
            return ToolOutcome(
                false,
                "Фрагмент встречается $count раз. Добавь контекста или используй replace_all=true.",
                "Неоднозначно"
            )
        }
        val updated = if (replaceAll) current.replace(oldStr, newStr) else current.replaceFirst(oldStr, newStr)
        val ok = Shell.writeText(file.absolutePath, updated)
        return if (ok) {
            ToolOutcome(true, "Заменено вхождений: ${if (replaceAll) count else 1} в ${file.absolutePath}", "Изменён ${file.name}")
        } else {
            ToolOutcome(false, "Не удалось записать ${file.absolutePath}", "Ошибка записи")
        }
    }

    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var idx = 0
        var count = 0
        while (true) {
            val found = text.indexOf(sub, idx)
            if (found < 0) break
            count++
            idx = found + sub.length
        }
        return count
    }

    private fun listDir(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val dir = resolve(ctx.cwd, args.optString("path", "."))
        guard(dir, ctx)
        if (!dir.exists()) return ToolOutcome(false, "Папка не найдена: ${dir.absolutePath}", "Ошибка")
        val depth = args.optInt("depth", 1).coerceIn(1, 3)
        val sb = StringBuilder()
        fun walk(f: File, level: Int, prefix: String) {
            val children = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            children.take(200).forEach { child ->
                val mark = if (child.isDirectory) "/" else ""
                val size = if (child.isDirectory) "" else "  ${humanSize(child.length())}"
                sb.append(prefix).append(child.name).append(mark).append(size).append('\n')
                if (child.isDirectory && level < depth && !child.name.startsWith(".")) {
                    walk(child, level + 1, "$prefix  ")
                }
            }
        }
        walk(dir, 1, "")
        if (sb.isEmpty()) sb.append("(пусто)")
        return ToolOutcome(true, "Содержимое ${dir.absolutePath}:\n$sb", "Просмотр ${dir.name}")
    }

    private fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    if (i + 2 < pattern.length && pattern[i + 2] == '/') {
                        sb.append("(?:.*/)?")
                        i += 3
                        continue
                    }
                    sb.append(".*")
                    i += 2
                    continue
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '.' || c == '(' || c == ')' || c == '+' || c == '|' || c == '^' || c == '$' || c == '{' || c == '}' || c == '[' || c == ']' || c == '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return Regex(sb.toString())
    }

    private val ignoredDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea", "dist", ".next", "vendor")

    private fun glob(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val base = resolve(ctx.cwd, args.optString("path", "."))
        guard(base, ctx)
        val pattern = args.getString("pattern")
        val regex = globToRegex(pattern)
        val results = mutableListOf<String>()
        base.walkTopDown()
            .onEnter { it.name !in ignoredDirs || it == base }
            .forEach { f ->
                if (results.size >= 300) return@forEach
                if (f.isFile && regex.matches(f.relativeToOrSelf(base).path.replace('\\', '/'))) {
                    results.add(f.absolutePath)
                }
            }
        return if (results.isEmpty()) {
            ToolOutcome(true, "Ничего не найдено по шаблону $pattern в ${base.absolutePath}", "Нет совпадений")
        } else {
            ToolOutcome(true, "Найдено ${results.size}:\n" + results.joinToString("\n"), "Найдено ${results.size}")
        }
    }

    private fun grep(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val base = resolve(ctx.cwd, args.optString("path", "."))
        guard(base, ctx)
        val pattern = args.getString("pattern")
        val include = args.optString("include", "")
        val max = args.optInt("max_results", 100).coerceIn(1, 500)
        val regex = runCatching { Regex(pattern) }.getOrElse {
            return ToolOutcome(false, "Некорректное регулярное выражение: ${it.message}", "Ошибка")
        }
        val includeRegex = if (include.isBlank()) null else globToRegex(include)
        val out = StringBuilder()
        var found = 0
        val files = if (base.isFile) listOf(base) else base.walkTopDown()
            .onEnter { it.name !in ignoredDirs || it == base }
            .filter { it.isFile && it.length() < 2_000_000 }
            .toList()
        outer@ for (f in files) {
            if (includeRegex != null && !includeRegex.matches(f.name)) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (text.contains('\u0000')) continue
            text.lineSequence().forEachIndexed { idx, line ->
                if (regex.containsMatchIn(line)) {
                    out.append(f.absolutePath).append(':').append(idx + 1).append(": ").append(line.trim()).append('\n')
                    found++
                    if (found >= max) return@outer
                }
            }
        }
        return if (found == 0) {
            ToolOutcome(true, "Совпадений не найдено", "Нет совпадений")
        } else {
            ToolOutcome(true, "Найдено $found совпадений:\n$out", "Найдено $found")
        }
    }

    private suspend fun runCommand(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val command = args.getString("command")
        val cwd = args.optString("cwd").ifBlank { ctx.cwd }
        val timeout = args.optInt("timeout", 120).coerceIn(5, 1800)
        val result: ShellResult = runInterruptible(Dispatchers.IO) {
            Shell.run(command, cwd = cwd, useRoot = ctx.useRoot, timeoutSec = timeout, onChunk = ctx.onChunk)
        }
        val header = buildString {
            append("$ ").append(command).append('\n')
            append("[exit=").append(result.exitCode)
            if (result.usedRoot) append(", root")
            if (result.truncated) append(", вывод обрезан")
            append("]\n")
        }
        val output = result.output.ifBlank { "(нет вывода)" }
        return ToolOutcome(
            ok = result.ok,
            output = header + output,
            title = if (result.ok) "Команда выполнена" else "Команда вернула ${result.exitCode}"
        )
    }

    private fun deletePath(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val file = resolve(ctx.cwd, args.getString("path"))
        guard(file, ctx)
        if (!file.exists() && !Shell.rootAvailable()) return ToolOutcome(false, "Не существует: ${file.absolutePath}", "Ошибка")
        val ok = Shell.delete(file.absolutePath, args.optBoolean("recursive", true))
        return ToolOutcome(ok, if (ok) "Удалено: ${file.absolutePath}" else "Не удалось удалить ${file.absolutePath}", if (ok) "Удалено" else "Ошибка")
    }

    private fun movePath(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val from = resolve(ctx.cwd, args.getString("from"))
        val to = resolve(ctx.cwd, args.getString("to"))
        guard(from, ctx)
        guard(to, ctx)
        val ok = runCatching {
            to.parentFile?.mkdirs()
            from.renameTo(to) || (from.copyRecursively(to, overwrite = true).let { from.deleteRecursively() })
        }.getOrDefault(false)
        return ToolOutcome(ok, if (ok) "Перемещено: $from -> $to" else "Не удалось переместить $from", if (ok) "Перемещено" else "Ошибка")
    }

    private fun todoWrite(args: JSONObject, ctx: ToolContext): ToolOutcome {
        val arr = args.optJSONArray("todos")
            ?: return ToolOutcome(false, "Нужен массив todos", "Ошибка")
        val items = mutableListOf<dev.opencode.mobile.data.TodoItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(dev.opencode.mobile.data.TodoItem(o.optString("content"), o.optString("status", "pending")))
        }
        ctx.onTodo(items)
        val text = items.joinToString("\n") { "[${it.status}] ${it.content}" }
        return ToolOutcome(true, "План обновлён:\n$text", "План (${items.size})")
    }

    private fun webFetch(args: JSONObject): ToolOutcome {
        val url = args.getString("url")
        val max = args.optInt("max_chars", 20000).coerceIn(1000, 100000)
        val request = Request.Builder().url(url).header("User-Agent", "OpenCodeMobile/0.1").build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return ToolOutcome(false, "HTTP ${resp.code}: ${body.take(500)}", "Ошибка HTTP")
            val text = stripHtml(body)
            val trimmed = if (text.length > max) text.take(max) + "\n… (обрезано)" else text
            return ToolOutcome(true, trimmed, "Загружено ${resp.code}")
        }
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace(Regex("[ \\t]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun deviceInfo(ctx: ToolContext): ToolOutcome {
        val root = Shell.rootStatus()
        val rt = Runtime.getRuntime()
        val stat = android.os.StatFs(ctx.cwd.takeIf { File(it).exists() } ?: "/sdcard")
        val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
        val text = buildString {
            append("Android: ").append(android.os.Build.VERSION.RELEASE)
                .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("Устройство: ").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n')
            append("ABI: ").append(android.os.Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
            append("Root: ").append(if (root.available) "да (${root.manager} ${root.version})" else "нет").append('\n')
            if (root.available) append("SELinux: ").append(root.selinux).append('\n')
            append("Режим инструментов: ").append(if (ctx.useRoot) "root" else "обычный shell").append('\n')
            append("Рабочая папка: ").append(ctx.cwd).append('\n')
            append("Свободно: ").append(String.format("%.1f ГБ", freeGb)).append('\n')
            append("RAM (heap): ").append((rt.maxMemory() / 1024 / 1024)).append(" МБ")
        }
        return ToolOutcome(true, text, "Информация об устройстве")
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

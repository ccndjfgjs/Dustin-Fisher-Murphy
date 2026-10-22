package dev.opencode.mobile.shell

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val usedRoot: Boolean = false,
    val truncated: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

data class RootStatus(
    val available: Boolean,
    val version: String = "",
    val manager: String = "",
    val selinux: String = ""
)

/**
 * Исполнитель команд. Умеет работать как от обычного uid приложения, так и через `su`
 * (Magisk / KernelSU / APatch). Это ключевой слой, который даёт приложению "десктопные"
 * возможности opencode: запуск сборок, git, пакетных менеджеров и правку системных файлов.
 */
object Shell {

    private const val MAX_OUTPUT = 400_000

    @Volatile private var rootCache: RootStatus? = null
    @Volatile private var rootCheckedAt = 0L
    @Volatile private var shizukuCache: Boolean? = null

    fun invalidate() {
        rootCache = null
        rootCheckedAt = 0L
        shizukuCache = null
    }

    fun rootStatus(force: Boolean = false): RootStatus {
        val now = System.currentTimeMillis()
        val cached = rootCache
        if (!force && cached != null && now - rootCheckedAt < 60_000) return cached

        val result = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val finished = p.waitFor(6, TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                RootStatus(false)
            } else if (!out.contains("uid=0")) {
                RootStatus(false)
            } else {
                val props = runQuiet("su", "-c", "su -v; getprop ro.build.version.release; getenforce")
                val lines = props.lines().filter { it.isNotBlank() }
                RootStatus(
                    available = true,
                    version = lines.firstOrNull().orEmpty(),
                    manager = when {
                        props.contains("MAGISK", true) -> "Magisk"
                        props.contains("KernelSU", true) -> "KernelSU"
                        props.contains("APatch", true) -> "APatch"
                        else -> "su"
                    },
                    selinux = lines.lastOrNull().orEmpty()
                )
            }
        } catch (t: Throwable) {
            RootStatus(false)
        }
        rootCache = result
        rootCheckedAt = now
        return result
    }

    fun rootAvailable(force: Boolean = false): Boolean = rootStatus(force).available

    /** Shizuku доступен, если в системе есть rish либо запущен сервис Shizuku. */
    fun shizukuAvailable(): Boolean {
        shizukuCache?.let { return it }
        val present = runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", "which rish || ls /data/local/tmp/rish 2>/dev/null").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(4, TimeUnit.SECONDS)
            text.isNotBlank()
        }.getOrDefault(false)
        shizukuCache = present
        return present
    }

    /**
     * Запускает команду и возвращает результат.
     * @param useRoot выполнять через `su -c`
     * @param onChunk потоковый колбэк (вызывается из фонового потока)
     */
    fun run(
        command: String,
        cwd: String? = null,
        useRoot: Boolean = false,
        timeoutSec: Int = 120,
        onChunk: ((String) -> Unit)? = null
    ): ShellResult {
        val asRoot = useRoot && rootAvailable()
        val workDir = cwd?.let { File(it) }?.takeIf { it.isDirectory }
        val process = try {
            val builder = if (asRoot) {
                ProcessBuilder("su", "-c", wrapWithCd(command, workDir))
            } else {
                ProcessBuilder("/system/bin/sh", "-c", command).apply {
                    if (workDir != null) directory(workDir)
                }
            }
            builder.redirectErrorStream(true)
            builder.start()
        } catch (t: Throwable) {
            return ShellResult(127, "Не удалось запустить процесс: ${t.message}", usedRoot = asRoot)
        }

        val sb = StringBuilder()
        val truncated = AtomicBoolean(false)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { br ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = br.read(buf)
                        if (n <= 0) break
                        val chunk = String(buf, 0, n)
                        synchronized(sb) {
                            if (sb.length < MAX_OUTPUT) sb.append(chunk) else truncated.set(true)
                        }
                        runCatching { onChunk?.invoke(chunk) }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var finished = false
        var timedOut = false
        while (true) {
            if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                finished = true
                break
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                process.destroyForcibly()
                break
            }
            if (Thread.currentThread().isInterrupted) {
                process.destroyForcibly()
                timedOut = true
                break
            }
        }
        reader.join(1500)
        val exit = runCatching { process.exitValue() }.getOrDefault(if (finished) 0 else 137)
        val text = synchronized(sb) { sb.toString() }
        return ShellResult(
            exitCode = exit,
            output = if (timedOut) "$text\n[таймаут ${timeoutSec}s]".trim() else text,
            timedOut = timedOut,
            usedRoot = asRoot,
            truncated = truncated.get()
        )
    }

    private fun wrapWithCd(command: String, workDir: File?): String {
        if (workDir == null) return command
        return "cd ${quote(workDir.absolutePath)} 2>/dev/null || cd /; $command"
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun runQuiet(vararg args: String): String = try {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(5, TimeUnit.SECONDS)
        out.trim()
    } catch (t: Throwable) {
        ""
    }

    /** Чтение файла с fallback на root, если обычный доступ запрещён. */
    fun readText(path: String): String? {
        val f = File(path)
        if (f.canRead() && f.isFile) {
            runCatching { return f.readText() }
        }
        if (rootAvailable()) {
            val r = run("cat ${quote(path)}", useRoot = true, timeoutSec = 30)
            if (r.ok) return r.output
        }
        return null
    }

    /** Запись файла с fallback на root (через временный файл + cp). */
    fun writeText(path: String, content: String): Boolean {
        val f = File(path)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            return true
        }
        if (!rootAvailable()) return false
        return runCatching {
            val tmp = File.createTempFile("ocm", ".tmp")
            tmp.writeText(content)
            val r = run(
                "mkdir -p ${quote(f.parentFile?.absolutePath ?: "/")} && cp ${quote(tmp.absolutePath)} ${quote(path)} && chmod 644 ${quote(path)}",
                useRoot = true,
                timeoutSec = 60
            )
            tmp.delete()
            r.ok
        }.getOrDefault(false)
    }

    fun delete(path: String, recursive: Boolean): Boolean {
        val f = File(path)
        runCatching {
            val ok = if (recursive) f.deleteRecursively() else f.delete()
            if (ok) return true
        }
        if (!rootAvailable()) return false
        val r = run("rm ${if (recursive) "-rf" else ""} ${quote(path)}", useRoot = true, timeoutSec = 60)
        return r.ok
    }
}

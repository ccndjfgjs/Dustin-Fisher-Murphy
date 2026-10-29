package dev.opencode.mobile.shell

import android.system.Os
import android.system.OsConstants
import java.io.File
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

object Shell {
    private const val MAX_OUTPUT = 400_000
    @Volatile private var rootCache: RootStatus? = null
    @Volatile private var rootCheckedAt = 0L
    @Volatile private var shizukuCache: Boolean? = null
    @Volatile private var temporaryDirectory: File? = null

    fun initialize(cacheDir: File) { temporaryDirectory = cacheDir }

    fun invalidate() {
        rootCache = null
        rootCheckedAt = 0L
        shizukuCache = null
    }

    fun rootStatus(force: Boolean = false): RootStatus {
        val now = System.currentTimeMillis()
        val cached = rootCache
        if (!force && cached != null && now - rootCheckedAt < 60_000) return cached

        // Read the pipe concurrently with waiting: su may wait indefinitely for a grant.
        val id = runProcess(listOf("su", "-c", "id"), timeoutSec = 6)
        val result = if (id.ok && id.output.contains("uid=0")) {
            val props = runProcess(
                listOf("su", "-c", "su -v; getprop ro.build.version.release; getenforce"),
                timeoutSec = 5
            ).output
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
        } else RootStatus(false)
        rootCache = result
        rootCheckedAt = now
        return result
    }

    fun rootAvailable(force: Boolean = false): Boolean = rootStatus(force).available

    fun shizukuAvailable(): Boolean {
        shizukuCache?.let { return it }
        val result = runProcess(
            listOf("/system/bin/sh", "-c", "which rish || ls /data/local/tmp/rish 2>/dev/null"),
            timeoutSec = 4
        )
        return (result.ok && result.output.isNotBlank()).also { shizukuCache = it }
    }

    /** Runs a non-interactive command. Output callbacks run on a background thread. */
    fun run(
        command: String,
        cwd: String? = null,
        useRoot: Boolean = false,
        timeoutSec: Int = 120,
        onChunk: ((String) -> Unit)? = null
    ): ShellResult {
        val asRoot = useRoot && rootAvailable()
        val workDir = cwd?.let(::File)?.takeIf { it.isDirectory }
        val commandLine = if (asRoot) {
            listOf("su", "-c", wrapWithCd(command, workDir))
        } else {
            listOf("/system/bin/sh", "-c", command)
        }
        return runProcess(commandLine, if (asRoot) null else workDir, timeoutSec, onChunk)
            .copy(usedRoot = asRoot)
    }

    private fun runProcess(
        command: List<String>,
        workDir: File? = null,
        timeoutSec: Int,
        onChunk: ((String) -> Unit)? = null
    ): ShellResult {
        val process = try {
            ProcessBuilder(command).apply {
                if (workDir != null) directory(workDir)
                redirectErrorStream(true)
            }.start()
        } catch (t: Exception) {
            return ShellResult(127, "Не удалось запустить процесс: ${t.message}")
        }
        // The terminal is not interactive: signal EOF rather than hanging on input.
        runCatching { process.outputStream.close() }
        val output = StringBuilder()
        val truncated = AtomicBoolean(false)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val kept = synchronized(output) {
                            val remaining = (MAX_OUTPUT - output.length).coerceAtLeast(0)
                            val length = minOf(count, remaining)
                            if (length < count) truncated.set(true)
                            if (length > 0) String(buffer, 0, length).also { output.append(it) } else ""
                        }
                        if (kept.isNotEmpty()) runCatching { onChunk?.invoke(kept) }
                    }
                }
            } catch (_: Exception) {
                // Closing the pipe after a timeout/interruption is expected.
            }
        }.apply { isDaemon = true; start() }

        var finished = false
        var timedOut = false
        val deadline = System.nanoTime() + timeoutSec.coerceIn(1, 1800) * 1_000_000_000L
        try {
            // Process.waitFor(timeout, unit) was added only in API 26. Polling keeps
            // the command runner compatible with Android 7 (API 24/25).
            while (true) {
                try {
                    process.exitValue()
                    finished = true
                    break
                } catch (_: IllegalThreadStateException) {
                    if (System.nanoTime() >= deadline) {
                        timedOut = true
                        process.destroy()
                        break
                    }
                    Thread.sleep(50)
                }
            }
            reader.join(1_000)
        } catch (interrupted: InterruptedException) {
            process.destroy()
            runCatching { process.inputStream.close() }
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            if (!finished) process.destroy()
            if (reader.isAlive) runCatching { process.inputStream.close() }
        }
        val exit = runCatching { process.exitValue() }.getOrDefault(if (finished) 0 else 137)
        val text = synchronized(output) { output.toString() }
        return ShellResult(
            exitCode = exit,
            output = if (timedOut) "$text\n[таймаут ${timeoutSec}s]".trim() else text,
            timedOut = timedOut,
            truncated = truncated.get()
        )
    }

    private fun wrapWithCd(command: String, workDir: File?): String =
        if (workDir == null) command else "cd ${quote(workDir.absolutePath)} && $command"

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Root fallback is opt-in; a discovered su must never override the user's setting. */
    fun readText(path: String, allowRoot: Boolean = false): String? {
        val file = File(path)
        if (file.canRead() && file.isFile) runCatching { return file.readText() }
        if (allowRoot && rootAvailable()) {
            val result = run("cat ${quote(path)}", useRoot = true, timeoutSec = 30)
            if (result.ok && !result.truncated) return result.output
        }
        return null
    }

    fun writeText(path: String, content: String, allowRoot: Boolean = false): Boolean {
        val file = File(path)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            return true
        }
        if (!allowRoot || !rootAvailable()) return false
        var tmp: File? = null
        return try {
            tmp = File.createTempFile("ocm", ".tmp", temporaryDirectory ?: return false)
            tmp.writeText(content)
            run(
                "mkdir -p ${quote(file.parentFile?.absolutePath ?: "/")} && cp ${quote(tmp.absolutePath)} ${quote(path)}",
                useRoot = true,
                timeoutSec = 60
            ).ok
        } catch (_: Exception) {
            false
        } finally {
            tmp?.delete()
        }
    }

    /** Do not descend through symbolic links when recursively deleting a directory. */
    private fun deleteLocally(file: File, recursive: Boolean): Boolean {
        if (recursive && file.isDirectory && !isSymlink(file)) {
            val children = file.listFiles() ?: return false
            if (children.any { !deleteLocally(it, true) }) return false
        }
        return file.delete()
    }

    fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (_: Exception) {
        false
    }

    fun delete(path: String, recursive: Boolean, allowRoot: Boolean = false): Boolean {
        val file = File(path)
        if (runCatching { deleteLocally(file, recursive) }.getOrDefault(false)) return true
        if (!allowRoot || !rootAvailable()) return false
        return run("rm ${if (recursive) "-rf" else ""} -- ${quote(path)}", useRoot = true, timeoutSec = 60).ok
    }
}

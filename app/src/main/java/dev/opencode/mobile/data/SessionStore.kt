package dev.opencode.mobile.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** One atomically replaced JSON file per session. */
class SessionStore(context: Context) {
    private val dir: File = File(context.filesDir, "sessions").apply { mkdirs() }
    private val lock = Any()

    private fun file(id: String): File? =
        if (id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) File(dir, "$id.json") else null

    fun list(): List<SessionSummary> = synchronized(lock) {
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching {
                    val o = JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8))
                    val id = o.optString("id", f.nameWithoutExtension)
                    if (file(id) == null) return@runCatching null
                    SessionSummary(
                        id = id,
                        title = o.optString("title", "Сессия"),
                        cwd = o.optString("cwd", "/sdcard"),
                        updatedAt = o.optLong("updatedAt", f.lastModified())
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /** Search titles, workspaces and stored message text locally; never sends queries to a server. */
    fun search(query: String): List<SessionSummary> {
        val needle = query.trim()
        val summaries = list()
        if (needle.isEmpty()) return summaries
        return summaries.filter { summary ->
            summary.title.contains(needle, ignoreCase = true) || summary.cwd.contains(needle, ignoreCase = true) ||
                load(summary.id)?.messages?.any { message ->
                    message.content.contains(needle, ignoreCase = true) ||
                        message.reasoning.contains(needle, ignoreCase = true) ||
                        message.attachments.any { it.path.contains(needle, true) || it.content.contains(needle, true) }
                } == true
        }
    }

    fun load(id: String): ChatSession? = synchronized(lock) {
        runCatching {
            val f = file(id) ?: return@runCatching null
            if (!f.exists() && !File(f.path + ".bak").exists()) return@runCatching null
            val session = ChatSession.fromJson(JSONObject(String(AtomicFile(f).readFully(), Charsets.UTF_8)))
            if (session.id == id) session else null
        }.getOrNull()
    }

    fun save(session: ChatSession): Boolean = synchronized(lock) {
        val f = file(session.id) ?: return@synchronized false
        val atomic = AtomicFile(f)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(session.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: Exception) {
            if (stream != null) atomic.failWrite(stream)
            false
        }
    }

    fun delete(id: String) = synchronized(lock) {
        file(id)?.let { AtomicFile(it).delete() }
    }

    fun clearAll() = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
    }
}

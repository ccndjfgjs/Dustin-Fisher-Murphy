package dev.opencode.mobile.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Хранилище сессий: по одному JSON-файлу на сессию в filesDir/sessions. */
class SessionStore(context: Context) {

    private val dir: File = File(context.filesDir, "sessions").apply { mkdirs() }

    fun list(): List<SessionSummary> = dir.listFiles { f -> f.name.endsWith(".json") }
        ?.mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                SessionSummary(
                    id = o.optString("id", f.nameWithoutExtension),
                    title = o.optString("title", "Сессия"),
                    cwd = o.optString("cwd", "/sdcard"),
                    updatedAt = o.optLong("updatedAt", f.lastModified())
                )
            }.getOrNull()
        }
        ?.sortedByDescending { it.updatedAt }
        ?: emptyList()

    fun load(id: String): ChatSession? = runCatching {
        val f = File(dir, "$id.json")
        if (!f.exists()) null else ChatSession.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    fun save(session: ChatSession) {
        runCatching {
            File(dir, "${session.id}.json").writeText(session.toJson().toString())
        }
    }

    fun delete(id: String) {
        runCatching { File(dir, "$id.json").delete() }
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

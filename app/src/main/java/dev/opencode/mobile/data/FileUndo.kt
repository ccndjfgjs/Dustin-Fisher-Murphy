package dev.opencode.mobile.data

import org.json.JSONObject

/** Private local backup of one direct write/edit; not sent to the model. */
data class FileUndo(
    val path: String,
    val existedBefore: Boolean,
    val beforeBase64: String,
    val beforeHash: String,
    val afterHash: String,
    val mode: Int? = null,
    val undone: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("existedBefore", existedBefore)
        .put("beforeBase64", beforeBase64).put("beforeHash", beforeHash).put("afterHash", afterHash)
        .put("undone", undone).apply { mode?.let { put("mode", it) } }

    companion object {
        fun fromJson(o: JSONObject): FileUndo = FileUndo(
            path = o.optString("path"), existedBefore = o.optBoolean("existedBefore"),
            beforeBase64 = o.optString("beforeBase64"), beforeHash = o.optString("beforeHash"),
            afterHash = o.optString("afterHash"),
            mode = if (o.has("mode") && !o.isNull("mode")) o.optInt("mode") else null,
            undone = o.optBoolean("undone")
        )
    }
}

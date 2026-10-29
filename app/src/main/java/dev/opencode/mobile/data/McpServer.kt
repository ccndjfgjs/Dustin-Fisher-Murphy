package dev.opencode.mobile.data

import org.json.JSONObject
import java.util.UUID

/** Opt-in Streamable HTTP endpoint. The bearer token is stored with encrypted settings. */
data class McpServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val bearerToken: String = "",
    val enabled: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
        .put("bearerToken", bearerToken).put("enabled", enabled)
    companion object {
        fun fromJson(o: JSONObject): McpServer = McpServer(
            id = o.optString("id", UUID.randomUUID().toString()), name = o.optString("name"),
            url = o.optString("url"), bearerToken = o.optString("bearerToken"), enabled = o.optBoolean("enabled", true)
        )
    }
}

package dev.opencode.mobile.ai

import java.net.URI
import java.util.Locale

/** Do not send API credentials over HTTP; cleartext is only supported for local models. */
internal object ProviderUrlPolicy {
    fun error(baseUrl: String, apiKey: String): String? {
        val uri = try { URI(baseUrl.trim()) } catch (_: Exception) { return "Некорректный адрес провайдера" }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port > 65535
        ) return "Укажите корректный HTTP(S) Base URL без логина, пароля и параметров"
        if (scheme == "https") return null
        if (apiKey.isNotBlank()) return "API-ключ нельзя отправлять по HTTP. Используйте HTTPS или удалите ключ для локальной модели."
        if (!isLocalHost(host)) return "HTTP разрешён только для локального сервера. Для удалённого провайдера используйте HTTPS."
        return null
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        // IPv6 unique-local/link-local addresses.
        if (host.contains(':')) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { part ->
            if (part.isEmpty() || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }
}

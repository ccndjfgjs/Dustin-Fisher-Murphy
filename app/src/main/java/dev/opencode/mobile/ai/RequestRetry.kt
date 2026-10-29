package dev.opencode.mobile.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Retries only before a successful response starts; streamed output/tools are never replayed. */
internal object RequestRetry {
    private const val MAX_ATTEMPTS = 3
    private const val MAX_DELAY_MS = 10_000L
    private val retryable = setOf(429, 500, 502, 503, 504)

    suspend fun <T> execute(
        request: Request,
        onCall: (Call?) -> Unit,
        isCancelled: () -> Boolean,
        consume: suspend (Response) -> T
    ): T {
        for (attempt in 0 until MAX_ATTEMPTS) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("Запрос отменён")
            val call = sharedHttp.newCall(request)
            onCall(call)
            var gotResponse = false
            var wait = 0L
            try {
                val response = call.execute()
                gotResponse = true
                if (response.code in retryable && attempt < MAX_ATTEMPTS - 1) {
                    wait = delayMillis(attempt, response.header("Retry-After"), System.currentTimeMillis())
                    response.close()
                } else {
                    return response.use { consume(it) }
                }
            } catch (e: IOException) {
                // Once headers were received, a server may have sent a partial stream. Never retry it.
                currentCoroutineContext().ensureActive()
                if (isCancelled() || call.isCanceled()) throw CancellationException("Запрос отменён").apply { initCause(e) }
                if (gotResponse || attempt == MAX_ATTEMPTS - 1) throw e
                wait = delayMillis(attempt, null, System.currentTimeMillis())
            } finally {
                onCall(null)
            }
            currentCoroutineContext().ensureActive()
            if (isCancelled()) throw CancellationException("Запрос отменён")
            delay(wait)
        }
        error("Недостижимое состояние повтора")
    }

    internal fun delayMillis(attempt: Int, retryAfter: String?, now: Long): Long {
        val raw = retryAfter?.trim().orEmpty()
        val seconds = raw.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return seconds.coerceAtMost(MAX_DELAY_MS / 1000) * 1000
        if (raw.isNotEmpty()) {
            val date = runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(raw)?.time
            }.getOrNull()
            if (date != null) return (date - now).coerceIn(0, MAX_DELAY_MS)
        }
        return (750L shl attempt.coerceIn(0, 3)).coerceAtMost(MAX_DELAY_MS)
    }
}

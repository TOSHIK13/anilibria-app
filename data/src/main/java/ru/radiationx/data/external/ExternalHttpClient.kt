package ru.radiationx.data.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException

/** Сервис просит подождать (HTTP 429) надолго; до [untilMs] запросы к нему не делаются. */
class ExternalRateLimitException(val untilMs: Long) : IOException("rate limited")

/**
 * HTTP-клиент одного внешнего сервиса: пауза между запросами, повтор после 429 (Retry-After),
 * User-Agent (без него Cloudflare AniList отвечает 403), опциональный Bearer-токен и
 * дедупликация одинаковых запросов «в полёте» (ключ = метод + url + тело).
 */
class ExternalHttpClient(
    private val tag: String,
    private val clientProvider: () -> OkHttpClient,
    private val minIntervalMs: Long,
    private val userAgent: String,
    private val tokenProvider: (() -> String?)? = null,
) {

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_INLINE_WAIT_MS = 30_000L
        const val DEFAULT_RETRY_AFTER_S = 60L
        val JSON = "application/json".toMediaType()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastAt = 0L
    private var blockedUntil = 0L
    private val inFlight = HashMap<String, CompletableDeferred<Result<String?>>>()

    private val client: OkHttpClient by lazy { clientProvider() }

    suspend fun get(url: String, allow404: Boolean = false): String? =
        call("GET", url, null, allow404)

    suspend fun postJson(url: String, body: String, allow404: Boolean = false): String? =
        call("POST", url, body, allow404)

    private suspend fun call(method: String, url: String, body: String?, allow404: Boolean): String? {
        val key = "$method $url ${body.orEmpty()}"
        val (deferred, owner) = synchronized(inFlight) {
            val existing = inFlight[key]
            if (existing != null) existing to false
            else CompletableDeferred<Result<String?>>().also { inFlight[key] = it } to true
        }
        if (owner) {
            scope.launch {
                val result = runCatching { throttled { execute(method, url, body, allow404) } }
                synchronized(inFlight) { inFlight.remove(key) }
                deferred.complete(result)
            }
        }
        return deferred.await().getOrThrow()
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = mutex.withLock {
        if (System.currentTimeMillis() < blockedUntil) throw ExternalRateLimitException(blockedUntil)
        try {
            block()
        } catch (e: ExternalRateLimitException) {
            blockedUntil = e.untilMs
            throw e
        }
    }

    private suspend fun execute(method: String, url: String, body: String?, allow404: Boolean): String? {
        var attempt = 0
        while (true) {
            val wait = lastAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
            tokenProvider?.invoke()?.also { builder.header("Authorization", "Bearer $it") }
            if (body != null) builder.post(body.toRequestBody(JSON))
            val request = builder.build()
            Timber.d("external[%s]: %s %s (attempt %d)", tag, method, url, attempt + 1)
            var retryAfter = 0L
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 429) {
                        if (response.code == 404 && allow404) return null
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${request.url.host}")
                        return response.body?.string()
                    }
                    retryAfter = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S
                }
            } finally {
                lastAt = System.currentTimeMillis()
            }
            val waitMs = retryAfter * 1000
            Timber.w("external[%s]: 429, Retry-After %d s", tag, retryAfter)
            if (waitMs > MAX_INLINE_WAIT_MS || attempt >= MAX_RETRIES) {
                throw ExternalRateLimitException(System.currentTimeMillis() + waitMs)
            }
            delay(waitMs)
            attempt++
        }
    }
}

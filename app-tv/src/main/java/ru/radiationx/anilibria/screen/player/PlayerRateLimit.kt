package ru.radiationx.anilibria.screen.player

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Расчёт задержек при ответах CDN 429/5xx. Чистые функции (без Android API).
 */
object RateLimitBackoff {

    const val BASE_DELAY_MS = 1_000L
    const val MAX_BACKOFF_MS = 15_000L
    const val MAX_RETRY_AFTER_MS = 30_000L

    /** 429 и любые 5xx: сервер перегружен/ограничил нас, имеет смысл подождать и повторить. */
    fun isRateLimitStatus(responseCode: Int): Boolean {
        return responseCode == 429 || responseCode in 500..599
    }

    /**
     * [errorCount] начинается с 1. Без Retry-After: 1, 2, 4, 8, 15, 15... с.
     * С Retry-After (секунды): max(Retry-After, backoff), не больше [MAX_RETRY_AFTER_MS].
     */
    fun retryDelayMs(errorCount: Int, retryAfterHeader: String?): Long {
        val shift = (errorCount - 1).coerceIn(0, 10)
        val backoff = (BASE_DELAY_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        val retryAfter = parseRetryAfterMs(retryAfterHeader) ?: return backoff
        return maxOf(backoff, retryAfter).coerceAtMost(MAX_RETRY_AFTER_MS)
    }

    /** Только форма в секундах; HTTP-date игнорируется. */
    fun parseRetryAfterMs(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        if (seconds < 0L) {
            return null
        }
        return (seconds.coerceAtMost(MAX_RETRY_AFTER_MS / 1_000L) * 1_000L)
    }

    fun retryAfterHeader(headers: Map<out String?, List<String>?>): String? {
        return headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
    }
}

/**
 * Общий для плеера и HLS prefetch-а признак "CDN ограничил загрузку до T".
 * Prefetch не ходит в сеть, пока ограничение действует (плюс запас), чтобы не мешать плееру восстановиться.
 */
object CdnRateLimitGate {

    @Volatile
    private var blockedUntilElapsedMs = 0L

    fun report(delayMs: Long, source: String, responseCode: Int) {
        val now = SystemClock.elapsedRealtime()
        val until = now + delayMs
        synchronized(this) {
            if (until <= blockedUntilElapsedMs) {
                return
            }
            blockedUntilElapsedMs = until
        }
        Log.w(PLAYER_NET_TAG, "rate-limit code=$responseCode until=+${delayMs}ms source=$source")
    }

    /** Сколько ещё действует ограничение (0, если не действует). */
    fun remainingMs(): Long {
        return (blockedUntilElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
}

internal fun Throwable.findInvalidResponseCode(): HttpDataSource.InvalidResponseCodeException? {
    return generateSequence(this) { it.cause }
        .take(5)
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
}

/**
 * На 429/5xx повторяет загрузку с экспоненциальной задержкой (учитывая Retry-After) и не исключает
 * варианты потока. Остальные ошибки — как в [DefaultLoadErrorHandlingPolicy].
 */
@UnstableApi
class RateLimitLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val http = loadErrorInfo.exception.findInvalidResponseCode()
        if (http == null || !RateLimitBackoff.isRateLimitStatus(http.responseCode)) {
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
        val retryAfter = RateLimitBackoff.retryAfterHeader(http.headerFields)
        val delayMs = maxOf(
            RateLimitBackoff.retryDelayMs(loadErrorInfo.errorCount, retryAfter),
            CdnRateLimitGate.remainingMs().coerceAtMost(RateLimitBackoff.MAX_RETRY_AFTER_MS),
        )
        CdnRateLimitGate.report(delayMs, source = "player", responseCode = http.responseCode)
        return delayMs
    }

    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val http = loadErrorInfo.exception.findInvalidResponseCode()
        if (http != null && RateLimitBackoff.isRateLimitStatus(http.responseCode)) {
            // Ограничение CDN не связано с конкретным вариантом: не исключаем его, просто ждём.
            return null
        }
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        return maxOf(super.getMinimumLoadableRetryCount(dataType), MIN_RETRY_COUNT)
    }

    private companion object {
        // 1+2+4+8+15+15+15+15 ≈ 75 с ожидания, прежде чем ошибка станет фатальной.
        const val MIN_RETRY_COUNT = 8
    }
}

package ru.radiationx.data.player

import androidx.media3.exoplayer.DefaultLoadControl
import java.util.concurrent.TimeUnit

object PlayerBufferConfig {

    const val DEFAULT_FORWARD_BUFFER_SECONDS = 45
    const val DEFAULT_BUFFER_MEMORY_LIMIT_MB = 16
    const val DEFAULT_DISK_CACHE_SIZE_MB = 2 * 1024

    private const val MIN_FORWARD_BUFFER_SECONDS = 1
    private const val MAX_FORWARD_BUFFER_SECONDS = 600
    private const val MIN_BACK_BUFFER_SECONDS = 0
    private const val MAX_BACK_BUFFER_SECONDS = 600
    private const val MIN_BUFFER_MEMORY_LIMIT_MB = 8
    private const val MAX_BUFFER_MEMORY_LIMIT_MB = 64
    private const val MIN_BUFFER_MS = 20_000
    private const val BUFFER_FOR_PLAYBACK_MS = 1_500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    private const val HEAP_HEADROOM_RESERVE_MB = 24L

    fun createLoadControl(
        forwardBufferSeconds: Int,
        backBufferSeconds: Int,
        bufferMemoryLimitMb: Int = DEFAULT_BUFFER_MEMORY_LIMIT_MB,
    ): DefaultLoadControl {
        val maxBufferMs = forwardBufferSeconds
            .coerceIn(MIN_FORWARD_BUFFER_SECONDS, MAX_FORWARD_BUFFER_SECONDS)
            .secondsToMillis()
        val minBufferMs = MIN_BUFFER_MS.coerceAtMost(maxBufferMs)
        val backBufferMs = backBufferSeconds
            .coerceIn(MIN_BACK_BUFFER_SECONDS, MAX_BACK_BUFFER_SECONDS)
            .secondsToMillis()

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufferMs,
                maxBufferMs,
                BUFFER_FOR_PLAYBACK_MS.coerceAtMost(minBufferMs),
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS.coerceAtMost(minBufferMs)
            )
            .setTargetBufferBytes(targetBufferBytes(bufferMemoryLimitMb).toInt())
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(backBufferMs, true)
            .build()
    }

    fun targetBufferBytes(bufferMemoryLimitMb: Int): Long {
        return bufferMemoryLimitMb
            .coerceIn(MIN_BUFFER_MEMORY_LIMIT_MB, MAX_BUFFER_MEMORY_LIMIT_MB)
            .toLong()
            .megabytesToBytes()
    }

    /**
     * Абсолютный запас heap: предзагрузка (prefetch и preload следующей серии) разрешена,
     * только если свободно больше, чем целевой буфер плеера + резерв.
     */
    fun isHeapHeadroomLow(bufferMemoryLimitMb: Int): Boolean {
        return heapFreeBytes() < targetBufferBytes(bufferMemoryLimitMb) +
            HEAP_HEADROOM_RESERVE_MB.megabytesToBytes()
    }

    fun heapFreeBytes(): Long {
        val runtime = Runtime.getRuntime()
        val usedBytes = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        return (runtime.maxMemory() - usedBytes).coerceAtLeast(0L)
    }

    private fun Int.secondsToMillis(): Int {
        return TimeUnit.SECONDS.toMillis(toLong()).toInt()
    }

    private fun Long.megabytesToBytes(): Long {
        return this * 1024L * 1024L
    }
}

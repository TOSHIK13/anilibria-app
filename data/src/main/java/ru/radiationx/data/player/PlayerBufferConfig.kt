package ru.radiationx.data.player

import androidx.media3.exoplayer.DefaultLoadControl
import java.util.concurrent.TimeUnit

object PlayerBufferConfig {

    private const val MIN_FORWARD_BUFFER_SECONDS = 1
    private const val MAX_FORWARD_BUFFER_SECONDS = 600
    private const val MIN_BACK_BUFFER_SECONDS = 0
    private const val MAX_BACK_BUFFER_SECONDS = 600
    private const val MIN_BUFFER_MEMORY_LIMIT_MB = 8
    private const val MAX_BUFFER_MEMORY_LIMIT_MB = 256

    fun createLoadControl(
        forwardBufferSeconds: Int,
        backBufferSeconds: Int,
        bufferMemoryLimitMb: Int = 32,
    ): DefaultLoadControl {
        val bufferMs = forwardBufferSeconds
            .coerceIn(MIN_FORWARD_BUFFER_SECONDS, MAX_FORWARD_BUFFER_SECONDS)
            .secondsToMillis()
        val backBufferMs = backBufferSeconds
            .coerceIn(MIN_BACK_BUFFER_SECONDS, MAX_BACK_BUFFER_SECONDS)
            .secondsToMillis()
        val targetBufferBytes = bufferMemoryLimitMb
            .coerceIn(MIN_BUFFER_MEMORY_LIMIT_MB, MAX_BUFFER_MEMORY_LIMIT_MB)
            .megabytesToBytes()

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs,
                bufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(backBufferMs, true)
            .build()
    }

    private fun Int.secondsToMillis(): Int {
        return TimeUnit.SECONDS.toMillis(toLong()).toInt()
    }

    private fun Int.megabytesToBytes(): Int {
        return (toLong() * 1024L * 1024L).toInt()
    }
}

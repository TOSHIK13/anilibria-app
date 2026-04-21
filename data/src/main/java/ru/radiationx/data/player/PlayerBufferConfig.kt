package ru.radiationx.data.player

import androidx.media3.exoplayer.DefaultLoadControl
import java.util.concurrent.TimeUnit

object PlayerBufferConfig {

    private const val MIN_FORWARD_BUFFER_SECONDS = 1
    private const val MAX_FORWARD_BUFFER_SECONDS = 600
    private const val MIN_BACK_BUFFER_SECONDS = 0
    private const val MAX_BACK_BUFFER_SECONDS = 600

    fun createLoadControl(
        forwardBufferSeconds: Int,
        backBufferSeconds: Int,
    ): DefaultLoadControl {
        val bufferMs = forwardBufferSeconds
            .coerceIn(MIN_FORWARD_BUFFER_SECONDS, MAX_FORWARD_BUFFER_SECONDS)
            .secondsToMillis()
        val backBufferMs = backBufferSeconds
            .coerceIn(MIN_BACK_BUFFER_SECONDS, MAX_BACK_BUFFER_SECONDS)
            .secondsToMillis()

        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferMs,
                bufferMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setBackBuffer(backBufferMs, true)
            .build()
    }

    private fun Int.secondsToMillis(): Int {
        return TimeUnit.SECONDS.toMillis(toLong()).toInt()
    }
}

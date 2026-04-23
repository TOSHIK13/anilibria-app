package ru.radiationx.data.player

import kotlin.math.max

object EpisodePlaybackRules {

    const val WATCHED_PROGRESS_THRESHOLD = 0.8
    private const val END_PROGRESS_THRESHOLD = 0.995
    private const val END_POSITION_TOLERANCE_MS = 500L

    fun clampPosition(position: Long, duration: Long): Long {
        return if (duration > 0L) {
            position.coerceIn(0L, duration)
        } else {
            position.coerceAtLeast(0L)
        }
    }

    fun hasReachedWatchedThreshold(position: Long, duration: Long): Boolean {
        if (duration <= 0L) {
            return false
        }
        return position >= (duration * WATCHED_PROGRESS_THRESHOLD).toLong()
    }

    fun isAtEpisodeEnd(position: Long, duration: Long): Boolean {
        if (duration <= 0L) {
            return false
        }
        val completionThreshold = max(
            duration - END_POSITION_TOLERANCE_MS,
            (duration * END_PROGRESS_THRESHOLD).toLong(),
        )
        return clampPosition(position, duration) >= completionThreshold
    }
}

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

    /** За сколько до конца серии заранее готовятся данные следующей (access/seek, URL). */
    const val PREARM_WINDOW_MS = 45_000L

    /** Сколько ждать перехода ExoPlayer на следующий MediaItem после достижения конца серии. */
    const val QUEUE_TRANSITION_GRACE_MS = 1_500L

    fun isInPrearmWindow(position: Long, duration: Long, windowMs: Long = PREARM_WINDOW_MS): Boolean {
        if (duration <= 0L) {
            return false
        }
        val remaining = duration - clampPosition(position, duration)
        return remaining <= windowMs
    }

    /**
     * Конец серии достигнут в [endReachedAtMs], а переход очереди так и не случился:
     * пора откатываться на обычное (app-side) завершение серии.
     */
    fun isQueueTransitionOverdue(
        endReachedAtMs: Long,
        nowMs: Long,
        graceMs: Long = QUEUE_TRANSITION_GRACE_MS,
    ): Boolean {
        if (endReachedAtMs <= 0L) {
            return false
        }
        return nowMs - endReachedAtMs >= graceMs
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

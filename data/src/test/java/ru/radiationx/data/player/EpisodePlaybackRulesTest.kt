package ru.radiationx.data.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePlaybackRulesTest {

    private val duration = 24L * 60L * 1_000L

    @Test
    fun `episode end is last 500 ms for long episodes`() {
        assertFalse(EpisodePlaybackRules.isAtEpisodeEnd(duration - 501L, duration))
        assertTrue(EpisodePlaybackRules.isAtEpisodeEnd(duration - 500L, duration))
        assertTrue(EpisodePlaybackRules.isAtEpisodeEnd(duration + 10L, duration))
        assertFalse(EpisodePlaybackRules.isAtEpisodeEnd(0L, 0L))
    }

    @Test
    fun `watched threshold is 80 percent`() {
        assertFalse(EpisodePlaybackRules.hasReachedWatchedThreshold((duration * 0.8).toLong() - 1L, duration))
        assertTrue(EpisodePlaybackRules.hasReachedWatchedThreshold((duration * 0.8).toLong(), duration))
        assertFalse(EpisodePlaybackRules.hasReachedWatchedThreshold(10L, 0L))
    }

    @Test
    fun `prearm window starts 45 s before end`() {
        assertFalse(EpisodePlaybackRules.isInPrearmWindow(duration - 45_001L, duration))
        assertTrue(EpisodePlaybackRules.isInPrearmWindow(duration - 45_000L, duration))
        assertTrue(EpisodePlaybackRules.isInPrearmWindow(duration, duration))
        assertFalse(EpisodePlaybackRules.isInPrearmWindow(0L, 0L))
    }

    @Test
    fun `queue transition fallback fires after grace period`() {
        assertFalse(EpisodePlaybackRules.isQueueTransitionOverdue(0L, 10_000L))
        assertFalse(EpisodePlaybackRules.isQueueTransitionOverdue(10_000L, 11_499L))
        assertTrue(EpisodePlaybackRules.isQueueTransitionOverdue(10_000L, 11_500L))
    }
}

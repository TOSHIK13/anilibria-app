package ru.radiationx.data.repository.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseCardCacheLogicTest {

    private fun info(id: Int, title: String = "t$id") = ReleaseCardInfo(id = id, title = title)

    @Test
    fun merge_freshReplacesOldById() {
        val result = ReleaseCardCacheLogic.merge(
            old = listOf(info(1, "old"), info(2)),
            fresh = listOf(info(1, "new")),
            priority = emptyList(),
            max = 10,
        )
        assertEquals(listOf(info(1, "new"), info(2)), result)
    }

    @Test
    fun merge_priorityFirstThenFreshThenOld() {
        val result = ReleaseCardCacheLogic.merge(
            old = listOf(info(1), info(2), info(3)),
            fresh = listOf(info(4)),
            priority = listOf(3, 99, 1),
            max = 10,
        )
        assertEquals(listOf(3, 1, 4, 2), result.map { it.id })
    }

    @Test
    fun merge_capsAtMaxKeepingPriority() {
        val old = (1..60).map { info(it) }
        val result = ReleaseCardCacheLogic.merge(
            old = old,
            fresh = emptyList(),
            priority = listOf(60, 59),
            max = 50,
        )
        assertEquals(50, result.size)
        assertEquals(listOf(60, 59, 1), result.take(3).map { it.id })
    }

    @Test
    fun merge_emptyInputs() {
        assertEquals(
            emptyList<ReleaseCardInfo>(),
            ReleaseCardCacheLogic.merge(emptyList(), emptyList(), listOf(1), 50)
        )
    }

    @Test
    fun toRefresh_skipsAlreadyRefreshedAndDuplicates() {
        assertEquals(
            listOf(2, 3),
            ReleaseCardCacheLogic.toRefresh(listOf(1, 2, 2, 3), setOf(1))
        )
    }

    private fun ep(id: String) = ReleaseCardEpisode(serverId = id, ordinal = id)

    @Test
    fun trimEpisodes_keepsFocusAndNext() {
        val info = ReleaseCardInfo(id = 1, episodes = listOf(ep("a"), ep("b"), ep("c"), ep("d")))
        assertEquals(listOf(ep("b"), ep("c")), ReleaseCardCacheLogic.trimEpisodes(info, "b").episodes)
        assertEquals(listOf(ep("d")), ReleaseCardCacheLogic.trimEpisodes(info, "d").episodes)
        assertEquals(emptyList<ReleaseCardEpisode>(), ReleaseCardCacheLogic.trimEpisodes(info, null).episodes)
    }

    @Test
    fun withEpisodesFrom_fillsOnlyEmpty() {
        val cached = ReleaseCardInfo(id = 1, episodes = listOf(ep("a")))
        val empty = ReleaseCardInfo(id = 1)
        val own = ReleaseCardInfo(id = 1, episodes = listOf(ep("b")))
        assertEquals(cached.episodes, ReleaseCardCacheLogic.withEpisodesFrom(empty, cached).episodes)
        assertEquals(own, ReleaseCardCacheLogic.withEpisodesFrom(own, cached))
        assertEquals(empty, ReleaseCardCacheLogic.withEpisodesFrom(empty, null))
    }
}

package ru.radiationx.data.repository.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.view.ViewHistoryEpisodeResponse
import ru.radiationx.data.entity.response.view.ViewHistoryItemResponse
import ru.radiationx.data.entity.response.view.ViewHistoryReleaseResponse
import ru.radiationx.data.repository.ReleaseWatchProgress

class WatchHistoryLogicTest {

    private fun ep(
        id: String,
        release: Int,
        ordinal: Float?,
        updatedAt: Long,
        watched: Boolean = false,
        total: Int? = null,
        time: Float? = 10f,
    ) = WatchHistoryEpisode(id, release, ordinal, time, watched, updatedAt, total)

    @Test
    fun fromResponse_mapsFieldsAndSkipsBrokenItems() {
        val items = listOf(
            ViewHistoryItemResponse(
                time = 125.5f,
                isWatched = true,
                updatedAt = "2025-03-01T10:20:30.123456Z",
                releaseEpisodeId = "e1",
                releaseEpisode = ViewHistoryEpisodeResponse(
                    ordinal = 3f,
                    releaseId = 10,
                    release = ViewHistoryReleaseResponse(10, 12),
                ),
            ),
            // release_id только во вложенном release
            ViewHistoryItemResponse(
                isWatched = null,
                releaseEpisodeId = "e2",
                releaseEpisode = ViewHistoryEpisodeResponse(
                    releaseId = null,
                    release = ViewHistoryReleaseResponse(11, 0),
                ),
            ),
            ViewHistoryItemResponse(isWatched = true, releaseEpisodeId = null, releaseEpisode = null),
            ViewHistoryItemResponse(isWatched = true, releaseEpisodeId = "e3", releaseEpisode = null),
        )
        val result = WatchHistoryLogic.fromResponse(items)
        assertEquals(2, result.size)
        val first = result[0]
        assertEquals("e1", first.episodeId)
        assertEquals(10, first.releaseId)
        assertEquals(3f, first.ordinal)
        assertEquals(125.5f, first.time)
        assertTrue(first.isWatched)
        assertEquals(12, first.episodesTotal)
        assertEquals(WatchHistoryLogic.parseDate("2025-03-01T10:20:30Z"), first.updatedAt)
        assertTrue(first.updatedAt > 0)
        assertEquals(11, result[1].releaseId)
        assertNull(result[1].episodesTotal)
        assertEquals(0L, result[1].updatedAt)
    }

    @Test
    fun parseDate_supportsOffsetsAndFractions() {
        val base = WatchHistoryLogic.parseDate("2025-03-01T10:20:30Z")
        assertEquals(base, WatchHistoryLogic.parseDate("2025-03-01T10:20:30+00:00"))
        assertEquals(base, WatchHistoryLogic.parseDate("2025-03-01T13:20:30.5+03:00"))
        assertEquals(base, WatchHistoryLogic.parseDate("2025-03-01T10:20:30"))
        assertEquals(base, WatchHistoryLogic.parseDate(WatchHistoryLogic.formatDate(base)))
        assertEquals(0L, WatchHistoryLogic.parseDate("garbage"))
        assertEquals(0L, WatchHistoryLogic.parseDate(null))
    }

    @Test
    fun continueList_lastEpisodePerRelease_sortedByLatest() {
        val episodes = listOf(
            ep("a1", 1, 1f, 100, watched = true),
            ep("a2", 1, 2f, 300),
            ep("b1", 2, 1f, 500),
            ep("c5", 3, 5f, 200, watched = true),
            ep("c6", 3, 6f, 150),
        )
        val result = WatchHistoryLogic.continueList(episodes)
        assertEquals(listOf(2, 1, 3), result.map { it.releaseId.id })
        assertEquals(listOf("b1", "a2", "c5"), result.map { it.episodeId })
        assertEquals(6f, WatchHistoryLogic.continueList(listOf(ep("x", 4, 5f, 1), ep("y", 4, 6f, 1)))[0].ordinal)
    }

    @Test
    fun continueList_keepsFullyWatchedReleases() {
        val episodes = listOf(
            ep("a1", 1, 1f, 100, watched = true, total = 2),
            ep("a2", 1, 2f, 300, watched = true, total = 2),
            ep("b1", 2, 1f, 200, watched = true, total = 2),
            ep("c1", 3, 1f, 50, watched = true, total = null),
        )
        val result = WatchHistoryLogic.continueList(episodes)
        assertEquals(listOf(1, 2, 3), result.map { it.releaseId.id })
    }

    @Test
    fun filterHidden_dropsWatchedAndAbandonedCollections() {
        val items = WatchHistoryLogic.continueList(
            (1..7).map { ep("e$it", it, 1f, 100L - it) }
        )
        val collections = mapOf(
            ReleaseId(1) to CollectionType.WATCHED,
            ReleaseId(2) to CollectionType.ABANDONED,
            ReleaseId(3) to CollectionType.WATCHING,
            ReleaseId(4) to CollectionType.PLANNED,
            ReleaseId(5) to CollectionType.POSTPONED,
            // 6 и 7 — без коллекции; 99 — нет в истории
            ReleaseId(99) to CollectionType.WATCHED,
        )
        val result = WatchHistoryLogic.filterHidden(items, collections)
        assertEquals(listOf(3, 4, 5, 6, 7), result.map { it.releaseId.id })
    }

    @Test
    fun filterHidden_keepsListWhenCollectionsUnknownOrEmpty() {
        val items = WatchHistoryLogic.continueList(listOf(ep("a", 1, 1f, 2), ep("b", 2, 1f, 1)))
        assertEquals(items, WatchHistoryLogic.filterHidden(items, null))
        assertEquals(items, WatchHistoryLogic.filterHidden(items, emptyMap()))
        assertEquals(emptyList<ContinueWatchingItem>(), WatchHistoryLogic.filterHidden(emptyList(), null))
    }

    @Test
    fun progress_countsWatchedAndTakesTotal() {
        val progress = WatchHistoryLogic.progress(
            listOf(
                ep("a1", 1, 1f, 1, watched = true, total = 12),
                ep("a2", 1, 2f, 2, watched = false),
                ep("b1", 2, 1f, 3, watched = true),
            )
        )
        assertEquals(ReleaseWatchProgress(1, 12), progress[ReleaseId(1)])
        assertEquals(ReleaseWatchProgress(1, null), progress[ReleaseId(2)])
    }

    @Test
    fun mergeServer_keepsLocalChangesMadeDuringLoad() {
        val server = listOf(ep("a1", 1, 1f, 100, time = 10f), ep("a2", 1, 2f, 100))
        val local = mapOf(
            "a1" to ep("a1", 1, null, 1_000, time = 50f),
            "old" to ep("old", 9, 1f, 10),
        )
        val merged = WatchHistoryLogic.mergeServer(server, local, since = 500)
        assertEquals(setOf("a1", "a2"), merged.keys)
        assertEquals(50f, merged.getValue("a1").time)
        // ordinal неизвестен локально — берётся с сервера
        assertEquals(1f, merged.getValue("a1").ordinal)
    }

    @Test
    fun applyPending_overridesOlderAndDeletes() {
        val base = mapOf(
            "a1" to ep("a1", 1, 1f, 100, total = 12),
            "a2" to ep("a2", 1, 2f, 100),
            "a3" to ep("a3", 1, 3f, 900),
        )
        val pending = listOf(
            PendingTimecode("a1", 1, 42.0, true, null, 200),
            PendingTimecode("a2", 1, null, createdAt = 200),
            PendingTimecode("a3", 1, 1.0, false, 3f, 300), // старше серверного — игнор
            PendingTimecode("n1", 2, 5.0, false, 1f, 400),
        )
        val result = WatchHistoryLogic.applyPending(base, pending)
        assertEquals(setOf("a1", "a3", "n1"), result.keys)
        assertEquals(42f, result.getValue("a1").time)
        assertTrue(result.getValue("a1").isWatched)
        assertEquals(1f, result.getValue("a1").ordinal)
        assertEquals(12, result.getValue("a1").episodesTotal)
        assertEquals(900L, result.getValue("a3").updatedAt)
        assertEquals(2, result.getValue("n1").releaseId)
    }

    @Test
    fun mergePending_latestPerEpisodeWins() {
        val queue = listOf(
            PendingTimecode("a", 1, 10.0, createdAt = 100),
            PendingTimecode("b", 1, 20.0, createdAt = 100),
        )
        val ops = listOf(
            PendingTimecode("a", 1, 30.0, createdAt = 200),
            PendingTimecode("b", 1, null, createdAt = 50),
        )
        val merged = WatchHistoryLogic.mergePending(queue, ops)
        assertEquals(2, merged.size)
        assertEquals(30.0, merged.first { it.episodeId == "a" }.time)
        assertEquals(20.0, merged.first { it.episodeId == "b" }.time)
    }

    @Test
    fun mergePending_capsSize() {
        val ops = (1..10).map { PendingTimecode("e$it", 1, 1.0, createdAt = it.toLong()) }
        val merged = WatchHistoryLogic.mergePending(emptyList(), ops, maxSize = 3)
        assertEquals(listOf("e8", "e9", "e10"), merged.map { it.episodeId })
    }

    @Test
    fun dropSent_keepsNewerChanges() {
        val queue = listOf(
            PendingTimecode("a", 1, 10.0, createdAt = 100),
            PendingTimecode("b", 1, 20.0, createdAt = 300),
            PendingTimecode("c", 1, 20.0, createdAt = 300),
        )
        val sent = listOf(
            PendingTimecode("a", 1, 10.0, createdAt = 100),
            PendingTimecode("b", 1, 15.0, createdAt = 200),
        )
        assertEquals(listOf("b", "c"), WatchHistoryLogic.dropSent(queue, sent).map { it.episodeId })
    }

    @Test
    fun ordinalLabel_formatsWholeAndFractional() {
        assertEquals("5", WatchHistoryLogic.ordinalLabel(5f))
        assertEquals("12.5", WatchHistoryLogic.ordinalLabel(12.5f))
    }
}

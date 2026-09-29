package ru.radiationx.data.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.domain.collection.CollectionType

class SyncRulesTest {

    private fun item(status: DesiredStatus? = null, progress: Int? = null, source: OutboxSource = OutboxSource.EPISODE, ep: Int? = null) =
        OutboxItem(
            id = 1, serviceId = "anilist", malId = 10, releaseId = 5, title = "T",
            desired = DesiredState(status, progress, 11), source = source, createdAt = 0,
            episodes = listOfNotNull(ep),
        )

    private val tv = MediaInfo(1, 11, "TV")
    private fun remote(status: String, progress: Int) = RemoteEntry(7, status, progress, 0)

    // --- коалесинг ---

    @Test
    fun `merge keeps max progress and counts events`() {
        val merged = SyncRules.merge(item(progress = 5, ep = 5), item(progress = 4, ep = 4))
        assertEquals(5, merged.desired.progress)
        assertEquals(2, merged.mergedCount)
        assertEquals(listOf(4, 5), merged.episodes)
        assertEquals("серии 4 и 5 объединены в один запрос", SyncRules.mergeDetail(merged))
    }

    @Test
    fun `merge takes latest explicit status`() {
        val merged = SyncRules.merge(
            item(DesiredStatus.PAUSED, source = OutboxSource.COLLECTION),
            item(DesiredStatus.DROPPED, source = OutboxSource.COLLECTION),
        )
        assertEquals(DesiredStatus.DROPPED, merged.desired.status)
        assertEquals(OutboxSource.COLLECTION, merged.source)
    }

    @Test
    fun `episode after paused resets status so it becomes current`() {
        val merged = SyncRules.merge(item(DesiredStatus.PAUSED, source = OutboxSource.COLLECTION), item(progress = 3, ep = 3))
        assertNull(merged.desired.status)
        assertEquals(3, merged.desired.progress)
    }

    @Test
    fun `episode keeps completed status from collection`() {
        val merged = SyncRules.merge(item(DesiredStatus.COMPLETED, source = OutboxSource.COLLECTION), item(progress = 3, ep = 3))
        assertEquals(DesiredStatus.COMPLETED, merged.desired.status)
    }

    @Test
    fun `remove drops progress`() {
        val merged = SyncRules.merge(item(progress = 3, ep = 3), item(DesiredStatus.REMOVE, source = OutboxSource.COLLECTION))
        assertEquals(DesiredStatus.REMOVE, merged.desired.status)
        assertNull(merged.desired.progress)
    }

    @Test
    fun `merge bumps version`() {
        assertEquals(1, SyncRules.merge(item(progress = 1), item(progress = 2)).version)
    }

    // --- backoff ---

    @Test
    fun `backoff schedule then error after five attempts`() {
        assertEquals(30_000L, SyncRules.backoffMs(1))
        assertEquals(120_000L, SyncRules.backoffMs(2))
        assertEquals(600_000L, SyncRules.backoffMs(3))
        assertEquals(1_800_000L, SyncRules.backoffMs(4))
        assertNull(SyncRules.backoffMs(5))
    }

    // --- правила записи ---

    @Test
    fun `episode creates current entry with progress`() {
        val plan = AniListWriteRules.plan(DesiredState(null, 3, 11), tv, null)
        assertEquals(SyncPlan.Save("CURRENT", 3), plan)
    }

    @Test
    fun `episode never lowers progress`() {
        val plan = AniListWriteRules.plan(DesiredState(null, 3, 11), tv, remote("CURRENT", 7))
        assertTrue(plan is SyncPlan.UpToDate)
    }

    @Test
    fun `episode never downgrades completed`() {
        val plan = AniListWriteRules.plan(DesiredState(null, 4, 11), tv, remote("COMPLETED", 11))
        assertTrue(plan is SyncPlan.UpToDate)
    }

    @Test
    fun `episode on completed with higher progress keeps completed`() {
        val plan = AniListWriteRules.plan(DesiredState(null, 12, null), MediaInfo(1, null, "TV"), remote("COMPLETED", 11))
        assertEquals(SyncPlan.Save("COMPLETED", 12), plan)
    }

    @Test
    fun `last episode completes the title`() {
        val plan = AniListWriteRules.plan(DesiredState(null, 11, 11), tv, remote("CURRENT", 10))
        assertEquals(SyncPlan.Save("COMPLETED", 11), plan)
    }

    @Test
    fun `watching resumes paused dropped and planning`() {
        listOf("PAUSED", "DROPPED", "PLANNING").forEach { s ->
            assertEquals(SyncPlan.Save("CURRENT", 2), AniListWriteRules.plan(DesiredState(null, 2, 11), tv, remote(s, 0)))
        }
    }

    @Test
    fun `collection watching keeps repeating`() {
        val plan = AniListWriteRules.plan(DesiredState(DesiredStatus.CURRENT), tv, remote("REPEATING", 3))
        assertTrue(plan is SyncPlan.UpToDate)
        val plan2 = AniListWriteRules.plan(DesiredState(DesiredStatus.CURRENT, 5), tv, remote("REPEATING", 3))
        assertEquals(SyncPlan.Save("REPEATING", 5), plan2)
    }

    @Test
    fun `collection completed sets progress to episodes`() {
        val plan = AniListWriteRules.plan(DesiredState(DesiredStatus.COMPLETED), tv, remote("CURRENT", 4))
        assertEquals(SyncPlan.Save("COMPLETED", 11), plan)
    }

    @Test
    fun `collection change keeps remote progress`() {
        val plan = AniListWriteRules.plan(DesiredState(DesiredStatus.PAUSED), tv, remote("CURRENT", 4))
        assertEquals(SyncPlan.Save("PAUSED", 4), plan)
    }

    @Test
    fun `remove deletes existing entry only`() {
        assertEquals(SyncPlan.Delete(7), AniListWriteRules.plan(DesiredState(DesiredStatus.REMOVE), tv, remote("CURRENT", 1)))
        assertTrue(AniListWriteRules.plan(DesiredState(DesiredStatus.REMOVE), tv, null) is SyncPlan.UpToDate)
    }

    @Test
    fun `collection type mapping`() {
        assertEquals(DesiredStatus.PLANNING, DesiredStatus.of(CollectionType.PLANNED))
        assertEquals(DesiredStatus.CURRENT, DesiredStatus.of(CollectionType.WATCHING))
        assertEquals(DesiredStatus.COMPLETED, DesiredStatus.of(CollectionType.WATCHED))
        assertEquals(DesiredStatus.PAUSED, DesiredStatus.of(CollectionType.POSTPONED))
        assertEquals(DesiredStatus.DROPPED, DesiredStatus.of(CollectionType.ABANDONED))
        assertEquals(DesiredStatus.REMOVE, DesiredStatus.of(null))
    }

    // --- тексты и фильмы ---

    @Test
    fun `movie says film instead of 1 of 1`() {
        val movie = MediaInfo(2, 1, "MOVIE")
        assertEquals("Фильм", AniListWriteRules.progressText(1, movie, null))
        assertEquals("просмотрен · Фильм", AniListWriteRules.describe(item(progress = 1, ep = 1), SyncPlan.Save("COMPLETED", 1), movie))
        assertEquals("5/11", AniListWriteRules.progressText(5, tv, null))
    }

    @Test
    fun `journal text for episode and collection`() {
        assertEquals("серия 5 → прогресс 5/11", AniListWriteRules.describe(item(progress = 5, ep = 5), SyncPlan.Save("CURRENT", 5), tv))
        assertEquals(
            "коллекция «Отложено»",
            AniListWriteRules.describe(item(DesiredStatus.PAUSED, source = OutboxSource.COLLECTION), SyncPlan.Save("PAUSED", 4), tv)
        )
    }
}

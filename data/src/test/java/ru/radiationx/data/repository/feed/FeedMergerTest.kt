package ru.radiationx.data.repository.feed

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.domain.feed.FeedItem
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.youtube.YoutubeItem
import ru.radiationx.data.entity.mapper.Fixtures
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.media.V1VideoResponse

class FeedMergerTest {

    private val latest: List<Release> = Fixtures
        .parseList<CollectionReleaseResponse>("v1/releases_latest_limit_10.json")
        .map { it.toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE) }

    private val catalogPage2: List<Release> = Fixtures
        .parse<CollectionReleasesResponse>("v1/catalog_fresh_page_2.json")
        .data
        .map { it.toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE) }

    private val videos: List<YoutubeItem> = Fixtures
        .parseList<V1VideoResponse>("v1/media_videos_limit_10.json")
        .map { it.toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE) }

    private val requestedPages = mutableListOf<Int>()

    private fun merger() = FeedMerger(pageSize = 10) { page ->
        requestedPages.add(page)
        if (page == 2) catalogPage2 else emptyList()
    }.apply { reset(latest, videos) }

    /** "r:<id>" / "y:<id>" из legacy `query=feed` (снято одновременно с v1-фикстурами). */
    private fun legacyPage(name: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val root = Fixtures.moshi.adapter(Map::class.java).fromJson(Fixtures.read(name)) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val data = root["data"] as List<Map<String, Any?>>
        return data.map { item ->
            @Suppress("UNCHECKED_CAST")
            val release = item["release"] as Map<String, Any?>?
            @Suppress("UNCHECKED_CAST")
            val youtube = item["youtube"] as Map<String, Any?>?
            if (release != null) "r:${(release["id"] as Number).toInt()}"
            else "y:${(youtube!!["id"] as Number).toInt()}"
        }
    }

    private fun List<FeedItem>.keys() = map { item ->
        item.release?.let { "r:${it.id.id}" } ?: "y:${item.youtube!!.id.id}"
    }

    @Test
    fun firstTwoPages_matchLegacyFeed() = runBlocking {
        val merger = merger()

        assertEquals(legacyPage("legacy/feed_page_1.json"), merger.getPage(1).keys())
        assertEquals(emptyList<Int>(), requestedPages)
        assertEquals(legacyPage("legacy/feed_page_2.json"), merger.getPage(2).keys())
        assertEquals(listOf(2), requestedPages)
    }

    @Test
    fun pages_areSortedByTimeDescending() = runBlocking {
        val merger = merger()
        val items = merger.getPage(1) + merger.getPage(2)
        val times = items.map { it.release?.torrentUpdate ?: it.youtube!!.timestamp }

        assertEquals(20, items.size)
        assertEquals(times.sortedDescending(), times)
        assertTrue(items.all { (it.release == null) != (it.youtube == null) })
    }

    @Test
    fun afterVideosAndReleasesEnd_returnsRemainderThenEmpty() = runBlocking {
        val merger = merger()
        val all = (1..5).flatMap { merger.getPage(it) }

        // 10 latest + 10 catalog + 10 videos, без дублей
        assertEquals(30, all.size)
        assertEquals(30, all.map { it.id }.toSet().size)
        assertEquals(emptyList<FeedItem>(), merger.getPage(4))
        assertEquals(listOf(2, 3), requestedPages)
    }

    @Test
    fun duplicatesFromShiftedCatalogPage_areSkipped() = runBlocking {
        val merger = FeedMerger(pageSize = 10) { page ->
            if (page == 2) latest.takeLast(3) + catalogPage2 else emptyList()
        }.apply { reset(latest, emptyList()) }

        val all = merger.getPage(1) + merger.getPage(2)

        assertEquals(20, all.size)
        assertEquals(20, all.map { it.id }.toSet().size)
    }
}

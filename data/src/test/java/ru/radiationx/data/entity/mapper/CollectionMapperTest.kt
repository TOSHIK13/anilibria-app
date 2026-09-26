package ru.radiationx.data.entity.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseCode
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse

class CollectionMapperTest {

    private fun CollectionReleaseResponse.map(favoriteAdded: Boolean = false): Release =
        toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE, favoriteAdded)

    @Test
    fun fullRelease_mapsMainFields() {
        val release = Fixtures.parse<CollectionReleaseResponse>("release_10292.json").map()

        assertEquals(ReleaseId(10292), release.id)
        assertEquals(ReleaseCode("super-no-ura-de-yani-suu-futari"), release.code)
        assertEquals("Super no Ura de Yani Suu Futari", release.names[1])
        assertEquals("12", release.series)
        assertEquals("2026", release.year)
        assertTrue(release.genres.isNotEmpty())
        assertEquals(Release.STATUS_CODE_COMPLETE, release.statusCode)
        assertEquals("https://www.anilibria.tv/release/super-no-ura-de-yani-suu-futari.html", release.link)
        assertFalse(release.favoriteInfo.isAdded)
    }

    @Test
    fun fullRelease_mapsEpisodes() {
        val release = Fixtures.parse<CollectionReleaseResponse>("release_10292.json").map()

        assertEquals(12, release.episodes.size)
        assertEquals(12, release.sourceEpisodes.size)
        val first = release.episodes.first()
        assertEquals("1", first.id.id)
        assertNotNull(first.qualityInfo.urlFullHd ?: first.qualityInfo.urlHd ?: first.qualityInfo.urlSd)
        assertNotNull(first.updatedAt)
    }

    @Test
    fun poster_prefersOptimizedPreview() {
        val release = Fixtures.parse<CollectionReleaseResponse>("release_10292.json").map()

        assertTrue(
            release.poster.orEmpty()
                .endsWith("/storage/releases/posters/10292/bwemBFzo9wLYMQB5vsck9WKVxYy1N57J.webp")
        )
    }

    @Test
    fun latestList_andFullRelease_haveSamePoster() {
        val fromList = Fixtures.parseList<CollectionReleaseResponse>("releases_latest_limit_10.json")
            .first { it.id == 10292 }
            .map()
        val full = Fixtures.parse<CollectionReleaseResponse>("release_10292.json").map()

        assertEquals(full.poster, fromList.poster)
    }

    @Test
    fun catalogPage_mapsPagination() {
        val page = Fixtures.parse<CollectionReleasesResponse>("catalog_fresh_page_2.json")
            .toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE)

        assertEquals(10, page.data.size)
        assertEquals(2, page.page)
        assertFalse(page.isEnd())
    }

    @Test
    fun favoriteAdded_isPassedThrough() {
        val release = Fixtures.parse<CollectionReleaseResponse>("release_10292.json").map(favoriteAdded = true)

        assertTrue(release.favoriteInfo.isAdded)
    }
}

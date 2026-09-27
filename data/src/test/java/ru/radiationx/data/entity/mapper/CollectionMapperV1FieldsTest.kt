package ru.radiationx.data.entity.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.radiationx.data.entity.domain.release.ReleaseCollectionStats
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.V1FranchiseResponse
import ru.radiationx.data.entity.response.collection.V1ScheduleItemResponse

class CollectionMapperV1FieldsTest {

    private fun CollectionReleaseResponse.map() =
        toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE)

    @Test
    fun fullRelease_mapsExtraFields() {
        val release = Fixtures.parse<CollectionReleaseResponse>("v1/release_10292.json").map()

        assertEquals("16+", release.ageRating)
        assertEquals(24, release.averageEpisodeDurationMin)
        assertEquals(8.16, release.shikimoriRating!!, 0.0001)
        assertEquals(
            "https://www.anilibria.tv/storage/releases/background-covers/10292/fXB6YQNqKiteVM5CMoOL4bYCOwNYj5iX.jpg",
            release.backgroundCover
        )
    }

    @Test
    fun fullRelease_mapsRatingsAndCollectionStats() {
        val release = Fixtures.parse<CollectionReleaseResponse>("v1/release_10292.json").map()

        assertEquals(5921, release.shikimoriVotes)
        assertEquals(8.3, release.malRating!!, 0.0001)
        assertEquals(70566, release.malVotes)
        // rating.average == null, votes == 0 на сервере
        assertNull(release.ownRatingAverage)
        assertEquals(0, release.ownRatingVotes)
        assertEquals(
            ReleaseCollectionStats(
                favorites = 7680,
                watching = 1062,
                planned = 675,
                watched = 301,
                postponed = 24,
                abandoned = 11,
            ),
            release.collectionStats
        )
    }

    @Test
    fun fullRelease_mapsEpisodePreviewAndDuration() {
        val episode = Fixtures.parse<CollectionReleaseResponse>("v1/release_10292.json").map()
            .episodes.first()

        assertEquals(
            "https://www.anilibria.tv/storage/releases/episodes/previews/10292/1/e0YkYSBtf9TBD4Sf__a62595733972b6431ed68d65d271c839.webp",
            episode.previewUrl
        )
        assertEquals(1434, episode.durationSec)
    }

    @Test
    fun latestList_withoutBackgroundCovers_isNull() {
        val releases = Fixtures.parseList<CollectionReleaseResponse>("v1/releases_latest_limit_10.json")
            .map { it.map() }

        assertTrue(releases.all { it.backgroundCover == null })
        assertTrue(releases.all { it.ageRating != null })
    }

    @Test
    fun franchise_mapsOrderedParts() {
        val franchises = Fixtures.parseList<V1FranchiseResponse>("v1/franchises_release_8789.json")
            .map { it.toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE, Fixtures.SITE) }

        assertEquals(1, franchises.size)
        val franchise = franchises.first()
        assertEquals("JUJUTSU KAISEN", franchise.nameEnglish)
        assertEquals(2020, franchise.firstYear)
        assertEquals(2026, franchise.lastYear)
        assertEquals(4, franchise.totalReleases)
        assertEquals(60, franchise.totalEpisodes)
        assertEquals(
            listOf(8789, 9313, 9470, 10088).map(::ReleaseId),
            franchise.releases.map { it.id }
        )
        assertEquals(listOf(1, 2, 3, 4), franchise.parts.map { it.sortOrder })
        assertTrue(franchise.releases.all { it.poster != null && it.year != null })
    }

    @Test
    fun schedule_parsesExtraFields() {
        val items = Fixtures.parseList<V1ScheduleItemResponse>("v1/schedule_week_sample.json")

        val published = items[0]
        assertEquals(8986, published.release.id)
        assertEquals(21, published.nextReleaseEpisodeNumber)
        assertEquals(false, published.fullSeasonIsReleased)
        assertEquals(20f, published.publishedReleaseEpisode?.ordinal)

        val notPublished = items[1]
        assertEquals(355, notPublished.nextReleaseEpisodeNumber)
        assertNull(notPublished.publishedReleaseEpisode)
    }
}

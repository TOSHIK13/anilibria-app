package ru.radiationx.data.entity.mapper

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.radiationx.data.entity.domain.types.YoutubeId
import ru.radiationx.data.entity.response.media.V1VideoResponse

class MediaMapperTest {

    private val videos = Fixtures
        .parseList<V1VideoResponse>("v1/media_videos_limit_10.json")
        .map { it.toDomain(Fixtures.apiUtils, Fixtures.IMAGES_BASE) }

    @Test
    fun video_mapsToYoutubeItem() {
        val video = videos.first()

        assertEquals(YoutubeId(1042), video.id)
        assertEquals("zU7DVkothk4", video.vid)
        assertEquals(1083, video.views)
        assertEquals(6, video.comments)
        // created_at 2026-09-24T18:00:20+00:00 == legacy youtube timestamp
        assertEquals(1790272820, video.timestamp)
        assertEquals("https://www.youtube.com/watch?v=zU7DVkothk4", video.link)
    }

    @Test
    fun video_imagePrefersOptimizedPreview() {
        assertEquals(
            "https://www.anilibria.tv/storage/media/videos/previews/1042/q9q7TP1iIO2XpLx1__6ab9b6bb9c2c7bbb60da673a11d16303.webp",
            videos.first().image
        )
    }

    @Test
    fun videos_keepServerOrder() {
        val times = videos.map { it.timestamp }
        assertEquals(times.sortedDescending(), times)
    }
}

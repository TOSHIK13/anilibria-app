package ru.radiationx.data.repository

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.YoutubeApi
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.youtube.YoutubeItem
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.system.ApiUtils
import javax.inject.Inject

class YoutubeRepository @Inject constructor(
    private val youtubeApi: YoutubeApi,
    private val apiUtils: ApiUtils,
    private val apiConfig: ApiConfig
) {

    companion object {
        private const val VIDEOS_CACHE_MS = 60_000L
    }

    private val videosMutex = Mutex()
    private var cachedVideos: List<YoutubeItem>? = null
    private var cachedVideosAt = 0L

    /**
     * V1 `/media/videos` без пагинации: первая страница — все доступные видео (до 50),
     * дальше пусто, а [Paginated.isEnd] сразу true, чтобы экраны не показывали "ещё".
     */
    suspend fun getYoutubeList(page: Int): Paginated<YoutubeItem> = withContext(Dispatchers.IO) {
        val items = if (page <= 1) getLatestVideos() else emptyList()
        Paginated(
            data = items,
            page = 1,
            allPages = 1,
            perPage = YoutubeApi.MAX_VIDEOS_LIMIT,
            allItems = items.size,
        )
    }

    /**
     * Последние видео из V1 `/media/videos` (максимум 50, без пагинации).
     * Лента и ряд YouTube грузятся одновременно, поэтому ответ делится и кэшируется ненадолго.
     */
    suspend fun getLatestVideos(): List<YoutubeItem> = withContext(Dispatchers.IO) {
        videosMutex.withLock {
            val cached = cachedVideos
            if (cached != null && SystemClock.elapsedRealtime() - cachedVideosAt < VIDEOS_CACHE_MS) {
                return@withLock cached
            }
            youtubeApi
                .getVideos(YoutubeApi.MAX_VIDEOS_LIMIT)
                .map { it.toDomain(apiUtils, apiConfig.baseImagesUrl) }
                .also {
                    cachedVideos = it
                    cachedVideosAt = SystemClock.elapsedRealtime()
                }
        }
    }
}

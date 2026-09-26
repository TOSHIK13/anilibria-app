package ru.radiationx.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.FeedApi
import ru.radiationx.data.entity.domain.feed.FeedItem
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.interactors.ReleaseUpdateMiddleware
import ru.radiationx.data.repository.feed.FeedMerger
import ru.radiationx.data.system.ApiUtils
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class FeedRepository @Inject constructor(
    private val feedApi: FeedApi,
    private val youtubeRepository: YoutubeRepository,
    private val updateMiddleware: ReleaseUpdateMiddleware,
    private val apiUtils: ApiUtils,
    private val apiConfig: ApiConfig
) {

    companion object {
        private const val PAGE_SIZE = 10
    }

    private val mutex = Mutex()

    private val merger = FeedMerger(PAGE_SIZE) { page -> loadFreshReleases(page) }

    suspend fun getFeed(page: Int): List<FeedItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (page <= 1 || !merger.isInitialized) {
                val (releases, videos) = coroutineScope {
                    val releases = async { loadLatestReleases() }
                    val videos = async {
                        // видео не должны ронять всю ленту
                        coRunCatching { youtubeRepository.getLatestVideos() }
                            .onFailure { Timber.e(it) }
                            .getOrDefault(emptyList())
                    }
                    releases.await() to videos.await()
                }
                merger.reset(releases, videos)
            }
            merger.getPage(page)
        }.also { updateMiddleware.handleFeed(it) }
    }

    private suspend fun loadLatestReleases(): List<Release> = feedApi
        .getLatestReleases(PAGE_SIZE)
        .map { it.toDomain(apiUtils, apiConfig) }

    private suspend fun loadFreshReleases(page: Int): List<Release> = feedApi
        .getFreshReleases(page, PAGE_SIZE)
        .data
        .map { it.toDomain(apiUtils, apiConfig) }
}

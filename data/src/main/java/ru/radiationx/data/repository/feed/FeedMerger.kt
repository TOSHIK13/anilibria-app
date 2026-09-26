package ru.radiationx.data.repository.feed

import ru.radiationx.data.entity.domain.feed.FeedItem
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.FeedId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.domain.youtube.YoutubeItem

/**
 * Собирает ленту как legacy `query=feed`: релизы и видео, отсортированные по времени
 * (release.torrentUpdate = fresh_at, youtube.timestamp = created_at) по убыванию,
 * страницы по [pageSize] элементов.
 *
 * Релизы догружаются страницами по [releasesPageSize] через [loadReleasesPage]
 * (страница 1 передаётся в [reset]). Видео — один список (v1 без пагинации):
 * когда видео кончаются, дальше идут только релизы.
 *
 * Не потокобезопасен: вызывать под Mutex.
 */
class FeedMerger(
    private val pageSize: Int = 10,
    private val loadReleasesPage: suspend (page: Int) -> List<Release>,
) {

    private val releases = ArrayDeque<Release>()
    private val videos = ArrayDeque<YoutubeItem>()
    private val items = mutableListOf<FeedItem>()
    private val emittedReleaseIds = mutableSetOf<ReleaseId>()
    private var nextReleasesPage = 2
    private var releasesEnded = false
    private var initialized = false

    val isInitialized: Boolean
        get() = initialized

    fun reset(firstReleasesPage: List<Release>, allVideos: List<YoutubeItem>) {
        releases.clear()
        videos.clear()
        items.clear()
        emittedReleaseIds.clear()
        releases.addAll(firstReleasesPage)
        videos.addAll(allVideos.sortedByDescending { it.timestamp })
        nextReleasesPage = 2
        releasesEnded = firstReleasesPage.isEmpty()
        initialized = true
    }

    suspend fun getPage(page: Int): List<FeedItem> {
        check(initialized) { "FeedMerger is not initialized" }
        val from = (page - 1).coerceAtLeast(0) * pageSize
        val to = from + pageSize
        while (items.size < to) {
            val next = nextItem() ?: break
            items.add(next)
        }
        if (from >= items.size) return emptyList()
        return items.subList(from, minOf(to, items.size)).toList()
    }

    private suspend fun nextItem(): FeedItem? {
        ensureReleases()
        val release = releases.firstOrNull()
        val video = videos.firstOrNull()
        return when {
            release == null && video == null -> null
            video == null || (release != null && release.torrentUpdate >= video.timestamp) -> {
                releases.removeFirst()
                emittedReleaseIds.add(release!!.id)
                FeedItem(FeedId(release.id, null), release, null)
            }

            else -> {
                videos.removeFirst()
                FeedItem(FeedId(null, video.id), null, video)
            }
        }
    }

    private suspend fun ensureReleases() {
        while (releases.isEmpty() && !releasesEnded) {
            val loaded = loadReleasesPage(nextReleasesPage)
            nextReleasesPage++
            if (loaded.isEmpty()) {
                releasesEnded = true
                return
            }
            // страницы каталога могут сдвинуться, если между запросами вышел новый релиз
            releases.addAll(loaded.filter { it.id !in emittedReleaseIds && releases.none { r -> r.id == it.id } })
        }
    }
}

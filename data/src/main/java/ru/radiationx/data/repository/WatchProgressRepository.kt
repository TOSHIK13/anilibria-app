package ru.radiationx.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.radiationx.data.datasource.remote.api.ViewsApi
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/** Сколько серий релиза досмотрено. [total] — null, если сервер не сообщил число серий. */
data class ReleaseWatchProgress(
    val watched: Int,
    val total: Int?,
)

/**
 * Прогресс просмотра по релизам для индикаторов на постерах.
 *
 * Источник — `views/history` (одна запись на серию с таймкодом), загружается целиком
 * раз в [REFRESH_TTL_MS]. Между загрузками обновляется локально из [EpisodeProgressRepository].
 */
class WatchProgressRepository @Inject constructor(
    private val viewsApi: ViewsApi,
    private val authRepository: AuthRepository,
) {

    private data class Entry(
        val episodes: Map<String, Boolean>,
        val total: Int?,
    ) {
        fun toProgress() = ReleaseWatchProgress(episodes.count { it.value }, total)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<ReleaseId, Entry>>(emptyMap())
    private var loadedAt = 0L
    private var useInclude = true

    fun observe(releaseId: ReleaseId): Flow<ReleaseWatchProgress?> = state
        .map { it[releaseId]?.toProgress() }
        .distinctUntilChanged()

    /** Загружает историю, если она ещё не загружена или устарела. Не блокирует вызывающего. */
    fun requestRefresh() {
        if (System.currentTimeMillis() - loadedAt < REFRESH_TTL_MS) return
        scope.launch {
            mutex.withLock {
                if (System.currentTimeMillis() - loadedAt < REFRESH_TTL_MS) return@withLock
                if (authRepository.getAuthState() != AuthState.AUTH) {
                    state.value = emptyMap()
                    return@withLock
                }
                coRunCatching { loadAll() }
                    .onSuccess {
                        state.value = it
                        loadedAt = System.currentTimeMillis()
                    }
                    .onFailure { Timber.e(it) }
            }
        }
    }

    fun clear() {
        state.value = emptyMap()
        loadedAt = 0L
    }

    fun onEpisodeChanged(releaseId: ReleaseId, serverId: String, isWatched: Boolean) {
        state.update { old ->
            val entry = old[releaseId] ?: Entry(emptyMap(), null)
            old + (releaseId to entry.copy(episodes = entry.episodes + (serverId to isWatched)))
        }
    }

    fun onEpisodeRemoved(releaseId: ReleaseId, serverId: String) {
        state.update { old ->
            val entry = old[releaseId] ?: return@update old
            val episodes = entry.episodes - serverId
            if (episodes.isEmpty()) old - releaseId else old + (releaseId to entry.copy(episodes = episodes))
        }
    }

    /** Полный набор таймкодов релиза (после загрузки или «отметить всё»). */
    fun onReleaseReplaced(releaseId: ReleaseId, episodes: Map<String, Boolean>) {
        state.update { old ->
            if (episodes.isEmpty()) return@update old - releaseId
            old + (releaseId to Entry(episodes, old[releaseId]?.total))
        }
    }

    private suspend fun loadAll(): Map<ReleaseId, Entry> {
        val episodesByRelease = mutableMapOf<ReleaseId, MutableMap<String, Boolean>>()
        val totals = mutableMapOf<ReleaseId, Int>()
        var page = 1
        while (page <= MAX_PAGES) {
            val response = fetchPage(page)
            val items = response.data.orEmpty()
            items.forEach { item ->
                val episodeId = item.releaseEpisodeId ?: return@forEach
                val rawReleaseId = item.releaseEpisode?.releaseId
                    ?: item.releaseEpisode?.release?.id
                    ?: return@forEach
                val releaseId = ReleaseId(rawReleaseId)
                episodesByRelease.getOrPut(releaseId) { mutableMapOf() }[episodeId] =
                    item.isWatched == true
                item.releaseEpisode?.release?.episodesTotal
                    ?.takeIf { it > 0 }
                    ?.also { totals[releaseId] = it }
            }
            val totalPages = response.meta?.pagination?.totalPages ?: page
            if (items.isEmpty() || page >= totalPages) break
            page++
        }
        return episodesByRelease.mapValues { (releaseId, episodes) ->
            Entry(episodes, totals[releaseId])
        }
    }

    // include сильно уменьшает ответ (без него приходят серия и релиз целиком).
    // Если сервер проигнорировал вложенные поля — один раз перезапрашиваем без include.
    private suspend fun fetchPage(page: Int) = if (useInclude) {
        val response = viewsApi.getHistory(page, PAGE_LIMIT, HISTORY_INCLUDE)
        val items = response.data.orEmpty()
        val hasReleaseIds = items.any { it.releaseEpisode?.releaseId != null }
        if (items.isNotEmpty() && !hasReleaseIds) {
            Timber.w("views/history ignored include, falling back to full response")
            useInclude = false
            viewsApi.getHistory(page, PAGE_LIMIT, null)
        } else {
            response
        }
    } else {
        viewsApi.getHistory(page, PAGE_LIMIT, null)
    }

    private companion object {
        const val PAGE_LIMIT = 50
        const val MAX_PAGES = 100
        const val REFRESH_TTL_MS = 10 * 60 * 1_000L
        const val HISTORY_INCLUDE =
            "is_watched,release_episode_id,release_episode.release_id,release_episode.release.episodes_total"
    }
}

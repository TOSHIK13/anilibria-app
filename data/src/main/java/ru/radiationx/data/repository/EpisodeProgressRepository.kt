package ru.radiationx.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import ru.radiationx.data.datasource.SuspendMutableStateFlow
import ru.radiationx.data.datasource.remote.api.ViewsApi
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.view.ViewTimecodeResponse
import ru.radiationx.data.repository.watch.PendingTimecode
import ru.radiationx.data.repository.watch.WatchHistoryEpisode
import ru.radiationx.data.repository.watch.WatchHistoryLogic
import ru.radiationx.data.system.HttpException
import ru.radiationx.data.tracker.AnimeTrackerRegistry
import ru.radiationx.data.tracker.TrackerEpisodeWatchedEvent
import ru.radiationx.data.tracker.TrackerReleaseRef
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.roundToLong

class EpisodeProgressRepository @Inject constructor(
    private val viewsApi: ViewsApi,
    private val authRepository: AuthRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val trackerRegistry: AnimeTrackerRegistry,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = SuspendMutableStateFlow<Map<String, ViewTimecodeResponse>> { emptyMap() }
    private val syncedReleases: MutableSet<ReleaseId> = ConcurrentHashMap.newKeySet()
    private val checkedEpisodes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        // Таймкоды из общей истории просмотра (диск/сервер) — без отдельных запросов по релизам.
        watchProgressRepository
            .observeEpisodes()
            .onEach { seedFromHistory(it) }
            .launchIn(scope)
    }

    fun observeAccesses(release: Release): Flow<List<EpisodeAccess>> = flow {
        refreshRelease(release)
        emitAll(cache.map { mapToAccesses(release, it) })
    }

    suspend fun getAccesses(release: Release): List<EpisodeAccess> {
        refreshRelease(release)
        return mapToAccesses(release, cache.getValue())
    }

    suspend fun getAccess(episode: Episode): EpisodeAccess? {
        refreshEpisode(episode)
        return mapToAccess(episode, cache.getValue()[episode.serverId])
    }

    suspend fun setAccessSeek(
        episode: Episode,
        seek: Long,
        duration: Long? = null,
    ) {
        setAccessSeek(episode.id, episode.serverId, seek, duration)
    }

    suspend fun setAccessSeek(
        episodeId: EpisodeId,
        serverId: String,
        seek: Long,
        duration: Long? = null,
        forceViewed: Boolean = false,
    ) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val current = cache.getValue()[serverId]
        val isWatched = forceViewed || (current?.isWatched == true)
        val time = seek.toDouble() / 1000.0
        // Кеш и история обновляются до запроса: позиция не теряется, даже если отправка не дошла
        // (тогда изменение остаётся в очереди и уйдёт при следующей синхронизации).
        putLocal(episodeId, serverId, time, isWatched)
        if (isWatched && current?.isWatched != true) {
            dispatchEpisodeWatched(episodeId)
        }
        watchProgressRepository.pushTimecodes(
            listOf(pendingUpdate(episodeId, serverId, time, isWatched))
        )
    }

    suspend fun importAccess(
        episode: Episode,
        access: EpisodeAccess,
    ) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val time = access.seek.toDouble() / 1000.0
        val wasWatched = cache.getValue()[episode.serverId]?.isWatched == true
        putLocal(episode.id, episode.serverId, time, access.isViewed)
        if (access.isViewed && !wasWatched) {
            dispatchEpisodeWatched(episode.id)
        }
        watchProgressRepository.pushTimecodes(
            listOf(pendingUpdate(episode.id, episode.serverId, time, access.isViewed))
        )
    }

    suspend fun markUnviewed(episode: Episode) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        cache.update { it - episode.serverId }
        checkedEpisodes += episode.serverId
        watchProgressRepository.onEpisodeRemoved(episode.id.releaseId, episode.serverId)
        watchProgressRepository.pushTimecodes(listOf(pendingDelete(episode)))
    }

    suspend fun markAllViewed(release: Release) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        if (release.episodes.isEmpty()) {
            return
        }
        val currentCache = cache.getValue()
        val times = release.episodes.map { currentCache[it.serverId]?.time?.toDouble() ?: 0.0 }
        val now = System.currentTimeMillis()
        val updatedAt = formatDateTime(now)
        cache.update { old ->
            old + release.episodes.mapIndexed { index, episode ->
                episode.serverId to ViewTimecodeResponse(
                    id = old[episode.serverId]?.id,
                    time = times[index].toFloat(),
                    userId = old[episode.serverId]?.userId,
                    isWatched = true,
                    updatedAt = updatedAt,
                    releaseEpisodeId = episode.serverId,
                )
            }
        }
        checkedEpisodes += release.episodes.map { it.serverId }
        syncedReleases += release.id
        watchProgressRepository.onReleaseReplaced(
            release.id,
            release.episodes.mapIndexed { index, episode ->
                WatchHistoryEpisode(
                    episodeId = episode.serverId,
                    releaseId = release.id.id,
                    ordinal = episode.id.id.toFloatOrNull(),
                    time = times[index].toFloat(),
                    isWatched = true,
                    updatedAt = now,
                )
            }
        )
        trackerRegistry.dispatchEpisodeWatched(
            TrackerEpisodeWatchedEvent(
                release = TrackerReleaseRef(release.id, release.shikimoriId, release.malId, release.title),
                episodeOrdinal = null,
                episodesWatched = release.episodes.size,
                episodesTotal = release.series?.toIntOrNull() ?: release.episodes.size,
            )
        )
        watchProgressRepository.pushTimecodes(
            release.episodes.mapIndexed { index, episode ->
                pendingUpdate(episode.id, episode.serverId, times[index], true, now)
            }
        )
    }

    suspend fun resetAccessHistory(release: Release) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        if (release.episodes.isEmpty()) {
            return
        }
        val ids = release.episodes.map { it.serverId }
        cache.update { old -> old - ids.toSet() }
        checkedEpisodes += ids
        syncedReleases += release.id
        watchProgressRepository.onReleaseReplaced(release.id, emptyList())
        watchProgressRepository.pushTimecodes(release.episodes.map { pendingDelete(it) })
    }

    private suspend fun putLocal(
        episodeId: EpisodeId,
        serverId: String,
        time: Double,
        isWatched: Boolean,
    ) {
        cache.update { old ->
            val current = old[serverId]
            old + (serverId to ViewTimecodeResponse(
                id = current?.id,
                time = time.toFloat(),
                userId = current?.userId,
                isWatched = isWatched,
                updatedAt = formatDateTime(System.currentTimeMillis()),
                releaseEpisodeId = serverId,
            ))
        }
        checkedEpisodes += serverId
        watchProgressRepository.onEpisodeChanged(
            releaseId = episodeId.releaseId,
            serverId = serverId,
            isWatched = isWatched,
            time = time.toFloat(),
            ordinal = episodeId.id.toFloatOrNull(),
        )
    }

    /** Внешним сервисам статистики (без привязанных сервисов — ничего не делает). */
    private fun dispatchEpisodeWatched(episodeId: EpisodeId) {
        val progress = watchProgressRepository.currentProgress(episodeId.releaseId)
        trackerRegistry.dispatchEpisodeWatched(
            TrackerEpisodeWatchedEvent(
                release = TrackerReleaseRef(episodeId.releaseId),
                episodeOrdinal = episodeId.id.toFloatOrNull(),
                episodesWatched = progress?.watched ?: 1,
                episodesTotal = progress?.total,
            )
        )
    }

    private fun pendingUpdate(
        episodeId: EpisodeId,
        serverId: String,
        time: Double,
        isWatched: Boolean,
        createdAt: Long = System.currentTimeMillis(),
    ) = PendingTimecode(
        episodeId = serverId,
        releaseId = episodeId.releaseId.id,
        time = time,
        isWatched = isWatched,
        ordinal = episodeId.id.toFloatOrNull(),
        createdAt = createdAt,
    )

    private fun pendingDelete(episode: Episode) = PendingTimecode(
        episodeId = episode.serverId,
        releaseId = episode.id.releaseId.id,
        time = null,
        createdAt = System.currentTimeMillis(),
    )

    /** Заполняет кэш из общей истории; более свежие локальные значения не перезаписываются. */
    private suspend fun seedFromHistory(episodes: Map<String, WatchHistoryEpisode>) {
        if (episodes.isEmpty()) return
        cache.update { old ->
            var result: MutableMap<String, ViewTimecodeResponse>? = null
            episodes.values.forEach { episode ->
                val current = old[episode.episodeId]
                if (current != null && parseDateTime(current.updatedAt) >= episode.updatedAt) {
                    return@forEach
                }
                val target = result ?: old.toMutableMap().also { result = it }
                target[episode.episodeId] = ViewTimecodeResponse(
                    id = current?.id,
                    time = episode.time ?: current?.time,
                    userId = current?.userId,
                    isWatched = episode.isWatched,
                    updatedAt = episode.updatedAt.takeIf { it > 0 }?.let(::formatDateTime)
                        ?: current?.updatedAt,
                    releaseEpisodeId = episode.episodeId,
                )
            }
            result ?: old
        }
        checkedEpisodes += episodes.keys
    }

    private suspend fun refreshRelease(release: Release) {
        if (!ensureServerSyncAllowed() || syncedReleases.contains(release.id)) {
            return
        }
        val response = viewsApi.getReleaseTimecodes(release.id.id)
        cache.update { old ->
            old + response.associateBy { it.releaseEpisodeId }
        }
        checkedEpisodes += release.episodes.map { it.serverId }
        syncedReleases += release.id
        val ordinals = release.episodes.associate { it.serverId to it.id.id.toFloatOrNull() }
        watchProgressRepository.onReleaseReplaced(
            release.id,
            response.map {
                WatchHistoryEpisode(
                    episodeId = it.releaseEpisodeId,
                    releaseId = release.id.id,
                    ordinal = ordinals[it.releaseEpisodeId],
                    time = it.time,
                    isWatched = it.isWatched == true,
                    updatedAt = parseDateTime(it.updatedAt),
                )
            },
        )
    }

    private suspend fun refreshEpisode(episode: Episode) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val episodeId = episode.serverId
        if (checkedEpisodes.contains(episodeId) || syncedReleases.contains(episode.id.releaseId)) {
            return
        }
        val response = runCatching {
            viewsApi.getEpisodeTimecode(episodeId)
        }.getOrElse { error ->
            if (error is HttpException && error.code == 404) {
                checkedEpisodes += episodeId
                return
            }
            throw error
        }
        cache.update { it + (episodeId to response) }
        checkedEpisodes += episodeId
    }

    private suspend fun ensureServerSyncAllowed(): Boolean {
        val allowed = authRepository.getAuthState() == ru.radiationx.data.entity.common.AuthState.AUTH
        if (!allowed) {
            resetLocalCache()
        }
        return allowed
    }

    private suspend fun resetLocalCache() {
        cache.setValue(emptyMap())
        syncedReleases.clear()
        checkedEpisodes.clear()
        watchProgressRepository.clear()
    }

    private fun mapToAccesses(
        release: Release,
        values: Map<String, ViewTimecodeResponse>,
    ): List<EpisodeAccess> {
        return release.episodes.mapNotNull { episode ->
            mapToAccess(episode, values[episode.serverId])
        }
    }

    private fun mapToAccess(
        episode: Episode,
        value: ViewTimecodeResponse?,
    ): EpisodeAccess? {
        value ?: return null
        return EpisodeAccess(
            id = episode.id,
            seek = ((value.time ?: 0f) * 1000f).roundToLong(),
            isViewed = value.isWatched == true,
            lastAccess = parseDateTime(value.updatedAt),
        )
    }

    private fun parseDateTime(value: String?): Long = WatchHistoryLogic.parseDate(value)

    private fun formatDateTime(value: Long): String = WatchHistoryLogic.formatDate(value)
}

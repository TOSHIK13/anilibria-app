package ru.radiationx.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import ru.radiationx.data.datasource.SuspendMutableStateFlow
import ru.radiationx.data.datasource.remote.api.ViewsApi
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.view.ViewTimecodeDeleteRequest
import ru.radiationx.data.entity.response.view.ViewTimecodeResponse
import ru.radiationx.data.entity.response.view.ViewTimecodeUpdateRequest
import ru.radiationx.data.system.HttpException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.math.roundToLong

class EpisodeProgressRepository @Inject constructor(
    private val viewsApi: ViewsApi,
    private val authRepository: AuthRepository,
) {

    private val cache = SuspendMutableStateFlow<Map<String, ViewTimecodeResponse>> { emptyMap() }
    private val syncedReleases = mutableSetOf<ReleaseId>()
    private val checkedEpisodes = mutableSetOf<String>()

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
        episodeId: ru.radiationx.data.entity.domain.types.EpisodeId,
        serverId: String,
        seek: Long,
        duration: Long? = null,
    ) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val current = cache.getValue()[serverId]
        val isWatched = (current?.isWatched == true) || duration
            ?.takeIf { it > 0 }
            ?.let { seek >= it * VIEWED_PROGRESS_THRESHOLD }
            ?: false
        val request = ViewTimecodeUpdateRequest(
            time = seek.toDouble() / 1000.0,
            isWatched = isWatched,
            releaseEpisodeId = serverId,
        )
        viewsApi.updateTimecodes(listOf(request))
        val updatedValue = ViewTimecodeResponse(
            id = current?.id,
            time = request.time.toFloat(),
            userId = current?.userId,
            isWatched = isWatched,
            updatedAt = formatDateTime(System.currentTimeMillis()),
            releaseEpisodeId = serverId,
        )
        cache.update { it + (serverId to updatedValue) }
        checkedEpisodes += serverId
    }

    suspend fun importAccess(
        episode: Episode,
        access: EpisodeAccess,
    ) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val request = ViewTimecodeUpdateRequest(
            time = access.seek.toDouble() / 1000.0,
            isWatched = access.isViewed,
            releaseEpisodeId = episode.serverId,
        )
        viewsApi.updateTimecodes(listOf(request))
        cache.update { old ->
            old + (episode.serverId to ViewTimecodeResponse(
                id = old[episode.serverId]?.id,
                time = request.time.toFloat(),
                userId = old[episode.serverId]?.userId,
                isWatched = request.isWatched,
                updatedAt = formatDateTime(System.currentTimeMillis()),
                releaseEpisodeId = request.releaseEpisodeId,
            ))
        }
        checkedEpisodes += episode.serverId
    }

    suspend fun markUnviewed(episode: Episode) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        viewsApi.deleteTimecodes(listOf(ViewTimecodeDeleteRequest(episode.serverId)))
        cache.update { it - episode.serverId }
        checkedEpisodes += episode.serverId
    }

    suspend fun markAllViewed(release: Release) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val currentCache = cache.getValue()
        val requests = release.episodes.map { episode ->
            ViewTimecodeUpdateRequest(
                time = currentCache[episode.serverId]?.time?.toDouble() ?: 0.0,
                isWatched = true,
                releaseEpisodeId = episode.serverId,
            )
        }
        if (requests.isEmpty()) {
            return
        }
        viewsApi.updateTimecodes(requests)
        val updatedAt = formatDateTime(System.currentTimeMillis())
        cache.update { old ->
            old + requests.associate { request ->
                request.releaseEpisodeId to ViewTimecodeResponse(
                    id = old[request.releaseEpisodeId]?.id,
                    time = request.time.toFloat(),
                    userId = old[request.releaseEpisodeId]?.userId,
                    isWatched = true,
                    updatedAt = updatedAt,
                    releaseEpisodeId = request.releaseEpisodeId,
                )
            }
        }
        checkedEpisodes += requests.map { it.releaseEpisodeId }
        syncedReleases += release.id
    }

    suspend fun resetAccessHistory(release: Release) {
        if (!ensureServerSyncAllowed()) {
            return
        }
        val requests = release.episodes.map { ViewTimecodeDeleteRequest(it.serverId) }
        if (requests.isEmpty()) {
            return
        }
        viewsApi.deleteTimecodes(requests)
        cache.update { old -> old - requests.map { it.releaseEpisodeId }.toSet() }
        checkedEpisodes += requests.map { it.releaseEpisodeId }
        syncedReleases += release.id
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

    private fun parseDateTime(value: String?): Long {
        value ?: return 0L
        return runCatching {
            createDateFormat().parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun formatDateTime(value: Long): String {
        return createDateFormat().format(Date(value))
    }

    private companion object {
        private const val VIEWED_PROGRESS_THRESHOLD = 0.9

        private fun createDateFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}

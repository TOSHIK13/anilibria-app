package ru.radiationx.data.interactors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.RandomRelease
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseCode
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.repository.EpisodeProgressRepository
import ru.radiationx.data.repository.ReleaseRepository
import javax.inject.Inject

/**
 * Created by radiationx on 17.02.18.
 */
class ReleaseInteractor @Inject constructor(
    private val releaseRepository: ReleaseRepository,
    private val episodeProgressRepository: EpisodeProgressRepository,
) {

    private val releaseItems = MutableStateFlow<List<Release>>(emptyList())
    private val releases = MutableStateFlow<List<Release>>(emptyList())

    private val sharedRequests = SharedRequests<RequestKey, Release>()

    suspend fun getRandomRelease(): RandomRelease = releaseRepository.getRandomRelease()

    private suspend fun loadRelease(releaseId: ReleaseId): Release {
        return releaseRepository.getRelease(releaseId).also(::updateFullCache)
    }

    private suspend fun loadRelease(releaseCode: ReleaseCode): Release {
        return releaseRepository.getRelease(releaseCode).also(::updateFullCache)
    }

    suspend fun loadRelease(
        releaseId: ReleaseId? = null,
        releaseCode: ReleaseCode? = null,
    ): Release {
        val key = RequestKey(releaseId, releaseCode)
        return sharedRequests.request(key) {
            when {
                releaseId != null -> loadRelease(releaseId)
                releaseCode != null -> loadRelease(releaseCode)
                else -> throw Exception("Unknown id=null or code=null")
            }
        }
    }

    fun getItem(releaseId: ReleaseId? = null, releaseCode: ReleaseCode? = null): Release? {
        return releaseItems.value.findRelease(releaseId, releaseCode)
    }

    suspend fun getFull(releaseId: ReleaseId? = null, releaseCode: ReleaseCode? = null): Release? {
        return observeFull(releaseId, releaseCode).firstOrNull()
    }

    fun observeItem(releaseId: ReleaseId? = null, releaseCode: ReleaseCode? = null): Flow<Release> {
        return releaseItems.mapNotNull { it.findRelease(releaseId, releaseCode) }
    }

    fun observeFull(releaseId: ReleaseId? = null, releaseCode: ReleaseCode? = null): Flow<Release> {
        return flow {
            emit(updateIfNotExists(releaseId, releaseCode))
        }.flatMapLatest {
            releases.mapNotNull { it.findRelease(releaseId, releaseCode) }
        }
    }

    fun updateItemsCache(items: List<Release>) {
        releaseItems.update { releaseItems ->
            releaseItems.filterNot { release ->
                items.any {
                    check(release, it.id, it.code)
                }
            } + items
        }
    }

    fun updateFullCache(release: Release) {
        releases.update { releases ->
            releases.filterNot {
                check(it, release.id, release.code)
            } + release
        }
    }

    suspend fun loadWithFranchises(releaseId: ReleaseId): List<Release> {
        val rootRelease = requireNotNull(getFull(releaseId)) {
            "Loaded release is null for $releaseId"
        }
        val rootReleaseIds = rootRelease.getFranchisesIds()
        if (rootReleaseIds.isEmpty()) {
            return listOf(rootRelease)
        }
        val idsToLoad = rootReleaseIds.filter { it != rootRelease.id }
        val franchiseReleases = releaseRepository.getFullReleasesById(idsToLoad)

        val allReleasesMap = mutableMapOf<ReleaseId, Release>()
        allReleasesMap[rootRelease.id] = rootRelease
        franchiseReleases.forEach {
            allReleasesMap[it.id] = it
        }
        return rootReleaseIds.mapNotNull { allReleasesMap[it] }
    }

    /* Common */
    fun observeAccesses(releaseId: ReleaseId): Flow<List<EpisodeAccess>> {
        return observeFull(releaseId = releaseId).flatMapLatest { release ->
            episodeProgressRepository.observeAccesses(release)
        }
    }

    suspend fun getAccesses(releaseId: ReleaseId): List<EpisodeAccess> {
        val release = getFull(releaseId = releaseId) ?: return emptyList()
        return episodeProgressRepository.getAccesses(release)
    }

    suspend fun getAccess(id: EpisodeId): EpisodeAccess? {
        val release = getFull(releaseId = id.releaseId) ?: return null
        val episode = release.episodes.find { it.id == id } ?: return null
        return episodeProgressRepository.getAccess(episode)
    }

    suspend fun resetAccessHistory(releaseId: ReleaseId) {
        val release = getFull(releaseId = releaseId) ?: return
        episodeProgressRepository.resetAccessHistory(release)
    }

    suspend fun markAllViewed(id: ReleaseId) {
        val release = getFull(releaseId = id) ?: return
        episodeProgressRepository.markAllViewed(release)
    }

    suspend fun markUnViewed(id: EpisodeId) {
        val release = getFull(releaseId = id.releaseId) ?: return
        val episode = release.episodes.find { it.id == id } ?: return
        episodeProgressRepository.markUnviewed(episode)
    }

    suspend fun setAccessSeek(id: EpisodeId, seek: Long, duration: Long? = null) {
        val release = getFull(releaseId = id.releaseId) ?: return
        val episode = release.episodes.find { it.id == id } ?: return
        episodeProgressRepository.setAccessSeek(episode, seek, duration)
    }

    suspend fun setAccessSeek(
        id: EpisodeId,
        seek: Long,
        duration: Long? = null,
        forceViewed: Boolean,
    ) {
        val release = getFull(releaseId = id.releaseId) ?: return
        val episode = release.episodes.find { it.id == id } ?: return
        episodeProgressRepository.setAccessSeek(
            episode.id,
            episode.serverId,
            seek,
            duration,
            forceViewed = forceViewed,
        )
    }

    suspend fun setAccessSeek(
        id: EpisodeId,
        serverId: String,
        seek: Long,
        duration: Long? = null,
        forceViewed: Boolean = false,
    ) {
        episodeProgressRepository.setAccessSeek(id, serverId, seek, duration, forceViewed)
    }

    suspend fun importAccess(access: EpisodeAccess) {
        val release = getFull(releaseId = access.id.releaseId) ?: return
        val episode = release.episodes.find { it.id == access.id } ?: return
        episodeProgressRepository.importAccess(episode, access)
    }

    private suspend fun updateIfNotExists(
        releaseId: ReleaseId? = null,
        releaseCode: ReleaseCode? = null,
    ) {
        val release = releases.value.findRelease(releaseId, releaseCode)
        if (release != null) {
            return
        }
        runCatching {
            loadRelease(releaseId, releaseCode)
        }
    }

    private fun List<Release>.findRelease(id: ReleaseId?, code: ReleaseCode?): Release? = find {
        check(it, id, code)
    }

    private fun check(release: Release, id: ReleaseId?, code: ReleaseCode?): Boolean {
        val foundById = id != null && release.id == id
        val foundByCode = code != null && release.code == code
        return foundById || foundByCode
    }

    data class RequestKey(
        val id: ReleaseId?,
        val code: ReleaseCode?,
    )
}

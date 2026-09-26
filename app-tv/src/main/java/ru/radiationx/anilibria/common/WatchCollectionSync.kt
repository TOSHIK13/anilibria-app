package ru.radiationx.anilibria.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Автоматический перенос релиза между коллекциями по факту просмотра.
 *
 * Автоматика двигает релиз только вперёд:
 * (нет) / Запланировано / Отложено → Смотрю → Просмотрено.
 * «Брошено» не трогается никогда.
 */
class WatchCollectionSync @Inject constructor(
    private val authRepository: AuthRepository,
    private val collectionRepository: CollectionRepository,
    private val releaseInteractor: ReleaseInteractor,
) {

    private val mutex = Mutex()

    /** Воспроизведение реально началось (≥ 30 сек). */
    suspend fun onPlaybackStarted(releaseId: ReleaseId) = update(releaseId, "playback_started") {
        when (it) {
            null,
            CollectionType.PLANNED,
            CollectionType.POSTPONED -> CollectionType.WATCHING

            else -> it
        }
    }

    /** Серия отмечена просмотренной. */
    suspend fun onEpisodeWatched(releaseId: ReleaseId) = moveToWatchedIfCompleted(releaseId)

    /** «Отметить всё как просмотренные». */
    suspend fun onAllMarkedViewed(releaseId: ReleaseId) = moveToWatchedIfCompleted(releaseId)

    /** «Сбросить историю просмотров». */
    suspend fun onHistoryReset(releaseId: ReleaseId) = update(releaseId, "history_reset") {
        when (it) {
            CollectionType.WATCHING,
            CollectionType.WATCHED -> null

            else -> it
        }
    }

    private suspend fun moveToWatchedIfCompleted(releaseId: ReleaseId) {
        if (!isAuthorized()) return
        val completed = coRunCatching { isFullyWatched(releaseId) }
            .onFailure { Timber.e(it) }
            .getOrDefault(false)
        if (!completed) return
        update(releaseId, "fully_watched") {
            when (it) {
                CollectionType.ABANDONED,
                CollectionType.WATCHED -> it

                else -> CollectionType.WATCHED
            }
        }
    }

    // Онгоинг остаётся в «Смотрю», даже если досмотрены все вышедшие серии.
    private suspend fun isFullyWatched(releaseId: ReleaseId): Boolean {
        val release = releaseInteractor.getFull(releaseId = releaseId) ?: return false
        if (release.statusCode != Release.STATUS_CODE_COMPLETE) return false
        if (release.episodes.isEmpty()) return false
        val watchedIds = releaseInteractor
            .getAccesses(releaseId)
            .filter { it.isViewed }
            .map { it.id }
            .toSet()
        return release.episodes.all { it.id in watchedIds }
    }

    private suspend fun update(
        releaseId: ReleaseId,
        reason: String,
        transform: (CollectionType?) -> CollectionType?,
    ) {
        if (!isAuthorized()) return
        mutex.withLock {
            coRunCatching {
                val current = collectionRepository.getReleaseCollection(releaseId)
                val target = transform(current)
                if (target != current) {
                    Timber.d("collection-sync reason=$reason release=${releaseId.id} $current -> $target")
                    collectionRepository.setReleaseCollection(releaseId, target)
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private suspend fun isAuthorized(): Boolean =
        authRepository.getAuthState() == AuthState.AUTH
}

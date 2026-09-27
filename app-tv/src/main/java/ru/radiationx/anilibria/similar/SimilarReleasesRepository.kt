package ru.radiationx.anilibria.similar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import timber.log.Timber
import javax.inject.Inject

/**
 * «Похожие» для экрана деталей: один общий поток на релиз (ряды AniList/Shikimori/MAL и
 * DetailsViewModel подписаны на него же, запросы к сервисам не дублируются).
 * MAL id берётся из полного релиза (V1 `mal.id` / `shikimori.id`).
 */
class SimilarReleasesRepository @Inject constructor(
    private val source: SimilarReleasesSource,
    private val releaseInteractor: ReleaseInteractor,
) {

    private companion object {
        const val MAX_FLOWS = 8
        const val STOP_TIMEOUT_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val flows = object : LinkedHashMap<ReleaseId, SharedFlow<SimilarData>>(MAX_FLOWS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReleaseId, SharedFlow<SimilarData>>?) =
            size > MAX_FLOWS
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(releaseId: ReleaseId): Flow<SimilarData> = synchronized(flows) {
        flows.getOrPut(releaseId) {
            releaseInteractor
                .observeFull(releaseId)
                .mapNotNull { it.malId }
                .distinctUntilChanged()
                .flatMapLatest { malId -> source.observe(releaseId, malId) }
                .catch { Timber.w(it, "similar: observe $releaseId") }
                .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
        }
    }

    suspend fun loadMore(releaseId: ReleaseId, source: SimilarSource): Boolean =
        this.source.loadMore(releaseId, source)
}

package ru.radiationx.data.tracker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.radiationx.data.external.RemoteOrigin
import ru.radiationx.data.repository.ReleaseRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/** Сервис и его текущее состояние (для экрана настроек). */
data class TrackerEntry(
    val tracker: AnimeTracker,
    val state: TrackerState,
)

/**
 * Реестр внешних сервисов статистики ([AnimeTracker]) и диспетчер событий просмотра/коллекций.
 *
 * Сейчас единственный сервис — AniList. Пока он не привязан (или вход истёк, очередь — этап 3),
 * `dispatch*` ничего не загружают и не отправляют; без сервисов ([trackers] пуст) возвращаются сразу, в настройках нет
 * строк сервисов. Чтобы подключить сервис — добавить его в [trackers] (через конструктор).
 *
 * События рассылаются асинхронно в собственном scope: вызывающий код (плеер, коллекции) не
 * ждёт сеть сервисов, а ошибки сервисов только логируются и не ломают основной поток.
 */
class AnimeTrackerRegistry @Inject constructor(
    private val releaseRepository: ReleaseRepository,
    aniListTracker: AniListTracker,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Все известные приложению сервисы (привязанные и нет). */
    val trackers: List<AnimeTracker> = listOf(aniListTracker)

    /** Сервисы, которые можно подключить сейчас (не привязаны). */
    val availableToLink: Flow<List<AnimeTracker>>
        get() = observeEntries().map { entries ->
            entries.filter { it.state is TrackerState.NotLinked }.map { it.tracker }
        }

    /** Состояния всех сервисов в порядке [trackers]. */
    fun observeEntries(): Flow<List<TrackerEntry>> {
        if (trackers.isEmpty()) return flowOf(emptyList())
        val flows = trackers.map { tracker ->
            tracker.observeState().map { TrackerEntry(tracker, it) }
        }
        return combine(flows) { it.toList() }
    }

    /** Привязанные сервисы (включая сервисы в состоянии ошибки — их можно отвязать). */
    fun observeLinked(): Flow<List<TrackerEntry>> = observeEntries().map { entries ->
        entries.filter { it.state !is TrackerState.NotLinked }
    }

    suspend fun dispatchEpisodeWatched(event: TrackerEpisodeWatchedEvent) {
        if (RemoteOrigin.isActive()) return // изменение пришло из внешнего сервиса: обратно не отправляем
        if (trackers.isEmpty()) return
        scope.launch {
            val linked = linkedTrackers()
            if (linked.isEmpty()) return@launch
            val resolved = event.copy(release = resolveIds(event.release))
            linked.forEach { tracker ->
                coRunCatching { tracker.onEpisodeWatched(resolved) }
                    .onFailure { Timber.w(it, "tracker ${tracker.id}: episode watched not sent") }
            }
        }
    }

    suspend fun dispatchCollectionChanged(event: TrackerCollectionChangedEvent) {
        if (RemoteOrigin.isActive()) return
        if (trackers.isEmpty()) return
        scope.launch {
            val linked = linkedTrackers()
            if (linked.isEmpty()) return@launch
            val resolved = event.copy(release = resolveIds(event.release))
            linked.forEach { tracker ->
                coRunCatching { tracker.onCollectionChanged(resolved) }
                    .onFailure { Timber.w(it, "tracker ${tracker.id}: collection change not sent") }
            }
        }
    }

    private suspend fun linkedTrackers(): List<AnimeTracker> = trackers.filter { tracker ->
        coRunCatching { tracker.observeState().first().let { it is TrackerState.Linked || it is TrackerState.Expiring || it is TrackerState.Expired } }
            .onFailure { Timber.w(it, "tracker ${tracker.id}: state unavailable") }
            .getOrDefault(false)
    }

    /** Дозагружает id Shikimori/MAL и название, если вызывающий их не знал (плеер знает только серию). */
    private suspend fun resolveIds(ref: TrackerReleaseRef): TrackerReleaseRef {
        if (ref.hasExternalIds && ref.title != null) return ref
        return coRunCatching { releaseRepository.getRelease(ref.releaseId) }
            .map { ref.copy(shikimoriId = ref.shikimoriId ?: it.shikimoriId, malId = ref.malId ?: it.malId, title = ref.title ?: it.title) }
            .onFailure { Timber.w(it, "tracker: release ${ref.releaseId.id} ids not loaded") }
            .getOrDefault(ref)
    }
}

package ru.radiationx.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.radiationx.data.datasource.storage.ReleaseCardStorage
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.watch.ReleaseCardCacheLogic
import ru.radiationx.data.repository.watch.ReleaseCardInfo
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Результат [ReleaseCardRepository.getCards]. [fromNetwork] — пришлось ждать сеть. */
data class ReleaseCards(
    val items: Map<ReleaseId, ReleaseCardInfo>,
    val fromNetwork: Boolean,
)

/**
 * Краткие данные релизов для карточек «Продолжить просмотр»: память → диск → сеть.
 *
 * Ряд рисуется из кэша без ожидания сети, а данные обновляются в фоне лёгким запросом
 * `releases/list?include=` (раз за процесс на релиз); если что-то поменялось —
 * [observeUpdates] просит перестроить ряд.
 *
 * В общий кэш [ReleaseInteractor] кладутся те же краткие релизы, что и из ленты:
 * детали всё равно грузят полный релиз отдельно (observeFull).
 */
class ReleaseCardRepository @Inject constructor(
    private val storage: ReleaseCardStorage,
    private val releaseRepository: ReleaseRepository,
    private val releaseInteractor: ReleaseInteractor,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskMutex = Mutex()
    private var disk: List<ReleaseCardInfo>? = null

    /** Релизы, данные которых в этом процессе уже пришли с сервера. */
    private val refreshed: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** releaseId → serverId серии из истории (какие серии хранить на диске). */
    private val focus = ConcurrentHashMap<Int, String>()

    @Volatile
    private var refreshJob: Job? = null

    private val updates = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Фоновое обновление изменило данные карточек. */
    fun observeUpdates(): Flow<Unit> = updates

    /**
     * @param focusEpisodes серия из истории по релизу (serverId): на диске у релиза
     * сохраняются только она и следующая (превью/длительность для карточки).
     */
    suspend fun getCards(
        ids: List<ReleaseId>,
        focusEpisodes: Map<ReleaseId, String> = emptyMap(),
    ): ReleaseCards {
        focusEpisodes.forEach { (id, episodeId) -> focus[id.id] = episodeId }
        val result = LinkedHashMap<ReleaseId, ReleaseCardInfo>()
        val cached = getDisk().associateBy { it.id }
        val fromMemory = ids.mapNotNull { releaseInteractor.getItem(releaseId = it) }
        fromMemory.forEach {
            result[it.id] = ReleaseCardCacheLogic.withEpisodesFrom(
                ReleaseCardCacheLogic.fromRelease(it),
                cached[it.id.id]
            )
            // Релиз из ленты приходит без серий — их (превью, длительность) догрузит фоновое обновление.
            if (it.episodes.isNotEmpty()) refreshed.add(it.id.id)
        }
        ids.forEach { id ->
            if (id !in result) cached[id.id]?.also { result[id] = it }
        }

        val missing = ids.filter { it !in result }
        var loaded = emptyList<Release>()
        if (missing.isNotEmpty()) {
            coRunCatching { load(missing) }
                .onSuccess { releases ->
                    loaded = releases
                    releases.forEach { result[it.id] = ReleaseCardCacheLogic.fromRelease(it) }
                }
                .onFailure {
                    // Без части карточек лучше, чем без ряда совсем.
                    if (result.isEmpty()) throw it
                    Timber.e(it)
                }
        }
        persist(fromMemory + loaded, ids)
        requestRefresh(ids)
        return ReleaseCards(
            items = ids.mapNotNull { id -> result[id]?.let { id to it } }.toMap(),
            fromNetwork = missing.isNotEmpty(),
        )
    }

    private fun requestRefresh(ids: List<ReleaseId>) {
        if (refreshJob?.isActive == true) return
        val toRefresh = ReleaseCardCacheLogic.toRefresh(ids.map { it.id }, refreshed)
        if (toRefresh.isEmpty()) return
        refreshJob = scope.launch {
            // Не мешаем первым запросам экрана: ряд уже нарисован из кэша.
            delay(REFRESH_DELAY_MS)
            val start = LoadTiming.now()
            coRunCatching { load(toRefresh.map { ReleaseId(it) }) }
                .onSuccess { releases ->
                    val before = getDisk().associateBy { it.id }
                    val fresh = releases.map { toDisk(ReleaseCardCacheLogic.fromRelease(it), before) }
                    val changed = fresh.any { before[it.id] != it }
                    persist(releases, ids)
                    LoadTiming.span(
                        "watch", "cards refreshed", start,
                        "items=${releases.size} changed=$changed"
                    )
                    if (changed) updates.tryEmit(Unit)
                }
                .onFailure {
                    Timber.e(it)
                    LoadTiming.span("watch", "cards refresh error", start, it.javaClass.simpleName)
                }
        }
    }

    private suspend fun load(ids: List<ReleaseId>): List<Release> {
        val releases = releaseRepository.getShortReleasesById(ids)
        releaseInteractor.updateItemsCache(releases)
        releases.forEach { refreshed.add(it.id.id) }
        return releases
    }

    private fun toDisk(info: ReleaseCardInfo, old: Map<Int, ReleaseCardInfo>): ReleaseCardInfo =
        ReleaseCardCacheLogic.trimEpisodes(
            ReleaseCardCacheLogic.withEpisodesFrom(info, old[info.id]),
            focus[info.id]
        )

    private suspend fun getDisk(): List<ReleaseCardInfo> {
        disk?.also { return it }
        return diskMutex.withLock {
            disk ?: run {
                val start = LoadTiming.now()
                storage.get().also {
                    disk = it
                    LoadTiming.span("watch", "cards restored", start, "items=${it.size}")
                }
            }
        }
    }

    private suspend fun persist(releases: List<Release>, priority: List<ReleaseId>) {
        diskMutex.withLock {
            val old = disk ?: storage.get()
            val oldById = old.associateBy { it.id }
            val merged = ReleaseCardCacheLogic.merge(
                old = old,
                fresh = releases.map { toDisk(ReleaseCardCacheLogic.fromRelease(it), oldById) },
                priority = priority.map { it.id },
                max = MAX_CACHED,
            )
            disk = merged
            if (merged != old) storage.save(merged)
        }
    }

    private companion object {
        const val MAX_CACHED = 50
        const val REFRESH_DELAY_MS = 3_000L
    }
}

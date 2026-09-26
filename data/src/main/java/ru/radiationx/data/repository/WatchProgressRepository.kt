package ru.radiationx.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ru.radiationx.data.datasource.remote.api.ViewsApi
import ru.radiationx.data.datasource.storage.WatchHistoryStorage
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.view.ViewHistoryItemResponse
import ru.radiationx.data.entity.response.view.ViewHistoryPageResponse
import ru.radiationx.data.entity.response.view.ViewTimecodeDeleteRequest
import ru.radiationx.data.entity.response.view.ViewTimecodeUpdateRequest
import ru.radiationx.data.repository.watch.ContinueWatchingItem
import ru.radiationx.data.repository.watch.PendingTimecode
import ru.radiationx.data.repository.watch.WatchHistoryEpisode
import ru.radiationx.data.repository.watch.WatchHistoryLogic
import ru.radiationx.data.repository.watch.WatchHistorySnapshot
import ru.radiationx.data.system.HttpException
import ru.radiationx.data.system.LoadTiming
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.min

/** Сколько серий релиза досмотрено. [total] — null, если сервер не сообщил число серий. */
data class ReleaseWatchProgress(
    val watched: Int,
    val total: Int?,
)

/**
 * Серверная история просмотра (`views/history`) с дисковым кэшем.
 *
 * - при старте сразу публикуется снимок с диска, затем в фоне грузится свежий с сервера
 *   (первая страница, остальные параллельно), не чаще раза в [REFRESH_TTL_MS];
 * - даёт прогресс по релизам (индикаторы на постерах) и список «Продолжить просмотр»;
 * - между загрузками обновляется локально из [EpisodeProgressRepository];
 * - хранит очередь неотправленных таймкодов и дожимает её при следующей отправке/синхронизации;
 * - при выходе из аккаунта всё стирается.
 */
class WatchProgressRepository @Inject constructor(
    private val viewsApi: ViewsApi,
    private val authRepository: AuthRepository,
    private val storage: WatchHistoryStorage,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val restoreMutex = Mutex()
    private val pushMutex = Mutex()

    /** Серии по serverId. null — данных ещё нет (не восстановлено с диска и не загружено). */
    private val state = MutableStateFlow<Map<String, WatchHistoryEpisode>?>(null)

    private val progressState: StateFlow<Map<ReleaseId, ReleaseWatchProgress>> = state
        .map { WatchHistoryLogic.progress(it?.values.orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    @Volatile
    private var restored = false

    @Volatile
    private var loadedAt = 0L
    private var failedAt = 0L

    @Volatile
    private var useInclude = true

    @Volatile
    private var refreshJob: Job? = null

    private var persistJob: Job? = null
    private var lastAuth: AuthState? = null

    init {
        scope.launch { ensureRestored() }
        authRepository
            .observeAuthState()
            .onEach { auth ->
                val wasAuth = lastAuth == AuthState.AUTH
                lastAuth = auth
                if (auth == AuthState.AUTH) {
                    if (!wasAuth) {
                        loadedAt = 0L
                        requestRefresh()
                    }
                } else if (wasAuth || state.value != null) {
                    clear()
                }
            }
            .launchIn(scope)
    }

    fun observe(releaseId: ReleaseId): Flow<ReleaseWatchProgress?> = progressState
        .map { it[releaseId] }
        .distinctUntilChanged()

    /** Все известные серии с таймкодами (для заполнения кэша таймкодов). */
    fun observeEpisodes(): Flow<Map<String, WatchHistoryEpisode>> = state.filterNotNull()

    /** «Продолжить просмотр»: null — данных ещё нет, пусто — без авторизации или нечего продолжать. */
    fun observeContinueList(): Flow<List<ContinueWatchingItem>?> = flow {
        ensureRestored()
        requestRefresh()
        emitAll(state.map { episodes -> episodes?.let { WatchHistoryLogic.continueList(it.values) } })
    }.distinctUntilChanged()

    /**
     * Текущий список «Продолжить просмотр». Если данных ещё нет совсем (первый запуск после входа),
     * ждёт загрузки с сервера, иначе сразу отдаёт кэш и обновляет его в фоне.
     */
    suspend fun getContinueList(): List<ContinueWatchingItem> {
        ensureRestored()
        if (authRepository.getAuthState() != AuthState.AUTH) return emptyList()
        if (state.value == null) {
            refresh()
        } else {
            requestRefresh()
        }
        return WatchHistoryLogic.continueList(state.value.orEmpty().values)
    }

    /** Загружает историю, если она ещё не загружена или устарела. Не блокирует вызывающего. */
    fun requestRefresh() {
        if (System.currentTimeMillis() - loadedAt < REFRESH_TTL_MS) return
        if (System.currentTimeMillis() - failedAt < RETRY_BACKOFF_MS) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch { refresh() }
    }

    fun clear() {
        state.value = null
        restored = false
        loadedAt = 0L
        failedAt = 0L
        useInclude = true
        persistJob?.cancel()
        storage.clear()
    }

    fun onEpisodeChanged(
        releaseId: ReleaseId,
        serverId: String,
        isWatched: Boolean,
        time: Float? = null,
        ordinal: Float? = null,
    ) {
        val episode = WatchHistoryEpisode(
            episodeId = serverId,
            releaseId = releaseId.id,
            ordinal = ordinal,
            time = time,
            isWatched = isWatched,
            updatedAt = System.currentTimeMillis(),
        )
        state.update { old ->
            val map = old.orEmpty()
            map + (serverId to WatchHistoryLogic.mergeEpisode(map[serverId], episode))
        }
        persistLater()
    }

    fun onEpisodeRemoved(releaseId: ReleaseId, serverId: String) {
        state.update { old -> old?.let { it - serverId } }
        persistLater()
    }

    /** Полный набор таймкодов релиза (после загрузки или «отметить всё»/«сбросить»). */
    fun onReleaseReplaced(releaseId: ReleaseId, episodes: List<WatchHistoryEpisode>) {
        state.update { old ->
            val map = old.orEmpty()
            val oldOfRelease = map.values.filter { it.releaseId == releaseId.id }
            val total = oldOfRelease.mapNotNull { it.episodesTotal }.maxOrNull()
            val replaced = episodes.associate { episode ->
                val merged = WatchHistoryLogic.mergeEpisode(map[episode.episodeId], episode)
                episode.episodeId to merged.copy(episodesTotal = merged.episodesTotal ?: total)
            }
            map - oldOfRelease.map { it.episodeId }.toSet() + replaced
        }
        persistLater()
    }

    /**
     * Отправляет изменения таймкодов. Сначала дожимает очередь неотправленных.
     * Если отправка не удалась — изменения попадают в очередь, исключение пробрасывается.
     */
    suspend fun pushTimecodes(ops: List<PendingTimecode>) {
        if (ops.isEmpty()) return
        pushMutex.withLock {
            flushPendingLocked()
            try {
                send(ops)
            } catch (ex: Throwable) {
                storage.savePending(WatchHistoryLogic.mergePending(storage.getPending(), ops))
                throw ex
            }
            val queue = storage.getPending()
            if (queue.isNotEmpty()) {
                storage.savePending(WatchHistoryLogic.dropSent(queue, ops))
            }
        }
    }

    suspend fun flushPending() {
        pushMutex.withLock { flushPendingLocked() }
    }

    private suspend fun flushPendingLocked() {
        val queue = storage.getPending()
        if (queue.isEmpty()) return
        val now = System.currentTimeMillis()
        val fresh = queue.filter { now - it.createdAt < PENDING_MAX_AGE_MS }
        coRunCatching { send(fresh) }
            .onSuccess {
                storage.savePending(WatchHistoryLogic.dropSent(storage.getPending(), queue))
            }
            .onFailure { error ->
                Timber.w(error, "pending timecodes not sent: ${fresh.size}")
                // Постоянные ошибки (серия удалена и т.п.) не должны блокировать очередь навсегда.
                val permanent = error is HttpException &&
                        error.code in 400..499 && error.code !in listOf(401, 408, 429)
                val keep = if (permanent) emptyList() else fresh
                storage.savePending(
                    WatchHistoryLogic.mergePending(
                        WatchHistoryLogic.dropSent(storage.getPending(), queue),
                        keep
                    )
                )
            }
    }

    private suspend fun send(ops: List<PendingTimecode>) {
        val updates = ops.filterNot { it.isDelete }.map {
            ViewTimecodeUpdateRequest(
                time = it.time ?: 0.0,
                isWatched = it.isWatched,
                releaseEpisodeId = it.episodeId,
            )
        }
        val deletes = ops.filter { it.isDelete }.map { ViewTimecodeDeleteRequest(it.episodeId) }
        if (updates.isNotEmpty()) viewsApi.updateTimecodes(updates)
        if (deletes.isNotEmpty()) viewsApi.deleteTimecodes(deletes)
    }

    private suspend fun ensureRestored() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            // restored выставляется только после чтения снимка: иначе параллельный вызов
            // (ряд «Продолжить просмотр») проскакивает мимо мьютекса с пустым state и ждёт сервер.
            try {
                restoreLocked()
            } finally {
                restored = true
            }
        }
    }

    private suspend fun restoreLocked() {
        if (authRepository.getAuthState() != AuthState.AUTH) return
        val start = LoadTiming.now()
        val snapshot = storage.getSnapshot() ?: return
        val fromDisk = WatchHistoryLogic.applyPending(
            snapshot.episodes.associateBy { it.episodeId },
            storage.getPending()
        )
        state.update { current ->
            // Локальные изменения, сделанные до восстановления, важнее дискового снимка.
            fromDisk + current.orEmpty().mapValues { (id, episode) ->
                WatchHistoryLogic.mergeEpisode(fromDisk[id], episode)
            }
        }
        LoadTiming.span("watch", "history restored", start, "items=${snapshot.episodes.size}")
    }

    private suspend fun refresh() {
        refreshMutex.withLock {
            if (System.currentTimeMillis() - loadedAt < REFRESH_TTL_MS) return
            ensureRestored()
            if (authRepository.getAuthState() != AuthState.AUTH) return
            flushPending()
            val startedAt = System.currentTimeMillis()
            val timingStart = LoadTiming.now()
            coRunCatching { loadAll() }
                .onSuccess { server ->
                    if (authRepository.getAuthState() != AuthState.AUTH) return
                    val now = System.currentTimeMillis()
                    val merged = WatchHistoryLogic.applyPending(
                        WatchHistoryLogic.mergeServer(server, state.value.orEmpty(), startedAt),
                        storage.getPending()
                    )
                    state.value = merged
                    loadedAt = now
                    failedAt = 0L
                    restored = true
                    storage.saveSnapshot(WatchHistorySnapshot(merged.values.toList(), now))
                    LoadTiming.span("watch", "history loaded", timingStart, "items=${server.size}")
                }
                .onFailure {
                    // Не долбим сервер повтором с каждой карточки: пауза перед следующей попыткой.
                    failedAt = System.currentTimeMillis()
                    Timber.e(it)
                    LoadTiming.span("watch", "history error", timingStart, it.javaClass.simpleName)
                }
        }
    }

    private fun persistLater() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DELAY_MS)
            val episodes = state.value ?: return@launch
            if (authRepository.getAuthState() != AuthState.AUTH) return@launch
            storage.saveSnapshot(WatchHistorySnapshot(episodes.values.toList(), loadedAt))
        }
    }

    private suspend fun loadAll(): List<WatchHistoryEpisode> = coroutineScope {
        val first = fetchPage(1, detectInclude = true)
        val items = first.data.orEmpty().toMutableList<ViewHistoryItemResponse>()
        val totalPages = first.meta?.pagination?.totalPages
        if (totalPages != null) {
            val semaphore = Semaphore(PARALLEL_PAGES)
            (2..min(totalPages, MAX_PAGES))
                .map { page -> async { semaphore.withPermit { fetchPage(page).data.orEmpty() } } }
                .awaitAll()
                .forEach { items.addAll(it) }
        } else {
            // Без meta.pagination — последовательно, пока страницы полные.
            var page = 1
            var last = first.data.orEmpty()
            while (last.size >= PAGE_LIMIT && page < MAX_PAGES) {
                page++
                last = fetchPage(page).data.orEmpty()
                items.addAll(last)
            }
        }
        WatchHistoryLogic.fromResponse(items)
    }

    // include сильно уменьшает ответ (без него приходят серия и релиз целиком).
    // Если сервер проигнорировал вложенные поля — перезапрашиваем без include.
    private suspend fun fetchPage(page: Int, detectInclude: Boolean = false): ViewHistoryPageResponse {
        if (!useInclude) return viewsApi.getHistory(page, PAGE_LIMIT, null)
        val response = viewsApi.getHistory(page, PAGE_LIMIT, HISTORY_INCLUDE)
        if (!detectInclude) return response
        val items = response.data.orEmpty()
        val hasReleaseIds = items.any { it.releaseEpisode?.releaseId != null }
        return if (items.isNotEmpty() && !hasReleaseIds) {
            Timber.w("views/history ignored include, falling back to full response")
            useInclude = false
            viewsApi.getHistory(page, PAGE_LIMIT, null)
        } else {
            response
        }
    }

    private companion object {
        // Сервер отвечает 422 на limit > 50.
        const val PAGE_LIMIT = 50
        const val RETRY_BACKOFF_MS = 60 * 1_000L
        const val MAX_PAGES = 100
        const val PARALLEL_PAGES = 4
        const val REFRESH_TTL_MS = 10 * 60 * 1_000L
        const val PERSIST_DELAY_MS = 2_000L
        const val PENDING_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000L
        const val HISTORY_INCLUDE =
            "time,is_watched,updated_at,release_episode_id,release_episode.ordinal," +
                    "release_episode.release_id,release_episode.release.episodes_total"
    }
}

package ru.radiationx.anilibria.screen.player

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.radiationx.anilibria.common.DetailDataConverter
import ru.radiationx.anilibria.common.WatchCollectionSync
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.watchnext.WatchNextPublisher
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.PlayerSkips
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.player.EpisodePlaybackRules
import ru.radiationx.data.player.PlayerBufferConfig
import ru.radiationx.data.repository.HistoryRepository
import ru.radiationx.shared.ktx.EventFlow
import ru.radiationx.shared.ktx.coRunCatching
import javax.inject.Inject

class PlayerViewModel @Inject constructor(
    private val argExtra: PlayerExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val historyRepository: HistoryRepository,
    private val preferencesHolder: PreferencesHolder,
    private val playerController: PlayerController,
    private val router: Router,
    private val watchCollectionSync: WatchCollectionSync,
    private val watchNextPublisher: WatchNextPublisher,
    private val detailDataConverter: DetailDataConverter,
) : LifecycleViewModel() {

    val videoData = MutableStateFlow<Video?>(null)
    val qualityState = MutableStateFlow<PlayerQuality?>(null)
    val speedState = MutableStateFlow<Float?>(null)
    val controlsState = MutableStateFlow(PlayerControlsState())
    // Начальные настройки читаются из prefs сразу, чтобы до первого combine не светились старые дефолты.
    val composeMenuState = MutableStateFlow(
        PlayerComposeMenuState(
            settings = PlayerComposeSettingsState(
                skipsEnabled = preferencesHolder.playerSkips.value,
                autoSkipEnabled = preferencesHolder.playerSkipsTimer.value,
                autoplayEnabled = preferencesHolder.playerAutoplay.value,
                backBufferSeconds = preferencesHolder.playerBackBufferSeconds.value,
                forwardBufferSeconds = preferencesHolder.playerForwardBufferSeconds.value,
                bufferMemoryLimitMb = preferencesHolder.playerBufferMemoryLimitMb.value,
                diskCacheEnabled = preferencesHolder.playerDiskCacheEnabled.value,
                diskCacheSizeMb = preferencesHolder.playerDiskCacheSizeMb.value,
                preloadNextEpisode = preferencesHolder.playerPreloadNextEpisode.value,
            ),
        )
    )
    val completionOverlay = MutableStateFlow<PlayerCompletionOverlay?>(null)
    val playAction = EventFlow<Boolean>()
    val settingsOverlayVisible = playerController.settingsOverlayVisible

    private var currentEpisodes = mutableListOf<Episode>()
    private var currentReleases: List<Release>? = null
    private var currentEpisode: Episode? = null
    private var currentQuality: PlayerQuality? = null
    private var watchedReached = false
    private var watchedSynced = false
    private var completionHandled = false
    private var progressSyncJob: Job? = null
    private var episodeTransitionJob: Job? = null
    private var lastKnownPosition = 0L
    private var lastKnownDuration = 0L
    private var progressDirty = false
    private var lastSyncedPosition = Long.MIN_VALUE
    private var lastSyncedDuration = Long.MIN_VALUE
    private var lastSyncedAt = 0L
    private var lastPeriodicSyncAt = 0L
    private var pendingAutoPlay = false

    /**
     * Автостарт только на первом READY загруженной серии. Повторные READY (перемотка на паузе,
     * rebuffer, восстановление после сетевой ошибки во сне) не должны снимать паузу пользователя.
     */
    private var awaitingInitialPlay = false
    private var playedMs = 0L
    private var lastPlayTickAt = 0L
    private val playbackStartReported = mutableSetOf<ReleaseId>()
    private var prearm: PrearmHandle? = null
    private var queueEndReachedAt = 0L

    init {
        playerController.reset()
        currentQuality = PlayerQuality.FULLHD
        qualityState.value = PlayerQuality.FULLHD
        speedState.value = preferencesHolder.playSpeed.value

        combine(
            combine(
                preferencesHolder.availableSpeeds,
                preferencesHolder.playSpeed,
            ) { speeds, speed ->
                speeds to speed
            },
            combine(
                preferencesHolder.playerBufferMemoryLimitMb,
                combine(
                    preferencesHolder.playerSkips,
                    preferencesHolder.playerSkipsTimer,
                    preferencesHolder.playerAutoplay,
                    preferencesHolder.playerBackBufferSeconds,
                    preferencesHolder.playerForwardBufferSeconds,
                ) { skipsEnabled, autoSkipEnabled, autoplayEnabled, backBufferSeconds, forwardBufferSeconds ->
                    PlayerComposeSettingsState(
                        skipsEnabled = skipsEnabled,
                        autoSkipEnabled = autoSkipEnabled,
                        autoplayEnabled = autoplayEnabled,
                        backBufferSeconds = backBufferSeconds,
                        forwardBufferSeconds = forwardBufferSeconds,
                    )
                },
            ) { bufferMemoryLimitMb, settings ->
                settings.copy(bufferMemoryLimitMb = bufferMemoryLimitMb)
            },
            combine(
                preferencesHolder.playerDiskCacheEnabled,
                preferencesHolder.playerDiskCacheSizeMb,
                preferencesHolder.playerPreloadNextEpisode,
            ) { diskCacheEnabled, diskCacheSizeMb, preloadNextEpisode ->
                Triple(diskCacheEnabled, diskCacheSizeMb, preloadNextEpisode)
            },
        ) { speedPair, settings, cachePair ->
            composeMenuState.value = composeMenuState.value.copy(
                availableSpeeds = speedPair.first,
                selectedSpeed = speedPair.second,
                settings = settings.copy(
                    diskCacheEnabled = cachePair.first,
                    diskCacheSizeMb = cachePair.second,
                    preloadNextEpisode = cachePair.third,
                ),
            )
        }.launchIn(viewModelScope)

        playerController
            .selectEpisodeRelay
            .onEach { episodeId ->
                currentEpisodes
                    .firstOrNull { it.id == episodeId }
                    ?.also {
                        transitionToEpisode(it, reason = "controller_select", force = true)
                    }
            }
            .launchIn(viewModelScope)

        preferencesHolder.playerQuality.value = PlayerQuality.FULLHD
        preferencesHolder
            .playerQuality
            .onEach {
                currentQuality = it
                updateQuality()
                updateEpisode()
                refreshComposeMenuState()
            }
            .launchIn(viewModelScope)

        preferencesHolder
            .playSpeed
            .onEach {
                speedState.value = it
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            coRunCatching {
                releaseInteractor.loadWithFranchises(argExtra.releaseId)
            }.onSuccess { releases ->
                playerController.data.value = releases
                currentReleases = releases
                currentEpisodes.clear()
                currentEpisodes.addAll(releases.flatMap { it.episodes })
                updateControlsState()
                refreshComposeMenuState()
                val episodeId = currentEpisode?.id ?: argExtra.episodeId
                val episode = currentEpisodes
                    .firstOrNull { it.id == episodeId }
                    ?: currentEpisodes.firstOrNull()
                episode?.also { playEpisode(it) }
            }.onFailure {

            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressSyncJob?.cancel()
        episodeTransitionJob?.cancel()
        prearm?.deferred?.cancel()
        prearm = null
        playerController.reset()
    }

    private fun getCurrentRelease(): Release? {
        val episodeId = currentEpisode?.id ?: return null
        return currentReleases?.find { it.id == episodeId.releaseId }
    }

    fun onPauseClick(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        launchImmediateEpisodeSync(reason = "pause")
    }

    fun onStopClick(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        launchImmediateEpisodeSync(reason = "stop")
    }

    fun onNextClick(position: Long, duration: Long) {
        getNextEpisode()?.also {
            updatePlaybackSnapshot(position, duration)
            transitionToEpisode(it, reason = "next_click")
        }
    }

    fun onPrevClick(position: Long, duration: Long) {
        getPrevEpisode()?.also {
            updatePlaybackSnapshot(position, duration)
            transitionToEpisode(it, reason = "prev_click")
        }
    }

    fun applyQuality(quality: PlayerQuality) {
        preferencesHolder.playerQuality.value = quality
    }

    fun applySpeed(speed: Float) {
        preferencesHolder.playSpeed.value = speed
    }

    fun applyEpisode(episodeId: EpisodeId, position: Long, duration: Long) {
        currentEpisodes
            .firstOrNull { it.id == episodeId }
            ?.also {
                updatePlaybackSnapshot(position, duration)
                transitionToEpisode(it, reason = "episode_select", force = true)
            }
    }

    fun setSkipsEnabled(value: Boolean) {
        preferencesHolder.playerSkips.value = value
    }

    fun setAutoSkipEnabled(value: Boolean) {
        preferencesHolder.playerSkipsTimer.value = value
    }

    fun setAutoplayEnabled(value: Boolean) {
        preferencesHolder.playerAutoplay.value = value
    }

    fun setDiskCacheEnabled(value: Boolean) {
        preferencesHolder.playerDiskCacheEnabled.value = value
    }

    fun setBufferMemoryLimitMb(value: Int) {
        preferencesHolder.playerBufferMemoryLimitMb.value = value
    }

    fun setDiskCacheSizeMb(value: Int) {
        preferencesHolder.playerDiskCacheSizeMb.value = value
    }

    fun setPreloadNextEpisode(value: Boolean) {
        preferencesHolder.playerPreloadNextEpisode.value = value
    }

    fun adjustBackBufferSeconds(delta: Int) {
        preferencesHolder.playerBackBufferSeconds.value =
            (preferencesHolder.playerBackBufferSeconds.value + delta).coerceAtLeast(0)
    }

    fun adjustForwardBufferSeconds(delta: Int) {
        preferencesHolder.playerForwardBufferSeconds.value =
            (preferencesHolder.playerForwardBufferSeconds.value + delta).coerceAtLeast(0)
    }

    fun onComplete(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        handleEpisodeCompletion("complete")
    }

    fun onEndingSkipped(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        markWatchedIfNeeded(reason = "ending_skip")
        launchImmediateEpisodeSync(reason = "ending_skip")
    }

    fun onPrepare(duration: Long) {
        if (duration > 0L) {
            lastKnownDuration = duration
        }
        if (currentEpisode == null) {
            return
        }
        if (!awaitingInitialPlay) {
            return
        }
        awaitingInitialPlay = false
        viewModelScope.launch {
            val autoPlay = pendingAutoPlay
            pendingAutoPlay = false
            Log.d(TAG, "prepare episode=${currentEpisode?.id} duration=$lastKnownDuration autoPlay=$autoPlay watched=$watchedReached")
            playAction.emit(true)
        }
    }

    /**
     * @param queueAdvanceExpected следующая серия стоит в плеере следующим MediaItem и ExoPlayer
     * перейдёт на неё сам ([onQueueAdvanced]); app-side завершение тогда только страховка.
     */
    fun onPlaybackProgress(
        position: Long,
        duration: Long,
        isPlaying: Boolean,
        queueAdvanceExpected: Boolean = false,
    ) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
        markWatchedIfNeeded(reason = "progress")
        trackPlaybackStart(isPlaying)
        maybePrearmNextEpisode()
        if (isEpisodeActuallyFinished(lastKnownPosition, lastKnownDuration)) {
            if (queueAdvanceExpected && canAutoAdvance()) {
                val now = SystemClock.elapsedRealtime()
                if (queueEndReachedAt == 0L) {
                    queueEndReachedAt = now
                    Log.d(TAG, "end-wait-queue episode=${currentEpisode?.id} position=$lastKnownPosition duration=$lastKnownDuration")
                }
                if (!EpisodePlaybackRules.isQueueTransitionOverdue(queueEndReachedAt, now)) {
                    return
                }
                Log.w(TAG, "queue-transition-timeout episode=${currentEpisode?.id} waitedMs=${now - queueEndReachedAt} -> fallback")
            }
            handleEpisodeCompletion("progress")
            return
        }
        queueEndReachedAt = 0L
        if (!isPlaying || completionHandled) {
            return
        }
        val now = System.currentTimeMillis()
        if (progressDirty && now - lastPeriodicSyncAt >= PERIODIC_SYNC_MS) {
            scheduleEpisodeSync(reason = "periodic")
        }
    }

    fun onSeek(position: Long, duration: Long) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
    }

    private fun handleEpisodeCompletion(source: String) {
        if (completionHandled) {
            return
        }
        completionHandled = true
        markWatchedIfNeeded(reason = "completion:$source", force = true)
        // Серия могла быть отмечена раньше — тогда отметка не отправляется и перенос
        // в «Просмотрено» не проверялся бы. Досмотр до конца проверяет его всегда.
        if (watchedSynced) {
            currentEpisode?.also { episode ->
                launchCollectionSync { watchCollectionSync.onEpisodeWatched(episode.id.releaseId) }
            }
        }
        Log.d(TAG, "end source=$source episode=${currentEpisode?.id} position=$lastKnownPosition duration=$lastKnownDuration watched=$watchedReached")
        val nextEpisode = getNextEpisode()
        if (nextEpisode != null && preferencesHolder.playerAutoplay.value) {
            Log.d(TAG, "auto-next episode=${currentEpisode?.id} -> ${nextEpisode.id}")
            autoAdvanceToEpisode(
                episode = nextEpisode,
                reason = "auto_next:$source",
                reuseLoadedItem = false,
            )
        } else {
            viewModelScope.launch {
                syncCurrentEpisodeNow(force = true, reason = "completion:$source")
                if (nextEpisode == null) {
                    Log.d(TAG, "season-end episode=${currentEpisode?.id}")
                    showSeasonCompletionOverlay()
                } else {
                    Log.d(TAG, "auto-next-disabled episode=${currentEpisode?.id}")
                }
            }
        }
    }

    /**
     * ExoPlayer сам перешёл на следующий MediaItem очереди (reason AUTO): старая серия доиграна до конца.
     * Переключаем состояние на следующую серию без повторного prepare плеера.
     */
    fun onQueueAdvanced(itemUrl: String): QueueAdvanceResult {
        val current = currentEpisode ?: return QueueAdvanceResult.REJECTED
        val quality = currentQuality ?: return QueueAdvanceResult.REJECTED
        if (current.qualityInfo.getSafeUrlFor(quality) == itemUrl) {
            // Страховочный путь уже переключил серию — videoData перезагрузит плеер сам.
            Log.d(TAG, "queue-advance-ignored already-current episode=${current.id}")
            return QueueAdvanceResult.ALREADY_CURRENT
        }
        if (episodeTransitionJob?.isActive == true) {
            Log.d(TAG, "queue-advance-ignored transition-in-progress episode=${current.id}")
            return QueueAdvanceResult.REJECTED
        }
        val next = getNextEpisode()
        val urlMatches = next?.qualityInfo?.getSafeUrlFor(quality) == itemUrl
        if (next == null || !urlMatches || !preferencesHolder.playerAutoplay.value) {
            Log.w(TAG, "queue-advance-rejected episode=${current.id} next=${next?.id} urlMatches=$urlMatches")
            handleEpisodeCompletion("queue_mismatch")
            return QueueAdvanceResult.REJECTED
        }
        // Старый MediaItem доигран до конца: финальная позиция = длительность.
        if (lastKnownDuration > 0L) {
            updatePlaybackSnapshot(lastKnownDuration, lastKnownDuration)
        }
        completionHandled = true
        markWatchedIfNeeded(reason = "completion:queue", force = true)
        Log.d(TAG, "end source=queue episode=${current.id} position=$lastKnownPosition duration=$lastKnownDuration watched=$watchedReached")
        Log.d(TAG, "auto-next episode=${current.id} -> ${next.id}")
        val previousWatched = watchedReached
        autoAdvanceToEpisode(
            episode = next,
            reason = "auto_next:queue",
            reuseLoadedItem = true,
        )
        return if (previousWatched) QueueAdvanceResult.ACCEPTED_PREVIOUS_WATCHED else QueueAdvanceResult.ACCEPTED
    }

    private fun canAutoAdvance(): Boolean =
        getNextEpisode() != null && preferencesHolder.playerAutoplay.value

    private fun getNextEpisode(): Episode? =
        currentEpisodes.getOrNull(getCurrentEpisodeIndex() + 1)

    private fun getEpisodeAfter(episode: Episode): Episode? {
        val index = currentEpisodes.indexOfFirst { it.id == episode.id }
        if (index < 0) {
            return null
        }
        return currentEpisodes.getOrNull(index + 1)
    }

    private fun getPrevEpisode(): Episode? =
        currentEpisodes.getOrNull(getCurrentEpisodeIndex() - 1)

    private fun getCurrentEpisodeIndex(): Int =
        currentEpisodes.indexOfFirst { it.id == currentEpisode?.id }


    private fun scheduleEpisodeSync(reason: String) {
        if (progressSyncJob?.isActive == true) {
            return
        }
        progressSyncJob = viewModelScope.launch {
            sendCurrentEpisodeSync(force = false, reason = reason)
        }.also { job ->
            job.invokeOnCompletion {
                if (progressSyncJob === job) {
                    progressSyncJob = null
                }
            }
        }
    }

    private fun updatePlaybackSnapshot(position: Long, duration: Long) {
        val snapshot = normalizePlaybackSnapshot(position, duration)
        if ((lastKnownPosition != snapshot.positionMs || lastKnownDuration != snapshot.durationMs) &&
            !(watchedReached && watchedSynced)
        ) {
            progressDirty = true
        }
        lastKnownPosition = snapshot.positionMs
        lastKnownDuration = snapshot.durationMs
    }

    private fun launchImmediateEpisodeSync(reason: String) {
        viewModelScope.launch {
            syncCurrentEpisodeNow(force = true, reason = reason)
        }
    }

    private fun transitionToEpisode(
        episode: Episode,
        reason: String,
        force: Boolean = false,
        autoPlay: Boolean = false,
    ) {
        if (episodeTransitionJob?.isActive == true) {
            return
        }
        episodeTransitionJob = viewModelScope.launch {
            markWatchedIfNeeded(reason = reason)
            syncCurrentEpisodeNow(force = true, reason = reason)
            playEpisode(episode, force = force, autoPlay = autoPlay)
        }.also { job ->
            job.invokeOnCompletion {
                if (episodeTransitionJob === job) {
                    episodeTransitionJob = null
                }
            }
        }
    }

    /**
     * Автопереход на следующую серию без ожидания сети:
     * 1) отметка/синк старой серии уходит в отдельную NonCancellable-корутину со снимком её состояния;
     * 2) новая серия стартует сразу на заранее подготовленных данных ([maybePrearmNextEpisode]).
     */
    private fun autoAdvanceToEpisode(
        episode: Episode,
        reason: String,
        reuseLoadedItem: Boolean,
    ) {
        if (episodeTransitionJob?.isActive == true) {
            return
        }
        markWatchedIfNeeded(reason = reason)
        // Снимок старой серии берётся здесь, до любого изменения состояния.
        launchDetachedEpisodeSync(reason = reason)
        val quality = currentQuality
        val handle = prearm?.takeIf { it.episodeId == episode.id && it.quality == quality }
        val startedAt = SystemClock.elapsedRealtime()
        // UNDISPATCHED: если данные уже готовы, await() не приостанавливается и переключение синхронное.
        episodeTransitionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val deferred = handle?.deferred
            val prepared = if (deferred == null || deferred.isCancelled) null else deferred.await()
            Log.d(
                TAG,
                "fast-switch reason=$reason -> ${episode.id} prearmed=${prepared != null} waitMs=${SystemClock.elapsedRealtime() - startedAt} reuse=$reuseLoadedItem"
            )
            playEpisode(
                episode = episode,
                force = true,
                autoPlay = true,
                prepared = prepared,
                reuseLoadedItem = reuseLoadedItem,
            )
        }.also { job ->
            job.invokeOnCompletion {
                if (episodeTransitionJob === job) {
                    episodeTransitionJob = null
                }
            }
        }
    }

    private fun maybePrearmNextEpisode() {
        if (!preferencesHolder.playerAutoplay.value || completionHandled) {
            return
        }
        val current = currentEpisode ?: return
        if (!EpisodePlaybackRules.isInPrearmWindow(lastKnownPosition, lastKnownDuration)) {
            return
        }
        val next = getNextEpisode() ?: return
        val quality = currentQuality ?: return
        val existing = prearm
        if (existing != null && existing.episodeId == next.id && existing.quality == quality) {
            return
        }
        existing?.deferred?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "prearm-start current=${current.id} next=${next.id} remainingMs=${lastKnownDuration - lastKnownPosition}")
        val deferred = viewModelScope.async {
            coRunCatching {
                prepareEpisode(next, quality)
            }.onSuccess {
                Log.d(TAG, "prearm-ready next=${next.id} ms=${SystemClock.elapsedRealtime() - startedAt} seek=${it?.initialSeek} viewed=${it?.isViewed}")
            }.onFailure {
                Log.w(TAG, "prearm-failed next=${next.id}", it)
            }.getOrNull()
        }
        prearm = PrearmHandle(episodeId = next.id, quality = quality, deferred = deferred)
    }

    /**
     * Синк серии, с которой уходим, не блокирует переключение. Состояние (id, позиция, флаги)
     * фиксируется синхронно до переключения, поэтому отправляются данные именно старой серии.
     */
    private fun launchDetachedEpisodeSync(reason: String) {
        val state = captureSyncState() ?: return
        val pendingProgressJob = progressSyncJob
        progressSyncJob = null
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                pendingProgressJob?.cancelAndJoin()
                sendEpisodeSync(state, force = true, reason = reason)
            }
        }
    }

    // NonCancellable: при выходе из плеера viewModelScope отменяется,
    // а последняя позиция всё равно должна дойти до сервера.
    private suspend fun syncCurrentEpisodeNow(force: Boolean, reason: String) = withContext(NonCancellable) {
        progressSyncJob?.cancelAndJoin()
        progressSyncJob = null
        sendCurrentEpisodeSync(force = force, reason = reason)
    }

    private fun captureSyncState(): EpisodeSyncState? {
        val episode = currentEpisode ?: return null
        return EpisodeSyncState(
            episode = episode,
            position = lastKnownPosition,
            duration = lastKnownDuration,
            watchedReached = watchedReached,
            watchedSynced = watchedSynced,
            progressDirty = progressDirty,
            lastSyncedPosition = lastSyncedPosition,
            lastSyncedDuration = lastSyncedDuration,
            lastSyncedAt = lastSyncedAt,
        )
    }

    private suspend fun sendCurrentEpisodeSync(force: Boolean, reason: String) {
        val state = captureSyncState() ?: return
        sendEpisodeSync(state, force = force, reason = reason)
    }

    private fun isCurrentEpisode(episode: Episode): Boolean = currentEpisode?.id == episode.id

    private suspend fun sendEpisodeSync(state: EpisodeSyncState, force: Boolean, reason: String) {
        val episode = state.episode
        val position = state.position
        if (position < 0L) {
            return
        }
        val duration = state.duration
        val sendWatched = state.watchedReached && !state.watchedSynced
        val sendProgress = !state.watchedReached
        if (!force && !state.progressDirty && !sendWatched) {
            return
        }
        if (!sendProgress && !sendWatched) {
            if (isCurrentEpisode(episode)) {
                progressDirty = false
            }
            return
        }
        val now = System.currentTimeMillis()
        if (!force &&
            !sendWatched &&
            position == state.lastSyncedPosition &&
            duration == state.lastSyncedDuration &&
            now - state.lastSyncedAt < DUPLICATE_GUARD_MS
        ) {
            if (isCurrentEpisode(episode)) {
                progressDirty = false
                lastPeriodicSyncAt = now
            }
            return
        }
        Log.d(
            TAG,
            "progress-sync reason=$reason episode=${episode.id} position=$position duration=$duration sendProgress=$sendProgress sendWatched=$sendWatched"
        )
        val synced = coRunCatching {
            releaseInteractor.setAccessSeek(
                id = episode.id,
                serverId = episode.serverId,
                seek = position,
                duration = duration.takeIf { it > 0L },
                forceViewed = sendWatched,
            )
        }.onFailure {
            // progressDirty остаётся true — отправка повторится на следующем триггере.
            Log.w(TAG, "progress-sync failed reason=$reason episode=${episode.id}", it)
        }.isSuccess
        // Системный ряд «Продолжить просмотр» обновляем независимо от результата сетевой отправки.
        publishWatchNext(episode, position, duration, isViewed = sendWatched || watchedReached)
        if (!synced) {
            return
        }
        if (isCurrentEpisode(episode)) {
            lastSyncedPosition = position
            lastSyncedDuration = duration
            lastSyncedAt = System.currentTimeMillis()
            lastPeriodicSyncAt = lastSyncedAt
            progressDirty = false
            if (sendWatched) {
                watchedSynced = true
            }
        }
        if (sendWatched) {
            Log.d(TAG, "watched-sync episode=${episode.id} position=$position duration=$duration")
            launchCollectionSync { watchCollectionSync.onEpisodeWatched(episode.id.releaseId) }
        }
    }

    private suspend fun publishWatchNext(episode: Episode, position: Long, duration: Long, isViewed: Boolean) {
        val release = currentReleases?.find { it.id == episode.id.releaseId } ?: return
        coRunCatching {
            watchNextPublisher.onEpisodeProgress(
                release = release,
                episode = episode,
                positionMs = position,
                durationMs = duration,
                isViewed = isViewed,
            )
        }.onFailure {
            Log.w(TAG, "watch-next publish failed episode=${episode.id}", it)
        }
    }

    private fun trackPlaybackStart(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val lastTick = lastPlayTickAt
        lastPlayTickAt = now
        if (!isPlaying || lastTick == 0L) {
            return
        }
        val releaseId = currentEpisode?.id?.releaseId ?: return
        if (releaseId in playbackStartReported) {
            return
        }
        playedMs += (now - lastTick).coerceIn(0L, MAX_PLAY_TICK_MS)
        if (playedMs < PLAYBACK_STARTED_MS) {
            return
        }
        playbackStartReported += releaseId
        Log.d(TAG, "playback-started release=${releaseId.id} playedMs=$playedMs")
        launchCollectionSync { watchCollectionSync.onPlaybackStarted(releaseId) }
    }

    private fun launchCollectionSync(block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(NonCancellable) { block() }
        }
    }

    private fun markWatchedIfNeeded(reason: String, force: Boolean = false) {
        if (watchedReached || watchedSynced) {
            return
        }
        if (!force && !hasReachedWatchedThreshold(lastKnownPosition, lastKnownDuration)) {
            return
        }
        watchedReached = true
        progressDirty = true
        Log.d(
            TAG,
            "watched-threshold reason=$reason episode=${currentEpisode?.id} position=$lastKnownPosition duration=$lastKnownDuration"
        )
    }

    private fun hasReachedWatchedThreshold(position: Long, duration: Long): Boolean {
        return EpisodePlaybackRules.hasReachedWatchedThreshold(position, duration)
    }

    private fun normalizePlaybackSnapshot(position: Long, duration: Long): NormalizedPlaybackSnapshot {
        val effectiveDuration = duration.takeIf { it > 0L } ?: lastKnownDuration
        val effectivePosition = EpisodePlaybackRules.clampPosition(position, effectiveDuration)
        return NormalizedPlaybackSnapshot(
            positionMs = effectivePosition,
            durationMs = effectiveDuration,
        )
    }

    private fun isEpisodeActuallyFinished(position: Long, duration: Long): Boolean {
        if (completionHandled) {
            return false
        }
        return EpisodePlaybackRules.isAtEpisodeEnd(position, duration)
    }

    private fun playEpisode(
        episode: Episode,
        force: Boolean = false,
        autoPlay: Boolean = false,
        prepared: PreparedEpisode? = null,
        reuseLoadedItem: Boolean = false,
    ) {
        progressSyncJob?.cancel()
        prearm?.deferred?.cancel()
        prearm = null
        queueEndReachedAt = 0L
        completionOverlay.value = null
        currentEpisode = episode
        watchedReached = false
        watchedSynced = false
        completionHandled = false
        pendingAutoPlay = autoPlay
        // Серия из очереди плеера уже играет — её READY не должен ничего запускать.
        awaitingInitialPlay = !reuseLoadedItem
        playedMs = 0L
        lastPlayTickAt = 0L
        lastKnownPosition = 0
        lastKnownDuration = 0
        progressDirty = false
        lastSyncedPosition = Long.MIN_VALUE
        lastSyncedDuration = Long.MIN_VALUE
        lastSyncedAt = 0
        lastPeriodicSyncAt = System.currentTimeMillis()
        updateQuality()
        updateEpisode(force, prepared, reuseLoadedItem)
        updateControlsState()
        refreshComposeMenuState()
        viewModelScope.launch {
            historyRepository.putReleaseId(episode.id.releaseId)
        }
    }

    private fun updateQuality() {
        val quality = currentQuality ?: return
        qualityState.value = currentEpisode?.qualityInfo?.getActualFor(quality) ?: quality
    }

    private fun updateEpisode(
        force: Boolean = false,
        prepared: PreparedEpisode? = null,
        reuseLoadedItem: Boolean = false,
    ) {
        val episode = currentEpisode ?: return
        val quality = currentQuality ?: return
        if (prepared != null && prepared.episodeId == episode.id && prepared.quality == quality) {
            applyPreparedEpisode(prepared, force, reuseLoadedItem)
            return
        }
        getCurrentRelease() ?: return
        viewModelScope.launch {
            val loaded = prepareEpisode(episode, quality) ?: return@launch
            applyPreparedEpisode(loaded, force, reuseLoadedItem)
        }
    }

    /** Всё, что нужно для старта серии: URL, URL следующей и начальная позиция из access. */
    private suspend fun prepareEpisode(episode: Episode, quality: PlayerQuality): PreparedEpisode? {
        val release = currentReleases?.find { it.id == episode.id.releaseId } ?: return null
        val newUrl = episode.qualityInfo.getSafeUrlFor(quality)
        val nextUrl = getEpisodeAfter(episode)?.qualityInfo?.getSafeUrlFor(quality)
        val access = releaseInteractor.getAccess(episode.id)
        val isViewed = access?.isViewed == true
        val initialSeek = if (isViewed) 0L else access?.seek ?: 0L
        return PreparedEpisode(
            episodeId = episode.id,
            quality = quality,
            url = newUrl,
            nextUrl = nextUrl,
            isViewed = isViewed,
            initialSeek = initialSeek,
            title = release.title.orEmpty(),
            subtitle = episode.title.orEmpty(),
            skips = episode.skips,
        )
    }

    private fun applyPreparedEpisode(prepared: PreparedEpisode, force: Boolean, reuseLoadedItem: Boolean) {
        if (currentEpisode?.id != prepared.episodeId) {
            Log.d(TAG, "episode-load-stale episode=${prepared.episodeId} current=${currentEpisode?.id}")
            return
        }
        val initialSeek = prepared.initialSeek
        watchedReached = prepared.isViewed
        watchedSynced = prepared.isViewed
        completionHandled = false
        lastKnownPosition = initialSeek
        lastKnownDuration = 0
        progressDirty = false
        lastSyncedPosition = lastKnownPosition
        lastSyncedDuration = 0
        lastSyncedAt = System.currentTimeMillis()
        lastPeriodicSyncAt = lastSyncedAt
        Log.d(
            TAG,
            "episode-load episode=${prepared.episodeId} seek=$initialSeek watched=$watchedReached autoPlay=$pendingAutoPlay reuse=$reuseLoadedItem"
        )
        val newVideo = Video(
            url = prepared.url,
            nextUrl = prepared.nextUrl,
            seek = initialSeek,
            title = prepared.title,
            subtitle = prepared.subtitle,
            skips = prepared.skips,
            reuseLoadedItem = reuseLoadedItem,
        )
        if (force || videoData.value?.url != newVideo.url) {
            videoData.value = newVideo
        }
    }

    private fun updateControlsState() {
        val release = getCurrentRelease()
        val episode = currentEpisode
        controlsState.value = PlayerControlsState(
            title = release?.title.orEmpty(),
            subtitle = episode?.title.orEmpty(),
            hasPrevious = getPrevEpisode() != null,
            hasNext = getNextEpisode() != null,
        )
    }

    fun playNextEpisodeFromOverlay() {
        completionOverlay.value = null
        getNextEpisode()?.also { nextEpisode ->
            playEpisode(nextEpisode, force = true, autoPlay = true)
        }
    }

    fun closePlayerFromOverlay() {
        completionOverlay.value = null
        router.exit()
    }

    private fun showSeasonCompletionOverlay() {
        val release = getCurrentRelease()
        val available = release?.episodes?.size ?: 0
        val total = release?.series?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        // Онгоинг: вышли не все серии — это не конец сезона, а ожидание новых серий.
        val waitingForEpisodes = release != null && (
            (total != null && available < total) ||
                release.statusCode == Release.STATUS_CODE_PROGRESS
            )
        val (title, subtitle) = if (waitingForEpisodes) {
            val count = if (total != null) "Вышло $available из $total серий." else "Вышло серий: $available."
            val announce = release?.let { detailDataConverter.scheduleAnnounce(it) }
            "Новые серии ещё не вышли" to listOfNotNull(count, announce).joinToString(" ")
        } else {
            "Сезон закончился" to "Вы посмотрели все серии."
        }
        completionOverlay.value = PlayerCompletionOverlay(
            type = PlayerCompletionOverlayType.END_SEASON,
            title = title,
            subtitle = subtitle,
            nextEpisodeLabel = null,
            closeLabel = "Закрыть",
            autoAdvanceEnabled = false,
        )
    }

    private fun refreshComposeMenuState() {
        val selectedQuality = qualityState.value ?: currentQuality
        val selectedEpisodeId = currentEpisode?.id
        composeMenuState.value = composeMenuState.value.copy(
            qualityOptions = currentEpisode
                ?.qualityInfo
                ?.available
                ?.sortedByDescending { it.ordinal }
                ?.map {
                    PlayerComposeQualityOption(
                        quality = it,
                        selected = it == selectedQuality,
                    )
                }
                .orEmpty(),
            selectedQuality = selectedQuality,
            episodeGroups = currentReleases
                .orEmpty()
                .map { release ->
                    PlayerComposeEpisodeGroup(
                        title = release.title.orEmpty(),
                        episodes = release.episodes.map { episode ->
                            PlayerComposeEpisodeItem(
                                episodeId = episode.id,
                                title = episode.title.orEmpty(),
                                description = if (episode.id == selectedEpisodeId) "Сейчас" else null,
                                selected = episode.id == selectedEpisodeId,
                            )
                        }
                    )
                },
            selectedEpisodeId = selectedEpisodeId,
        )
    }

    private companion object {
        private const val TAG = "PlayerFlow"
        private const val PERIODIC_SYNC_MS = 60 * 1_000L
        private const val PLAYBACK_STARTED_MS = 30 * 1_000L
        private const val MAX_PLAY_TICK_MS = 2_000L
        private const val DUPLICATE_GUARD_MS = 2_000L
    }
}

enum class QueueAdvanceResult(val accepted: Boolean, val previousEpisodeWatched: Boolean) {
    /** Переход не принят: VM всё ещё на старой серии, плеер надо вернуть/остановить. */
    REJECTED(accepted = false, previousEpisodeWatched = false),
    /** VM уже переключилась на эту серию другим путём (страховка) и сама перезагрузит плеер. */
    ALREADY_CURRENT(accepted = false, previousEpisodeWatched = false),
    ACCEPTED(accepted = true, previousEpisodeWatched = false),
    ACCEPTED_PREVIOUS_WATCHED(accepted = true, previousEpisodeWatched = true),
}

private class PrearmHandle(
    val episodeId: EpisodeId,
    val quality: PlayerQuality,
    val deferred: Deferred<PreparedEpisode?>,
)

private data class PreparedEpisode(
    val episodeId: EpisodeId,
    val quality: PlayerQuality,
    val url: String,
    val nextUrl: String?,
    val isViewed: Boolean,
    val initialSeek: Long,
    val title: String,
    val subtitle: String,
    val skips: PlayerSkips?,
)

/** Снимок состояния серии для синка: отправляются данные именно этой серии даже после переключения. */
private data class EpisodeSyncState(
    val episode: Episode,
    val position: Long,
    val duration: Long,
    val watchedReached: Boolean,
    val watchedSynced: Boolean,
    val progressDirty: Boolean,
    val lastSyncedPosition: Long,
    val lastSyncedDuration: Long,
    val lastSyncedAt: Long,
)

private data class NormalizedPlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long,
)

data class PlayerControlsState(
    val title: String = "",
    val subtitle: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

data class PlayerComposeMenuState(
    val qualityOptions: List<PlayerComposeQualityOption> = emptyList(),
    val selectedQuality: PlayerQuality? = null,
    val availableSpeeds: List<Float> = emptyList(),
    val selectedSpeed: Float = 1f,
    val episodeGroups: List<PlayerComposeEpisodeGroup> = emptyList(),
    val selectedEpisodeId: EpisodeId? = null,
    val settings: PlayerComposeSettingsState = PlayerComposeSettingsState(),
)

data class PlayerComposeQualityOption(
    val quality: PlayerQuality,
    val selected: Boolean,
)

data class PlayerComposeEpisodeGroup(
    val title: String,
    val episodes: List<PlayerComposeEpisodeItem>,
)

data class PlayerComposeEpisodeItem(
    val episodeId: EpisodeId,
    val title: String,
    val description: String?,
    val selected: Boolean,
)

data class PlayerComposeSettingsState(
    val skipsEnabled: Boolean = true,
    val autoSkipEnabled: Boolean = true,
    val autoplayEnabled: Boolean = true,
    val backBufferSeconds: Int = 0,
    val forwardBufferSeconds: Int = PlayerBufferConfig.DEFAULT_FORWARD_BUFFER_SECONDS,
    val bufferMemoryLimitMb: Int = PlayerBufferConfig.DEFAULT_BUFFER_MEMORY_LIMIT_MB,
    val diskCacheEnabled: Boolean = false,
    val diskCacheSizeMb: Int = PlayerBufferConfig.DEFAULT_DISK_CACHE_SIZE_MB,
    val preloadNextEpisode: Boolean = true,
)

data class PlayerStatsState(
    val playbackStateLabel: String = "Нет данных",
    val bitrateLabel: String = "Нет данных",
    val videoSizeLabel: String = "Нет данных",
    val fpsLabel: String = "Нет данных",
    val codecLabel: String = "Нет данных",
    val bufferLabel: String = "Нет данных",
    val memoryUsedLabel: String = "Нет данных",
    val memoryAvailableLabel: String = "Нет данных",
    val diskCacheLabel: String = "Нет данных",
    val prefetchLabel: String = "Выкл",
    val droppedFramesLabel: String = "0",
    val rebufferCountLabel: String = "0",
)

enum class PlayerCompletionOverlayType {
    END_EPISODE,
    END_SEASON,
}

data class PlayerCompletionOverlay(
    val type: PlayerCompletionOverlayType,
    val title: String,
    val subtitle: String,
    val nextEpisodeLabel: String?,
    val closeLabel: String,
    val autoAdvanceEnabled: Boolean,
)

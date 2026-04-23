package ru.radiationx.anilibria.screen.player

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.player.EpisodePlaybackRules
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
) : LifecycleViewModel() {

    val videoData = MutableStateFlow<Video?>(null)
    val qualityState = MutableStateFlow<PlayerQuality?>(null)
    val speedState = MutableStateFlow<Float?>(null)
    val controlsState = MutableStateFlow(PlayerControlsState())
    val composeMenuState = MutableStateFlow(PlayerComposeMenuState())
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
        ) { speedPair, settings ->
            composeMenuState.value = composeMenuState.value.copy(
                availableSpeeds = speedPair.first,
                selectedSpeed = speedPair.second,
                settings = settings,
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
        viewModelScope.launch {
            val autoPlay = pendingAutoPlay
            pendingAutoPlay = false
            Log.d(TAG, "prepare episode=${currentEpisode?.id} duration=$lastKnownDuration autoPlay=$autoPlay watched=$watchedReached")
            playAction.emit(true)
        }
    }

    fun onPlaybackProgress(position: Long, duration: Long, isPlaying: Boolean) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
        markWatchedIfNeeded(reason = "progress")
        if (isEpisodeActuallyFinished(lastKnownPosition, lastKnownDuration)) {
            handleEpisodeCompletion("progress")
            return
        }
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
        Log.d(TAG, "end source=$source episode=${currentEpisode?.id} position=$lastKnownPosition duration=$lastKnownDuration watched=$watchedReached")
        val nextEpisode = getNextEpisode()
        if (nextEpisode != null && preferencesHolder.playerAutoplay.value) {
            Log.d(TAG, "auto-next episode=${currentEpisode?.id} -> ${nextEpisode.id}")
            transitionToEpisode(
                episode = nextEpisode,
                reason = "auto_next:$source",
                force = true,
                autoPlay = true,
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

    private fun getNextEpisode(): Episode? =
        currentEpisodes.getOrNull(getCurrentEpisodeIndex() + 1)

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

    private suspend fun syncCurrentEpisodeNow(force: Boolean, reason: String) {
        progressSyncJob?.cancelAndJoin()
        progressSyncJob = null
        sendCurrentEpisodeSync(force = force, reason = reason)
    }

    private suspend fun sendCurrentEpisodeSync(force: Boolean, reason: String) {
        val episode = currentEpisode ?: return
        val position = lastKnownPosition
        if (position < 0L) {
            return
        }
        val duration = lastKnownDuration
        val sendWatched = watchedReached && !watchedSynced
        val sendProgress = !watchedReached
        if (!force && !progressDirty && !sendWatched) {
            return
        }
        if (!sendProgress && !sendWatched) {
            progressDirty = false
            return
        }
        val now = System.currentTimeMillis()
        if (!force &&
            !sendWatched &&
            position == lastSyncedPosition &&
            duration == lastSyncedDuration &&
            now - lastSyncedAt < DUPLICATE_GUARD_MS
        ) {
            progressDirty = false
            lastPeriodicSyncAt = now
            return
        }
        Log.d(
            TAG,
            "progress-sync reason=$reason episode=${episode.id} position=$position duration=$duration sendProgress=$sendProgress sendWatched=$sendWatched"
        )
        releaseInteractor.setAccessSeek(
            id = episode.id,
            serverId = episode.serverId,
            seek = position,
            duration = duration.takeIf { it > 0L },
            forceViewed = sendWatched,
        )
        if (currentEpisode?.id == episode.id) {
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

    private fun playEpisode(episode: Episode, force: Boolean = false, autoPlay: Boolean = false) {
        progressSyncJob?.cancel()
        completionOverlay.value = null
        currentEpisode = episode
        watchedReached = false
        watchedSynced = false
        completionHandled = false
        pendingAutoPlay = autoPlay
        lastKnownPosition = 0
        lastKnownDuration = 0
        progressDirty = false
        lastSyncedPosition = Long.MIN_VALUE
        lastSyncedDuration = Long.MIN_VALUE
        lastSyncedAt = 0
        lastPeriodicSyncAt = System.currentTimeMillis()
        updateQuality()
        updateEpisode(force)
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

    private fun updateEpisode(force: Boolean = false) {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        val quality = currentQuality ?: return
        viewModelScope.launch {
            val newUrl = episode.qualityInfo.getSafeUrlFor(quality)
            val access = releaseInteractor.getAccess(episode.id)
            val initialSeek = if (access?.isViewed == true) 0L else access?.seek ?: 0L
            watchedReached = access?.isViewed == true
            watchedSynced = access?.isViewed == true
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
                "episode-load episode=${episode.id} seek=$initialSeek watched=$watchedReached autoPlay=$pendingAutoPlay"
            )
            val newVideo = Video(
                url = newUrl,
                seek = initialSeek,
                title = release.title.orEmpty(),
                subtitle = episode.title.orEmpty(),
                episode.skips
            )
            if (force || videoData.value?.url != newVideo.url) {
                videoData.value = newVideo
            }
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
        completionOverlay.value = PlayerCompletionOverlay(
            type = PlayerCompletionOverlayType.END_SEASON,
            title = "Сезон закончился",
            subtitle = "Следующей серии нет. Можно закрыть плеер.",
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
        private const val PERIODIC_SYNC_MS = 5 * 60 * 1_000L
        private const val DUPLICATE_GUARD_MS = 2_000L
    }
}

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
    val forwardBufferSeconds: Int = 50,
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

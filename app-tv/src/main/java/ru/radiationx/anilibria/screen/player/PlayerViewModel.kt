package ru.radiationx.anilibria.screen.player

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var currentComplete: Boolean? = null
    private var progressSyncJob: Job? = null
    private var lastKnownPosition = 0L
    private var lastKnownDuration = 0L
    private var progressDirty = false
    private var syncQueued = false
    private var syncForceRequested = false
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
                        if (getCurrentEpisodeIndex().let { currentIndex ->
                                currentIndex != -1 && currentEpisodes.indexOf(it) > currentIndex
                            }
                        ) {
                            maybeCompleteCurrentEpisodeOnForwardSwitch()
                            flushProgress(force = true)
                        }
                        playEpisode(it, true)
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
        playerController.reset()
    }

    private fun getCurrentRelease(): Release? {
        val episodeId = currentEpisode?.id ?: return null
        return currentReleases?.find { it.id == episodeId.releaseId }
    }

    fun onPauseClick(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
    }

    fun onStopClick(position: Long, duration: Long) {
        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
    }

    fun onNextClick(position: Long, duration: Long) {
        getNextEpisode()?.also {
            updatePlaybackSnapshot(position, duration)
            maybeCompleteCurrentEpisodeOnForwardSwitch()
            flushProgress(force = true)
            playEpisode(it)
        }
    }

    fun onPrevClick(position: Long, duration: Long) {
        getPrevEpisode()?.also {
            updatePlaybackSnapshot(position, duration)
            flushProgress(force = true)
            playEpisode(it)
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
                if (getCurrentEpisodeIndex().let { currentIndex ->
                        currentIndex != -1 && currentEpisodes.indexOf(it) > currentIndex
                    }
                ) {
                    maybeCompleteCurrentEpisodeOnForwardSwitch()
                }
                flushProgress(force = true)
                playEpisode(it, true)
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
        if (currentComplete == true) return
        currentComplete = true

        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
        val nextEpisode = getNextEpisode()
        if (nextEpisode != null && preferencesHolder.playerAutoplay.value) {
            playEpisode(nextEpisode, autoPlay = true)
        } else if (nextEpisode != null) {
            showCompletionOverlay(hasNextEpisode = true)
        } else {
            showCompletionOverlay(hasNextEpisode = false)
        }
    }

    fun onEndingSkipped(position: Long, duration: Long) {
        val episode = currentEpisode ?: return
        currentComplete = true
        updatePlaybackSnapshot(position, duration)
        progressDirty = false
        syncQueued = false
        syncForceRequested = false
        progressSyncJob?.cancel()
        progressSyncJob = viewModelScope.launch {
            releaseInteractor.setAccessSeek(
                id = episode.id,
                serverId = episode.serverId,
                seek = position,
                duration = duration.takeIf { it > 0L },
                forceViewed = true,
            )
            lastSyncedPosition = position
            lastSyncedDuration = duration
            lastSyncedAt = System.currentTimeMillis()
            lastPeriodicSyncAt = lastSyncedAt
        }
    }

    fun onPrepare(duration: Long) {
        lastKnownDuration = duration
        val episode = currentEpisode ?: return
        viewModelScope.launch {
            val access = releaseInteractor.getAccess(episode.id)
            val complete = access?.isViewed == true
            if (currentComplete == complete) return@launch
            currentComplete = complete
            if (pendingAutoPlay && !complete) {
                pendingAutoPlay = false
                playAction.emit(true)
                return@launch
            }
            if (complete) {
                pendingAutoPlay = false
                playAction.emit(false)
                val nextEpisode = getNextEpisode()
                showCompletionOverlay(hasNextEpisode = nextEpisode != null)
            } else {
                pendingAutoPlay = false
                playAction.emit(true)
            }
        }
    }

    private fun getNextEpisode(): Episode? =
        currentEpisodes.getOrNull(getCurrentEpisodeIndex() + 1)

    private fun getPrevEpisode(): Episode? =
        currentEpisodes.getOrNull(getCurrentEpisodeIndex() - 1)

    private fun getCurrentEpisodeIndex(): Int =
        currentEpisodes.indexOfFirst { it.id == currentEpisode?.id }

    fun onPlaybackProgress(position: Long, duration: Long, isPlaying: Boolean) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
        if (!isPlaying || currentComplete == true) {
            return
        }
        val now = System.currentTimeMillis()
        if (progressDirty && now - lastPeriodicSyncAt >= PERIODIC_SYNC_MS) {
            flushProgress()
        }
    }

    fun onSeek(position: Long, duration: Long) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
    }

    private fun flushProgress(force: Boolean = false) {
        if (!force && !progressDirty) {
            return
        }
        syncQueued = true
        syncForceRequested = syncForceRequested || force
        if (progressSyncJob?.isActive == true) {
            return
        }
        progressSyncJob = viewModelScope.launch {
            while (syncQueued) {
                syncQueued = false
                val episode = currentEpisode ?: break
                val position = lastKnownPosition
                if (position < 0) {
                    continue
                }
                val duration = lastKnownDuration
                val forceSync = syncForceRequested
                syncForceRequested = false
                val now = System.currentTimeMillis()
                if (!forceSync &&
                    !progressDirty
                ) {
                    continue
                }
                if (!forceSync &&
                    position == lastSyncedPosition &&
                    duration == lastSyncedDuration &&
                    now - lastSyncedAt < DUPLICATE_GUARD_MS
                ) {
                    progressDirty = false
                    lastPeriodicSyncAt = now
                    continue
                }
                releaseInteractor.setAccessSeek(episode.id, episode.serverId, position, duration)
                lastSyncedPosition = position
                lastSyncedDuration = duration
                lastSyncedAt = System.currentTimeMillis()
                lastPeriodicSyncAt = lastSyncedAt
                progressDirty = false
            }
        }
    }

    private fun updatePlaybackSnapshot(position: Long, duration: Long) {
        if (lastKnownPosition != position || lastKnownDuration != duration) {
            progressDirty = true
        }
        lastKnownPosition = position
        lastKnownDuration = duration
    }

    private fun maybeCompleteCurrentEpisodeOnForwardSwitch() {
        if (currentComplete == true) {
            return
        }
        val duration = lastKnownDuration
        if (duration <= 0L) {
            return
        }
        val progress = lastKnownPosition.toDouble() / duration.toDouble()
        if (progress >= NEXT_SWITCH_VIEWED_THRESHOLD) {
            currentComplete = true
            lastKnownPosition = duration
        }
    }

    private fun playEpisode(episode: Episode, force: Boolean = false, autoPlay: Boolean = false) {
        progressSyncJob?.cancel()
        completionOverlay.value = null
        currentEpisode = episode
        currentComplete = null
        pendingAutoPlay = autoPlay
        lastKnownPosition = 0
        lastKnownDuration = 0
        progressDirty = false
        syncQueued = false
        syncForceRequested = false
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
            lastKnownPosition = access?.seek ?: 0
            lastKnownDuration = 0
            progressDirty = false
            lastSyncedPosition = lastKnownPosition
            lastSyncedDuration = 0
            lastSyncedAt = System.currentTimeMillis()
            lastPeriodicSyncAt = lastSyncedAt
            val newVideo = Video(
                url = newUrl,
                seek = access?.seek ?: 0,
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

    private fun showCompletionOverlay(hasNextEpisode: Boolean) {
        val nextEpisode = getNextEpisode()
        completionOverlay.value = if (hasNextEpisode && nextEpisode != null) {
            val nextEpisodeTitle = nextEpisode.title?.takeIf { it.isNotBlank() } ?: "Следующая серия"
            PlayerCompletionOverlay(
                type = PlayerCompletionOverlayType.END_EPISODE,
                title = "Серия закончилась",
                subtitle = "Следом будет: $nextEpisodeTitle",
                nextEpisodeLabel = "Следующая серия",
                closeLabel = "Закрыть",
                autoAdvanceEnabled = true,
            )
        } else {
            PlayerCompletionOverlay(
                type = PlayerCompletionOverlayType.END_SEASON,
                title = "Сезон закончился",
                subtitle = "Следующей серии нет. Можно закрыть плеер.",
                nextEpisodeLabel = null,
                closeLabel = "Закрыть",
                autoAdvanceEnabled = false,
            )
        }
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
        private const val PERIODIC_SYNC_MS = 5 * 60 * 1_000L
        private const val DUPLICATE_GUARD_MS = 2_000L
        private const val NEXT_SWITCH_VIEWED_THRESHOLD = 0.8
    }
}

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

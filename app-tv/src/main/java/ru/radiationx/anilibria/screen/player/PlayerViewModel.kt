package ru.radiationx.anilibria.screen.player

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.PlayerEndEpisodeGuidedScreen
import ru.radiationx.anilibria.screen.PlayerEndSeasonGuidedScreen
import ru.radiationx.anilibria.screen.PlayerEpisodesGuidedScreen
import ru.radiationx.anilibria.screen.PlayerQualityGuidedScreen
import ru.radiationx.anilibria.screen.PlayerSettingsGuidedScreen
import ru.radiationx.anilibria.screen.PlayerSpeedGuidedScreen
import ru.radiationx.data.datasource.holders.PreferencesHolder
import ru.radiationx.data.entity.common.PlayerQuality
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.Release
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
    private val guidedRouter: GuidedRouter,
    private val playerController: PlayerController,
) : LifecycleViewModel() {

    val videoData = MutableStateFlow<Video?>(null)
    val qualityState = MutableStateFlow<PlayerQuality?>(null)
    val speedState = MutableStateFlow<Float?>(null)
    val playAction = EventFlow<Boolean>()
    val settingsOverlayVisible = playerController.settingsOverlayVisible

    private var currentEpisodes = mutableListOf<Episode>()
    private var currentReleases: List<Release>? = null
    private var currentEpisode: Episode? = null
    private var currentQuality: PlayerQuality? = null
    private var currentComplete: Boolean? = null
    private var pendingSeekSyncJob: Job? = null
    private var lastKnownPosition = 0L
    private var lastKnownDuration = 0L
    private var lastSyncedPosition = Long.MIN_VALUE
    private var lastSyncedDuration = Long.MIN_VALUE
    private var lastSyncedAt = 0L
    private var heartbeatAnchorPosition = 0L
    private var seekHeartbeatSuppressedUntil = 0L

    init {
        playerController.reset()
        currentQuality = PlayerQuality.FULLHD
        qualityState.value = PlayerQuality.FULLHD
        speedState.value = preferencesHolder.playSpeed.value

        playerController
            .selectEpisodeRelay
            .onEach { episodeId ->
                currentEpisodes
                    .firstOrNull { it.id == episodeId }
                    ?.also { playEpisode(it, true) }
            }
            .launchIn(viewModelScope)

        preferencesHolder.playerQuality.value = PlayerQuality.FULLHD
        preferencesHolder
            .playerQuality
            .onEach {
                currentQuality = it
                updateQuality()
                updateEpisode()
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
                currentEpisodes.addAll(releases.flatMap { it.episodes.reversed() })
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
        pendingSeekSyncJob?.cancel()
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

    fun onEpisodesClick(position: Long, duration: Long) {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
        guidedRouter.open(PlayerEpisodesGuidedScreen(release.id, episode.id))
    }


    fun onQualityClick(position: Long, duration: Long) {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
        guidedRouter.open(PlayerQualityGuidedScreen(release.id, episode.id))
    }

    fun onSpeedClick() {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        guidedRouter.open(PlayerSpeedGuidedScreen(release.id, episode.id))
    }

    fun onSettingsClick(position: Long, duration: Long) {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
        guidedRouter.open(PlayerSettingsGuidedScreen(release.id, episode.id))
    }

    fun onComplete(position: Long, duration: Long) {
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        if (currentComplete == true) return
        currentComplete = true

        updatePlaybackSnapshot(position, duration)
        flushProgress(force = true)
        val nextEpisode = getNextEpisode()
        if (nextEpisode != null && preferencesHolder.playerAutoplay.value) {
            playEpisode(nextEpisode)
        } else if (nextEpisode != null) {
            guidedRouter.open(PlayerEndEpisodeGuidedScreen(release.id, episode.id))
        } else {
            guidedRouter.open(PlayerEndSeasonGuidedScreen(release.id, episode.id))
        }
    }

    fun onPrepare(duration: Long) {
        lastKnownDuration = duration
        val release = getCurrentRelease() ?: return
        val episode = currentEpisode ?: return
        viewModelScope.launch {
            val access = releaseInteractor.getAccess(episode.id)
            val complete = access?.isViewed == true
            if (currentComplete == complete) return@launch
            currentComplete = complete
            if (complete) {
                playAction.emit(false)
                val nextEpisode = getNextEpisode()
                if (nextEpisode == null) {
                    guidedRouter.open(PlayerEndSeasonGuidedScreen(release.id, episode.id))
                } else {
                    guidedRouter.open(PlayerEndEpisodeGuidedScreen(release.id, episode.id))
                }
            } else {
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
        if (now < seekHeartbeatSuppressedUntil) {
            return
        }
        if (kotlin.math.abs(position - heartbeatAnchorPosition) >= HEARTBEAT_STEP_MS) {
            flushProgress()
        }
    }

    fun onSeek(position: Long, duration: Long) {
        if (position < 0) {
            return
        }
        updatePlaybackSnapshot(position, duration)
        seekHeartbeatSuppressedUntil = System.currentTimeMillis() + SEEK_DEBOUNCE_MS
        pendingSeekSyncJob?.cancel()
        pendingSeekSyncJob = viewModelScope.launch {
            delay(SEEK_DEBOUNCE_MS)
            flushProgress()
        }
    }

    private fun flushProgress(force: Boolean = false) {
        val episode = currentEpisode ?: return
        val position = lastKnownPosition
        if (position < 0) {
            return
        }
        val duration = lastKnownDuration
        val now = System.currentTimeMillis()
        if (!force &&
            position == lastSyncedPosition &&
            duration == lastSyncedDuration &&
            now - lastSyncedAt < DUPLICATE_GUARD_MS
        ) {
            return
        }
        pendingSeekSyncJob?.cancel()
        lastSyncedPosition = position
        lastSyncedDuration = duration
        lastSyncedAt = now
        heartbeatAnchorPosition = position
        viewModelScope.launch {
            releaseInteractor.setAccessSeek(episode.id, episode.serverId, position, duration)
        }
    }

    private fun updatePlaybackSnapshot(position: Long, duration: Long) {
        lastKnownPosition = position
        lastKnownDuration = duration
    }

    private fun playEpisode(episode: Episode, force: Boolean = false) {
        pendingSeekSyncJob?.cancel()
        currentEpisode = episode
        currentComplete = null
        lastKnownPosition = 0
        lastKnownDuration = 0
        lastSyncedPosition = Long.MIN_VALUE
        lastSyncedDuration = Long.MIN_VALUE
        lastSyncedAt = 0
        heartbeatAnchorPosition = 0
        seekHeartbeatSuppressedUntil = 0
        updateQuality()
        updateEpisode(force)
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
            heartbeatAnchorPosition = access?.seek ?: 0
            lastKnownPosition = access?.seek ?: 0
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

    private companion object {
        private const val HEARTBEAT_STEP_MS = 10_000L
        private const val SEEK_DEBOUNCE_MS = 1_500L
        private const val DUPLICATE_GUARD_MS = 2_000L
    }
}

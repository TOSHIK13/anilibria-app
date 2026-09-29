package ru.radiationx.anilibria.screen.details

import ru.radiationx.data.system.LoadTiming
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.DetailDataConverter
import ru.radiationx.anilibria.common.DetailRatings
import ru.radiationx.anilibria.common.DetailsState
import ru.radiationx.anilibria.common.LibriaDetails
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.AuthGuidedScreen
import ru.radiationx.anilibria.screen.DetailCollectionGuidedScreen
import ru.radiationx.anilibria.screen.DetailOtherGuidedScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.PlayerEpisodesGuidedScreen
import ru.radiationx.anilibria.screen.PlayerScreen
import ru.radiationx.anilibria.screen.player.PlayerController
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo
import ru.radiationx.data.entity.domain.release.EpisodeAccess
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.data.repository.ScheduleRepository
import ru.radiationx.data.repository.FavoriteRepository
import ru.radiationx.data.external.AniListMediaLookup
import ru.radiationx.data.external.AniListService
import ru.radiationx.data.external.AniListTokens
import ru.radiationx.data.external.ExternalSyncState
import ru.radiationx.data.external.ExternalTokenStore
import ru.radiationx.data.tracker.TrackerState
import ru.radiationx.anilibria.common.DetailCollectionSync
import ru.radiationx.anilibria.common.SyncKind
import ru.radiationx.anilibria.screen.services.progressText
import ru.radiationx.anilibria.screen.services.remoteStatusText
import ru.radiationx.shared.ktx.EventFlow
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

class DetailHeaderViewModel @Inject constructor(
    argExtra: DetailExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val favoriteRepository: FavoriteRepository,
    private val authRepository: AuthRepository,
    private val converter: DetailDataConverter,
    private val router: Router,
    private val guidedRouter: GuidedRouter,
    private val playerController: PlayerController,
    private val collectionRepository: CollectionRepository,
    private val scheduleRepository: ScheduleRepository,
    private val syncState: ExternalSyncState,
    private val tokenStore: ExternalTokenStore,
    private val mediaLookup: AniListMediaLookup,
) : LifecycleViewModel() {

    private val releaseId = argExtra.id

    val releaseData = MutableStateFlow<LibriaDetails?>(null)
    val progressState = MutableStateFlow(DetailsState())

    /** Открыть полное описание поверх экрана. */
    val descriptionEvent = EventFlow<DetailDescription>()

    /** Открыть «★ Оценки»: название релиза + данные. */
    val ratingsEvent = EventFlow<Pair<String, DetailRatings>>()

    private var currentRelease: Release? = null
    private var currentAccesses: List<EpisodeAccess> = emptyList()
    private var currentCollection: CollectionType? = null
    private var collectionSync: DetailCollectionSync? = null
    private var scheduleInfo: ReleaseScheduleInfo? = null
    private var scheduleRequested = false
    private var isFullLoaded = false

    private var selectEpisodeJob: Job? = null
    private var favoriteDisposable: Job? = null

    private val timingStart = LoadTiming.now()

    init {
        LoadTiming.mark("details", "open", "id=${releaseId.id}")
        updateProgress()
        releaseInteractor.getItem(releaseId)?.also {
            LoadTiming.span("details", "release_cached", timingStart, "poster=${it.poster?.substringAfterLast('/')}")
            updateRelease(it, emptyList())
        }
        combine(
            releaseInteractor.observeFull(releaseId),
            releaseInteractor.observeAccesses(releaseId)
        ) { release, accesses ->
            if (!isFullLoaded) {
                LoadTiming.span("details", "release_full", timingStart, "poster=${release.poster?.substringAfterLast('/')}")
            }
            isFullLoaded = true
            updateRelease(release, accesses)
        }.launchIn(viewModelScope)

        collectionRepository
            .observeCollectionIds()
            .map { it?.get(releaseId) }
            .distinctUntilChanged()
            .onEach {
                currentCollection = it
                rebuildDetails()
            }
            .launchIn(viewModelScope)

        // значок и подсказка синхронизации коллекции с AniList (только при подключённом сервисе)
        combine(
            tokenStore.observe(AniListService.ID),
            syncState.observeRelease(AniListService.ID, releaseId.id),
        ) { token, sync ->
            val linked = AniListTokens.state(token, System.currentTimeMillis()) !is TrackerState.NotLinked
            if (!linked || sync == null) return@combine null
            val kind = when {
                sync.error != null -> SyncKind.ERROR
                sync.pending -> SyncKind.PENDING
                sync.syncedAt > 0 -> SyncKind.SYNCED
                else -> return@combine null
            }
            val media = mediaLookup.cached(sync.malId)
            DetailCollectionSync(
                kind = kind,
                statusText = remoteStatusText(sync.remoteStatus),
                progressText = progressText(
                    sync.remoteProgress,
                    media?.episodes ?: currentRelease?.series?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                    media?.isMovie == true,
                ),
                syncedAt = sync.syncedAt,
            )
        }
            .distinctUntilChanged()
            .onEach {
                collectionSync = it
                rebuildDetails()
            }
            .launchIn(viewModelScope)
    }

    override fun onResume() {
        super.onResume()

        selectEpisodeJob?.cancel()
        selectEpisodeJob = playerController
            .selectEpisodeRelay
            .onEach { episodeId ->
                router.navigateTo(PlayerScreen(releaseId, episodeId))
            }
            .launchIn(viewModelScope)
    }

    override fun onPause() {
        super.onPause()
        selectEpisodeJob?.cancel()
    }

    fun onContinueClick() {
        viewModelScope.launch {
            val release = currentRelease ?: return@launch
            // Последняя серия досмотрена — продолжаем со следующей, если она есть.
            val target = converter.continueTarget(release, releaseInteractor.getAccesses(releaseId))
                ?: return@launch
            router.navigateTo(PlayerScreen(releaseId, target.episodeId))
        }
    }

    fun onPlayClick() {
        val release = currentRelease ?: return
        if (release.episodes.isEmpty()) return

        viewModelScope.launch {
            if (release.episodes.size == 1) {
                router.navigateTo(PlayerScreen(releaseId, null))
            } else {
                val episodeId =
                    releaseInteractor.getAccesses(releaseId).maxByOrNull { it.lastAccessRaw }?.id
                guidedRouter.open(PlayerEpisodesGuidedScreen(releaseId, episodeId))
            }
        }
    }

    fun onFavoriteClick() {
        val release = currentRelease ?: return

        favoriteDisposable?.cancel()
        favoriteDisposable = viewModelScope.launch {
            if (authRepository.getAuthState() != AuthState.AUTH) {
                guidedRouter.open(AuthGuidedScreen())
                return@launch
            }
            coRunCatching {
                if (release.favoriteInfo.isAdded) {
                    favoriteRepository.deleteFavorite(releaseId, release.favoriteInfo)
                } else {
                    favoriteRepository.addFavorite(releaseId, release.favoriteInfo)
                }
            }.onSuccess { favoriteInfo ->
                currentRelease?.also { data ->
                    val newData = data.copy(
                        favoriteInfo = favoriteInfo
                    )
                    releaseInteractor.updateFullCache(newData)
                }
            }.onFailure {
                Timber.e(it)
            }
        }.apply {
            invokeOnCompletion { updateProgress() }
        }

        updateProgress()
    }

    fun onDescriptionClick() {
        val details = releaseData.value ?: return
        if (details.description.isBlank()) return
        descriptionEvent.emit(DetailDescription(details.titleRu, details.description))
    }

    fun onRatingsClick() {
        val details = releaseData.value ?: return
        val ratings = details.ratings ?: return
        ratingsEvent.emit(details.titleRu to ratings)
    }

    fun onCollectionClick() {
        viewModelScope.launch {
            if (authRepository.getAuthState() != AuthState.AUTH) {
                guidedRouter.open(AuthGuidedScreen())
                return@launch
            }
            guidedRouter.open(DetailCollectionGuidedScreen(releaseId))
        }
    }

    fun onOtherClick() {
        guidedRouter.open(DetailOtherGuidedScreen(releaseId))
    }

    private fun updateRelease(release: Release, accesses: List<EpisodeAccess>) {
        currentRelease = release
        currentAccesses = accesses
        rebuildDetails()
        updateProgress()
        requestScheduleInfo(release)
    }

    private fun rebuildDetails() {
        val release = currentRelease ?: return
        releaseData.value = converter.toDetail(
            release,
            isFullLoaded,
            currentAccesses,
            currentCollection,
            scheduleInfo,
            collectionSync
        )
    }

    /** Расписание нужно только онгоингам; грузится один раз (кэш в репозитории), ошибка — без плашки. */
    private fun requestScheduleInfo(release: Release) {
        if (scheduleRequested || release.statusCode != Release.STATUS_CODE_PROGRESS) return
        scheduleRequested = true
        viewModelScope.launch {
            coRunCatching {
                scheduleRepository.getScheduleInfo()[releaseId]
            }.onSuccess {
                scheduleInfo = it
                rebuildDetails()
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private fun updateProgress() {
        progressState.value = DetailsState(
            currentRelease == null,
            currentRelease == null || favoriteDisposable?.isActive ?: false
        )
    }
}

data class DetailDescription(
    val title: String,
    val text: String,
)

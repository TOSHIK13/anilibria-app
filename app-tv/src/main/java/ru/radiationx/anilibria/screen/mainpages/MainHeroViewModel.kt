package ru.radiationx.anilibria.screen.mainpages

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.ScheduleRepository
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.data.repository.watch.WatchHistoryEpisode
import ru.radiationx.data.repository.watch.WatchHistoryLogic
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Hero-блок «Главной», «Я смотрю» и «Коллекций»: карточка в фокусе → [heroData].
 * Смена — после паузы фокуса [SELECT_DEBOUNCE_MS], чтобы при быстрой прокрутке фон не мигал.
 * Общий для страниц (живёт во [MainPagesFragment]), поэтому на вкладках остаётся последняя карточка.
 */
@OptIn(FlowPreview::class)
class MainHeroViewModel @Inject constructor(
    private val releaseInteractor: ReleaseInteractor,
    private val scheduleRepository: ScheduleRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val converter: MainHeroConverter,
) : LifecycleViewModel() {

    private companion object {
        const val SELECT_DEBOUNCE_MS = 300L
        const val RELEASE_TIMEOUT_MS = 10_000L

        /** Сколько ждать полный релиз и расписание, прежде чем показать данные из списка. */
        const val FULL_WAIT_MS = 700L
    }

    val heroData = MutableStateFlow<MainHeroState?>(null)

    private val selection = MutableStateFlow<LibriaCard?>(null)

    /** История просмотра по serverId серии (для прогресса у обычных карточек). */
    private var historyEpisodes: Map<String, WatchHistoryEpisode> = emptyMap()

    init {
        watchProgressRepository
            .observeEpisodes()
            .onEach { historyEpisodes = it }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            selection
                .filterNotNull()
                // Первую карточку показываем сразу, дальше — когда фокус остановился.
                .debounce { if (heroData.value == null) 0L else SELECT_DEBOUNCE_MS }
                .collectLatest { card ->
                    coRunCatching { load(card) }.onFailure { Timber.e(it) }
                }
        }
    }

    /** Карточка в фокусе. Служебные карточки («Загрузить ещё», загрузка) hero не меняют. */
    fun onItemSelected(item: Any?) {
        if (item is LibriaCard) {
            selection.value = item
        }
    }

    private suspend fun load(card: LibriaCard) {
        val releaseId = (card.type as? LibriaCard.Type.Release)?.releaseId
        if (releaseId == null) {
            heroData.value = converter.fromCard(card, null)
            return
        }

        val item = releaseInteractor.getItem(releaseId)
        // Полный релиз нужен ради широкого фона, возраста, длительности и названий серий
        // (в списках их нет). Запросы не отменяются вместе с выбором: результат кэшируется.
        val fullRequest = viewModelScope.async { loadFull(releaseId) }
        val scheduleRequest = viewModelScope.async { loadScheduleInfo(releaseId) }

        // Недолго ждём полный релиз, чтобы не показывать фон и тексты дважды.
        val full = withTimeoutOrNull(if (item != null) FULL_WAIT_MS else RELEASE_TIMEOUT_MS) {
            fullRequest.await()
        }
        var release: Release = full ?: item ?: run {
            heroData.value = converter.fromCard(card, progressOf(card, null))
            return
        }
        var schedule = if (converter.isOngoing(release)) {
            withTimeoutOrNull(FULL_WAIT_MS) { scheduleRequest.await() }
        } else {
            null
        }
        emit(card, release, schedule)

        if (full == null) {
            release = fullRequest.await() ?: release
            emit(card, release, schedule)
        }
        if (schedule == null && converter.isOngoing(release)) {
            schedule = scheduleRequest.await() ?: return
            emit(card, release, schedule)
        }
    }

    private suspend fun loadScheduleInfo(releaseId: ReleaseId): ReleaseScheduleInfo? = coRunCatching {
        scheduleRepository.getScheduleInfo()[releaseId]
    }.onFailure { Timber.e(it) }.getOrNull()

    private fun emit(
        card: LibriaCard,
        release: Release,
        scheduleInfo: ReleaseScheduleInfo?,
    ) {
        heroData.value = converter.fromRelease(card, release, progressOf(card, release), scheduleInfo)
    }

    private suspend fun loadFull(releaseId: ReleaseId): Release? = coRunCatching {
        withTimeoutOrNull(RELEASE_TIMEOUT_MS) { releaseInteractor.getFull(releaseId) }
    }.onFailure { Timber.e(it) }.getOrNull()

    /**
     * Прогресс: у карточки «Продолжить просмотр» — её серия, у остальных — последняя
     * недосмотренная серия релиза из истории.
     */
    private fun progressOf(card: LibriaCard, release: Release?): MainHeroProgress? {
        val info = card.continueInfo
        if (info != null) {
            val episode = release?.episodes?.find { it.id == info.episodeId }
            return MainHeroProgress(
                ordinalLabel = info.ordinalLabel,
                episodeName = converter.episodeDisplayName(episode?.title),
                positionSec = info.positionSec,
                durationSec = info.durationSec ?: episode?.durationSec?.takeIf { it > 0 },
            )
        }
        val releaseId = (card.type as? LibriaCard.Type.Release)?.releaseId ?: return null
        val last = historyEpisodes.values
            .filter { it.releaseId == releaseId.id }
            .maxByOrNull { it.updatedAt }
            ?: return null
        val position = last.time?.toInt() ?: 0
        if (last.isWatched || position <= 0) return null
        val episode = release?.episodes?.find { it.serverId == last.episodeId }
        return MainHeroProgress(
            ordinalLabel = last.ordinal?.let { WatchHistoryLogic.ordinalLabel(it) } ?: episode?.id?.id,
            episodeName = converter.episodeDisplayName(episode?.title),
            positionSec = position,
            durationSec = episode?.durationSec?.takeIf { it > 0 },
        )
    }
}

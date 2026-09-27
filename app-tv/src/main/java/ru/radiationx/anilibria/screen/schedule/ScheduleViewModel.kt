package ru.radiationx.anilibria.screen.schedule

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.screen.DetailsScreen
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.schedule.ReleaseScheduleInfo
import ru.radiationx.data.entity.domain.schedule.ScheduleDay
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.ScheduleRepository
import ru.radiationx.shared.ktx.asMsk
import ru.radiationx.shared.ktx.getDayOfWeek
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

/**
 * «Расписание» — неделя из 7 колонок, первая — сегодня по МСК.
 * Живёт в [ru.radiationx.anilibria.screen.mainpages.MainPagesFragment]; данные грузит сама
 * при первом показе страницы, дальше берёт из кэша репозитория (на процесс).
 */
class ScheduleViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val dataConverter: CardsDataConverter,
    private val router: Router,
) : LifecycleViewModel() {

    companion object {
        /** Строк в свёрнутой колонке, остальные — за «ещё N». */
        const val COLLAPSED_ROWS = 6

        private val shortNames = mapOf(
            Calendar.MONDAY to "Пн",
            Calendar.TUESDAY to "Вт",
            Calendar.WEDNESDAY to "Ср",
            Calendar.THURSDAY to "Чт",
            Calendar.FRIDAY to "Пт",
            Calendar.SATURDAY to "Сб",
            Calendar.SUNDAY to "Вс",
        )
    }

    sealed interface State {
        data object Loading : State
        data object Error : State
        data class Content(val days: List<WeekDay>) : State
    }

    /** Колонка недели. [day] — [Calendar.DAY_OF_WEEK]. */
    data class WeekDay(
        val day: Int,
        val title: String,
        val subtitle: String?,
        val isToday: Boolean,
        val items: List<WeekItem>,
    )

    data class WeekItem(
        val releaseId: ReleaseId,
        val title: String,
        val image: String,
        /** Номер следующей серии, null — неизвестен. */
        val nextEpisode: Int?,
        val seasonReleased: Boolean,
    )

    /** Ячейка с фокусом: день ([Calendar.DAY_OF_WEEK]) и индекс фокусируемого элемента колонки. */
    data class Cell(val day: Int, val index: Int)

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** Развёрнутые колонки («ещё N»), по дню недели. */
    private val _expandedDays = MutableStateFlow<Set<Int>>(emptySet())
    val expandedDays: StateFlow<Set<Int>> = _expandedDays

    /** Последняя ячейка с фокусом. */
    var focusedCell: Cell? = null
        private set

    /** Открыт релиз: при возврате фокус — на ту же строку, а не на начало «Сегодня». */
    var restoreFocus: Boolean = false

    private var loadJob: Job? = null

    init {
        val cached = scheduleRepository.getCachedSchedule()
        if (cached != null) {
            _state.value = State.Content(buildWeek(cached, scheduleRepository.getApiOrder(), null))
        } else {
            load()
        }
    }

    fun onRetryClick() {
        load()
    }

    fun onCellFocused(cell: Cell) {
        focusedCell = cell
    }

    fun onMoreClick(day: Int) {
        _expandedDays.value = _expandedDays.value + day
    }

    fun onItemClick(item: WeekItem) {
        restoreFocus = true
        router.navigateTo(DetailsScreen(item.releaseId))
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.value = State.Loading
            try {
                val days = scheduleRepository.loadSchedule()
                val info = scheduleRepository.getScheduleInfo()
                releaseInteractor.updateItemsCache(days.flatMap { day -> day.items.map { it.releaseItem } })
                _state.value = State.Content(buildWeek(days, scheduleRepository.getApiOrder(), info))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                Timber.e(ex)
                _state.value = State.Error
            }
        }
    }

    private fun buildWeek(
        days: List<ScheduleDay>,
        apiOrder: Map<ReleaseId, Int>,
        info: Map<ReleaseId, ReleaseScheduleInfo>?,
    ): List<WeekDay> {
        val today = System.currentTimeMillis().asMsk().getDayOfWeek()
        return (0 until 7).map { offset ->
            // Calendar.SUNDAY = 1 … Calendar.SATURDAY = 7.
            val day = (today - 1 + offset) % 7 + 1
            val short = shortNames.getValue(day)
            val items = days
                .filter { it.day == day }
                .flatMap { it.items }
                .sortedBy { apiOrder[it.releaseItem.id] ?: Int.MAX_VALUE }
                .map { item ->
                    val release = item.releaseItem
                    val card = dataConverter.toCard(release)
                    val scheduleInfo = info?.get(release.id) ?: item.scheduleInfo
                    WeekItem(
                        releaseId = release.id,
                        title = card.title,
                        image = card.image,
                        nextEpisode = scheduleInfo?.nextEpisodeNumber,
                        seasonReleased = scheduleInfo?.fullSeasonIsReleased == true,
                    )
                }
            WeekDay(
                day = day,
                title = when (offset) {
                    0 -> "Сегодня"
                    1 -> "Завтра"
                    else -> short
                },
                subtitle = short.takeIf { offset < 2 },
                isToday = offset == 0,
                items = items,
            )
        }
    }
}

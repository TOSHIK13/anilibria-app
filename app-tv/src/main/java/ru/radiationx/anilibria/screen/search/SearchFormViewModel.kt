package ru.radiationx.anilibria.screen.search

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.SearchCompletedGuidedScreen
import ru.radiationx.anilibria.screen.SearchGenreGuidedScreen
import ru.radiationx.anilibria.screen.SearchSeasonGuidedScreen
import ru.radiationx.anilibria.screen.SearchSortGuidedScreen
import ru.radiationx.anilibria.screen.SearchYearGuidedScreen
import ru.radiationx.data.entity.domain.search.SearchForm
import javax.inject.Inject

/** Значение чипа фильтра «Каталога»; [active] — выбрано не значение по умолчанию. */
data class SearchFilterChip(
    val value: String,
    val active: Boolean,
)

class SearchFormViewModel @Inject constructor(
    private val searchController: SearchController,
    private val guidedRouter: GuidedRouter,
) : LifecycleViewModel() {

    private companion object {
        const val ALL = "все"
    }

    val yearData = MutableStateFlow(SearchFilterChip(ALL, false))
    val seasonData = MutableStateFlow(SearchFilterChip(ALL, false))
    val genreData = MutableStateFlow(SearchFilterChip(ALL, false))
    val sortData = MutableStateFlow(SearchFilterChip(ALL, false))
    val onlyCompletedData = MutableStateFlow(SearchFilterChip(ALL, false))

    private var searchForm = SearchForm()

    init {
        updateDataByForm()

        searchController.yearsEvent.onEach {
            searchForm = searchForm.copy(years = it)
            updateDataByForm()
        }.launchIn(viewModelScope)

        searchController.seasonsEvent.onEach {
            searchForm = searchForm.copy(seasons = it)
            updateDataByForm()
        }.launchIn(viewModelScope)

        searchController.genresEvent.onEach {
            searchForm = searchForm.copy(genres = it)
            updateDataByForm()
        }.launchIn(viewModelScope)

        searchController.sortEvent.onEach {
            searchForm = searchForm.copy(sort = it)
            updateDataByForm()
        }.launchIn(viewModelScope)

        searchController.completedEvent.onEach {
            searchForm = searchForm.copy(onlyCompleted = it)
            updateDataByForm()
        }.launchIn(viewModelScope)
    }

    fun onYearClick() {
        guidedRouter.open(SearchYearGuidedScreen(searchForm.years.map { it.value }))
    }

    fun onSeasonClick() {
        guidedRouter.open(SearchSeasonGuidedScreen(searchForm.seasons.map { it.value }))
    }

    fun onGenreClick() {
        guidedRouter.open(SearchGenreGuidedScreen(searchForm.genres.map { it.value }))
    }

    fun onSortClick() {
        guidedRouter.open(SearchSortGuidedScreen(searchForm.sort))
    }

    fun onOnlyCompletedClick() {
        guidedRouter.open(SearchCompletedGuidedScreen(searchForm.onlyCompleted))
    }

    /** «Сбросить фильтры» на пустом результате. */
    fun onResetClick() {
        searchForm = SearchForm()
        updateDataByForm()
    }

    private fun updateDataByForm() {
        yearData.value = searchForm.years.map { it.title }.toChip()
        seasonData.value = searchForm.seasons.map { it.title }.toChip()
        genreData.value = searchForm.genres.map { it.title }.toChip()
        sortData.value = when (searchForm.sort) {
            SearchForm.Sort.RATING -> SearchFilterChip("по популярности", false)
            SearchForm.Sort.DATE -> SearchFilterChip("по новизне", true)
        }
        onlyCompletedData.value = if (searchForm.onlyCompleted) {
            SearchFilterChip("завершённые", true)
        } else {
            SearchFilterChip(ALL, false)
        }

        searchController.applyFormEvent.emit(searchForm)
    }

    /** «Романтика, Меха» или «Романтика, Меха +2». */
    private fun List<String>.toChip(take: Int = 2): SearchFilterChip {
        if (isEmpty()) {
            return SearchFilterChip(ALL, false)
        }
        var result = take(take).joinToString(", ")
        if (size > take) {
            result += " +${size - take}"
        }
        return SearchFilterChip(result, true)
    }
}

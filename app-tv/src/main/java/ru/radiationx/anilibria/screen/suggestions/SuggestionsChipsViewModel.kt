package ru.radiationx.anilibria.screen.suggestions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.mainpages.MainPagesTabsController
import ru.radiationx.data.entity.domain.release.GenreItem
import ru.radiationx.data.repository.SearchRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/** Блоки «Недавние запросы» и «Жанры» страницы «Поиск» до ввода. */
class SuggestionsChipsViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val tabsController: MainPagesTabsController,
    queriesStorage: SearchQueriesStorage,
) : LifecycleViewModel() {

    val recentQueriesData: StateFlow<List<String>> = queriesStorage.observe()

    /** Пусто — жанры не загрузились, блока нет. */
    val genresData = MutableStateFlow<List<GenreItem>>(emptyList())

    init {
        searchRepository
            .observeGenres()
            .onEach { genresData.value = it }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            coRunCatching {
                searchRepository.getGenres()
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun onGenreClick(genre: GenreItem) {
        tabsController.openCatalogWithGenre(genre)
    }

    fun onCatalogClick() {
        tabsController.openCatalog()
    }
}

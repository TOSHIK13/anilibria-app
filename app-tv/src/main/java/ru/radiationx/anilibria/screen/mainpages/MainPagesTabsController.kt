package ru.radiationx.anilibria.screen.mainpages

import ru.radiationx.anilibria.screen.search.SearchController
import ru.radiationx.data.entity.domain.release.GenreItem
import ru.radiationx.shared.ktx.EventFlow
import javax.inject.Inject

/**
 * Переключение вкладок главного экрана из страниц и их ViewModel
 * (например, кнопка поиска в «Каталоге» → вкладка «Поиск»).
 * Событие ждёт, пока [MainPagesFragment] его обработает, в том числе после возврата с другого экрана.
 * Id вкладок — [MainPagesFragmentFactory].ID_*.
 */
class MainPagesTabsController @Inject constructor(
    private val searchController: SearchController,
) {

    val openTabEvent = EventFlow<Long>()

    fun openTab(tabId: Long) {
        openTabEvent.emit(tabId)
    }

    fun openCatalog() = openTab(MainPagesFragmentFactory.ID_CATALOG)

    /** «Каталог» с фильтром только по [genre] (остальные фильтры сбрасываются). */
    fun openCatalogWithGenre(genre: GenreItem) {
        searchController.showGenreEvent.emit(genre)
        openCatalog()
    }

    fun openSchedule() = openTab(MainPagesFragmentFactory.ID_SCHEDULE)

    fun openSearch() = openTab(MainPagesFragmentFactory.ID_SEARCH)
}

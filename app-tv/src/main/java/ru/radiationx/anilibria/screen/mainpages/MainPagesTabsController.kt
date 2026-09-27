package ru.radiationx.anilibria.screen.mainpages

import ru.radiationx.shared.ktx.EventFlow
import javax.inject.Inject

/**
 * Переключение вкладок главного экрана из страниц и их ViewModel
 * (например, «Открыть полное расписание» на «Главной» → вкладка «Расписание»).
 * Событие ждёт, пока [MainPagesFragment] его обработает, в том числе после возврата с другого экрана.
 * Id вкладок — [MainPagesFragmentFactory].ID_*.
 */
class MainPagesTabsController @Inject constructor() {

    val openTabEvent = EventFlow<Long>()

    fun openTab(tabId: Long) {
        openTabEvent.emit(tabId)
    }

    fun openCatalog() = openTab(MainPagesFragmentFactory.ID_CATALOG)

    fun openSchedule() = openTab(MainPagesFragmentFactory.ID_SCHEDULE)

    fun openSearch() = openTab(MainPagesFragmentFactory.ID_SEARCH)
}

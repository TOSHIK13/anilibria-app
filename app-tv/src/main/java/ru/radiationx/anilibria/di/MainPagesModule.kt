package ru.radiationx.anilibria.di

import ru.radiationx.anilibria.screen.mainpages.MainPagesTabsController
import ru.radiationx.anilibria.screen.suggestions.SearchQueriesStorage
import ru.radiationx.quill.QuillModule

/** Общие объекты страниц главного экрана (вкладок), живут вместе с activity. */
class MainPagesModule : QuillModule() {

    init {
        single<MainPagesTabsController>()
        single<SearchQueriesStorage>()
    }
}

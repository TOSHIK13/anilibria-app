package ru.radiationx.anilibria.screen.mainpages

import androidx.fragment.app.Fragment
import androidx.leanback.widget.Row
import ru.radiationx.anilibria.common.CachedRowsFragmentFactory
import ru.radiationx.anilibria.screen.collections.CollectionsFragment
import ru.radiationx.anilibria.screen.main.MainFragment
import ru.radiationx.anilibria.screen.profile.ProfileFragment
import ru.radiationx.anilibria.screen.schedule.ScheduleFragment
import ru.radiationx.anilibria.screen.search.SearchFragment
import ru.radiationx.anilibria.screen.suggestions.SuggestionsFragment
import ru.radiationx.anilibria.screen.youtube.YoutubeFragment

class MainPagesFragmentFactory : CachedRowsFragmentFactory() {

    companion object {
        const val ID_MAIN = 1L
        // 2L — бывшая вкладка «Я смотрю» (объединена с «Главной»), id не переиспользуем.
        const val ID_SERIES = 3L
        const val ID_MOVIES = 4L
        const val ID_SEARCH = 5L
        const val ID_YOUTUBE = 6L
        const val ID_PROFILE = 7L
        const val ID_COLLECTIONS = 8L
        const val ID_CATALOG = 9L
        const val ID_SCHEDULE = 10L

        /** Страницы главного экрана, по одной на верхнюю вкладку. */
        val ids = listOf(
            ID_MAIN,
            ID_COLLECTIONS,
            ID_CATALOG,
            ID_SCHEDULE,
            ID_SEARCH,
            ID_PROFILE,
        )

        /** Верхние вкладки, в том же порядке, что и [ids]. */
        val tabIds = ids

        /** Страницы без hero-блока: у них своя вёрстка, вкладки над ними видны всегда. */
        val fullPageIds = setOf(
            ID_CATALOG,
            ID_SCHEDULE,
            ID_SEARCH,
            ID_PROFILE,
        )

        val variant1 = mapOf(
            ID_MAIN to "Главная",
            ID_SERIES to "Сериалы",
            ID_MOVIES to "Фильмы",
            ID_SEARCH to "Поиск",
            ID_YOUTUBE to "YouTube",
            ID_PROFILE to "Настройки",
            ID_COLLECTIONS to "Коллекции",
            ID_CATALOG to "Каталог",
            ID_SCHEDULE to "Расписание",
        )
    }

    override fun getFragmentByRow(row: Row): Fragment {
        return when (row.id) {
            ID_MAIN -> MainFragment()
            ID_COLLECTIONS -> CollectionsFragment()
            ID_CATALOG -> SearchFragment()
            ID_SCHEDULE -> ScheduleFragment()
            ID_SEARCH -> SuggestionsFragment()
            ID_YOUTUBE -> YoutubeFragment()
            ID_PROFILE -> ProfileFragment()
            else -> super.getFragmentByRow(row)
        }
    }
}

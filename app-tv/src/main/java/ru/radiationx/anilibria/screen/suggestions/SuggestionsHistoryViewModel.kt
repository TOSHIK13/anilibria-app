package ru.radiationx.anilibria.screen.suggestions

import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.repository.HistoryRepository
import javax.inject.Inject

/**
 * Ряд «Недавно открытые» (локальная история [HistoryRepository]).
 * Раньше жил на вкладке «Я смотрю»; перенесён сюда для страницы «Поиск» (пока не подключён).
 */
class SuggestionsHistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter
) : BaseCardsViewModel() {

    override val defaultTitle: String = "Недавно открытые"

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = historyRepository
        .getReleases()
        .items
        .let { historyItems ->
            historyItems.map { converter.toCard(it) }
        }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        false

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}
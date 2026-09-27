package ru.radiationx.anilibria.screen.suggestions

import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.entity.domain.search.SearchForm
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.SearchRepository
import javax.inject.Inject

/** Ряд «Популярное» страницы «Поиск»: каталог по рейтингу, конечный, без «Загрузить еще». */
class SuggestionsRecommendsViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter
) : BaseCardsViewModel() {

    private companion object {
        const val LIMIT = 14
    }

    override val defaultTitle: String = "Популярное"

    override val showLoadMoreCard: Boolean = false

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean = false

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = searchRepository
        .searchReleases(SearchForm(sort = SearchForm.Sort.RATING), requestPage, LIMIT)
        .also { releaseInteractor.updateItemsCache(it.data) }
        .let { result -> result.data.map { converter.toCard(it) } }

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}

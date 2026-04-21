package ru.radiationx.anilibria.screen.watching

import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.HistoryRepository
import ru.radiationx.data.repository.ReleaseRepository
import javax.inject.Inject

class WatchingRecommendsViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val releaseRepository: ReleaseRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter
) : BaseCardsViewModel() {

    override val defaultTitle: String = "Рекомендации"

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = historyRepository
        .getReleases()
        .items
        .firstOrNull()
        .let { releaseRepository.getRecommendedReleases(it?.id) }
        .also {
            releaseInteractor.updateItemsCache(it)
        }
        .let { releases ->
            releases.map { converter.toCard(it) }
        }

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }

}

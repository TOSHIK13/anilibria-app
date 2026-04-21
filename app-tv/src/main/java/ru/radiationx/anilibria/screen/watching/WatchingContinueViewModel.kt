package ru.radiationx.anilibria.screen.watching

import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.HistoryRepository
import javax.inject.Inject

class WatchingContinueViewModel @Inject constructor(
    private val releaseInteractor: ReleaseInteractor,
    private val historyRepository: HistoryRepository,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter,
) : BaseCardsViewModel() {

    override val defaultTitle: String =
        "\u041f\u0440\u043e\u0434\u043e\u043b\u0436\u0438\u0442\u044c \u043f\u0440\u043e\u0441\u043c\u043e\u0442\u0440"

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> {
        val pairs = historyRepository.getReleases().items.mapNotNull { release ->
            val lastEpisode = releaseInteractor.getAccesses(release.id).maxByOrNull { it.lastAccessRaw }
                ?: return@mapNotNull null
            release to lastEpisode
        }
        return pairs
            .sortedByDescending { it.second.lastAccessRaw }
            .map {
                converter.toCard(it.first)
                    .copy(
                        description = "\u0412\u044b \u043e\u0441\u0442\u0430\u043d\u043e\u0432\u0438\u043b\u0438\u0441\u044c \u043d\u0430 ${it.second.id.id} \u0441\u0435\u0440\u0438\u0438"
                    )
            }
    }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        false

    override fun onLibriaCardClick(card: LibriaCard) {
        super.onLibriaCardClick(card)
        cardRouter.navigate(card)
    }
}

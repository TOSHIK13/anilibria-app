package ru.radiationx.anilibria.screen.youtube

import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.data.repository.YoutubeRepository
import javax.inject.Inject

class YouTubeViewModel @Inject constructor(
    private val youtubeRepository: YoutubeRepository,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter
) : BaseCardsViewModel() {

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    private var isEnd = false

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = youtubeRepository
        .getYoutubeList(requestPage)
        .also { isEnd = it.isEnd() }
        .let { youtubeItems ->
            youtubeItems.data.map { converter.toCard(it) }
        }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean {
        return !isEnd && super.hasMoreCards(newCards, allCards)
    }

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}
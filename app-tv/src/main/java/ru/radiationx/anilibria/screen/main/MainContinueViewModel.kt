package ru.radiationx.anilibria.screen.main

import ru.radiationx.anilibria.common.ContinueWatchingCardsViewModel
import ru.radiationx.anilibria.common.ContinueWatchingLoader
import ru.radiationx.anilibria.common.LibriaCardRouter
import javax.inject.Inject

class MainContinueViewModel @Inject constructor(
    loader: ContinueWatchingLoader,
    cardRouter: LibriaCardRouter,
) : ContinueWatchingCardsViewModel(loader, cardRouter) {

    override val timingName: String = "continue"
}

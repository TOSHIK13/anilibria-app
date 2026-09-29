package ru.radiationx.anilibria.screen.details.other

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.radiationx.anilibria.common.WatchCollectionSync
import ru.radiationx.anilibria.common.fragment.GuidedRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.anilibria.screen.details.DetailExtra
import ru.radiationx.anilibria.screen.DetailScoreGuidedScreen
import ru.radiationx.data.external.AniListRatings
import ru.radiationx.data.interactors.ReleaseInteractor
import javax.inject.Inject

class DetailOtherViewModel @Inject constructor(
    private val argExtra: DetailExtra,
    private val releaseInteractor: ReleaseInteractor,
    private val guidedRouter: GuidedRouter,
    private val watchCollectionSync: WatchCollectionSync,
    private val aniListRatings: AniListRatings,
) : LifecycleViewModel() {

    /** «Оценить на AniList» доступно при входе в AniList и известном MAL id релиза. */
    fun canRate(): Boolean =
        aniListRatings.isLinked() && aniListRatings.malIdOf(argExtra.id.id) != null

    fun onRateClick() {
        guidedRouter.replace(DetailScoreGuidedScreen(argExtra.id))
    }

    fun onClearClick() {
        viewModelScope.launch {
            releaseInteractor.resetAccessHistory(argExtra.id)
            guidedRouter.close()
            // Экран уже закрыт и viewModelScope отменяется — коллекцию обновляем без отмены.
            withContext(NonCancellable) {
                watchCollectionSync.onHistoryReset(argExtra.id)
            }
        }
    }

    fun onMarkClick() {
        viewModelScope.launch {
            releaseInteractor.markAllViewed(argExtra.id)
            guidedRouter.close()
            withContext(NonCancellable) {
                watchCollectionSync.onAllMarkedViewed(argExtra.id)
            }
        }
    }
}
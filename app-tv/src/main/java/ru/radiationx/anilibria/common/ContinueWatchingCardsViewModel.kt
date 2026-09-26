package ru.radiationx.anilibria.common

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.data.repository.watch.ContinueWatchingItem

/** Ряд «Продолжить просмотр» (главная, «Я смотрю»): карточки из [ContinueWatchingLoader]. */
abstract class ContinueWatchingCardsViewModel(
    private val loader: ContinueWatchingLoader,
    private val cardRouter: LibriaCardRouter,
) : BaseCardsViewModel() {

    override val defaultTitle: String = "Продолжить просмотр"

    // Ряд обновляется при каждом возврате на экран — без мигания карточкой загрузки.
    override val progressOnRefresh: Boolean = false

    @Volatile
    private var loading = false

    @Volatile
    private var source: String? = null

    @Volatile
    private var loadedItems: List<ContinueWatchingItem>? = null

    init {
        // Свежая история с сервера (или локальный прогресс из плеера) — перестроить ряд.
        loader
            .observeItems()
            .filterNotNull()
            .filter { it != loadedItems }
            .onEach {
                while (loading) delay(RELOAD_POLL_MS)
                delay(RELOAD_POLL_MS)
                onRefreshClick()
            }
            .launchIn(viewModelScope)

        // Фоновое обновление постеров/названий из сети.
        loader
            .observeCardUpdates()
            .onEach {
                while (loading) delay(RELOAD_POLL_MS)
                delay(RELOAD_POLL_MS)
                onRefreshClick()
            }
            .launchIn(viewModelScope)
    }

    override fun onResume() {
        super.onResume()
        onRefreshClick()
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> {
        loading = true
        try {
            return loader.loadCards(
                onItems = { loadedItems = it },
                onSource = { source = it },
            )
        } finally {
            loading = false
        }
    }

    override fun timingExtra(): String? = source?.let { "source=$it" }

    // Перестраиваем ряд только при реальных изменениях (порядок, серия, постер):
    // одинаковый список не трогает адаптер, фокус не прыгает.
    override fun needsModify(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        newCards != allCards

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        false

    override fun onLibriaCardClick(card: LibriaCard) {
        super.onLibriaCardClick(card)
        cardRouter.navigate(card)
    }

    private companion object {
        const val RELOAD_POLL_MS = 50L
    }
}

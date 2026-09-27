package ru.radiationx.anilibria.screen.suggestions

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.anilibria.screen.LifecycleViewModel
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.SearchRepository
import ru.radiationx.shared_app.controllers.loadersearch.SearchLoader
import ru.radiationx.shared_app.controllers.loadersearch.SearchQuery
import javax.inject.Inject

/** Карточка результата поиска с подписью: название и «2020 · ТВ». */
data class SuggestionsPosterItem(
    val card: LibriaCard,
    val title: String? = null,
    val meta: String? = null,
)

/**
 * Поиск страницы «Поиск»: [SearchLoader] (300 мс, от 3 символов, `id1234` — по номеру).
 * Запрос живёт здесь: страница пересоздаётся при смене вкладок и возврате с релиза.
 */
class SuggestionsResultViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter,
    private val queriesStorage: SearchQueriesStorage,
) : LifecycleViewModel() {

    sealed class State {
        /** Запроса нет (меньше 3 символов) — блоки до ввода. */
        object Idle : State()
        data class Loading(val query: String) : State()
        data class Content(val query: String, val items: List<SuggestionsPosterItem>) : State()
        data class Error(val query: String) : State()
    }

    private data class Result(val query: String, val items: List<SuggestionsPosterItem>)

    private val searchLoader = SearchLoader<Query, Result>(viewModelScope) { query ->
        val releases = searchRepository.searchReleasesByQuery(query.query)
        releaseInteractor.updateItemsCache(releases)
        Result(query.query, releases.map { it.toItem() })
    }

    /** Текст строки поиска как есть. */
    val queryData = MutableStateFlow("")

    val state = MutableStateFlow<State>(State.Idle)

    init {
        combine(queryData, searchLoader.observeState()) { text, loaderState ->
            val query = Query(text.trim())
            val data = loaderState.data
            when {
                query.isEmpty() -> State.Idle
                data != null && data.query == query.query && !loaderState.loading -> {
                    State.Content(data.query, data.items)
                }

                loaderState.error != null && !loaderState.loading -> State.Error(query.query)
                else -> State.Loading(query.query)
            }
        }
            .onEach { state.value = it }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(text: String) {
        if (text == queryData.value) return
        queryData.value = text
        searchLoader.onNewQuery(Query(text.trim()))
    }

    fun onCardClick(card: LibriaCard) {
        queriesStorage.add(queryData.value)
        cardRouter.navigate(card)
    }

    private fun Release.toItem(): SuggestionsPosterItem {
        val card = converter.toCard(this)
        val meta = listOfNotNull(
            year?.trim()?.takeIf { it.isNotEmpty() },
            types.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        return SuggestionsPosterItem(
            card = card,
            title = title?.takeIf { it.isNotBlank() } ?: card.title,
            meta = meta.takeIf { it.isNotEmpty() },
        )
    }

    private data class Query(val query: String) : SearchQuery {
        override fun isEmpty(): Boolean {
            return query.length < 3
        }
    }
}

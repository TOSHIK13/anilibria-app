package ru.radiationx.anilibria.screen.search

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.screen.mainpages.MainHeroConverter
import ru.radiationx.anilibria.screen.mainpages.MainPagesTabsController
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.search.SearchForm
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.SearchRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Название и мета выбранной карточки над сеткой «Каталога». */
data class SearchSelectedInfo(
    val title: String,
    val meta: String?,
)

class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val heroConverter: MainHeroConverter,
    private val tabsController: MainPagesTabsController,
    private val cardRouter: LibriaCardRouter,
    searchController: SearchController,
) : BaseCardsViewModel() {

    private companion object {
        const val PAGE_LIMIT = 30
    }

    @Volatile
    private var searchForm = SearchForm()
    private var loadedForm: SearchForm? = null

    @Volatile
    private var lastPageIsEnd = true

    /** Релизы загруженных страниц — для строки над сеткой. */
    private val releases = ConcurrentHashMap<ReleaseId, Release>()

    val progressState = MutableStateFlow(false)

    /** Сколько всего тайтлов по фильтрам (`meta.pagination.total`); null — идёт загрузка или ошибка. */
    val totalData = MutableStateFlow<Int?>(null)

    val selectedInfoData = MutableStateFlow<SearchSelectedInfo?>(null)

    /** Выбранная позиция сетки: страница пересоздаётся при смене вкладок и возврате с релиза. */
    var selectedPosition = 0

    override val loadOnCreate: Boolean = false

    override val progressOnRefresh: Boolean = false

    override val showLoadMoreCard: Boolean = false

    init {
        searchController.applyFormEvent.onEach {
            if (it == loadedForm) return@onEach
            loadedForm = it
            searchForm = it
            reload()
        }.launchIn(viewModelScope)
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> {
        val form = searchForm
        val result = searchRepository.searchReleases(form, requestPage, PAGE_LIMIT)
        releaseInteractor.updateItemsCache(result.data)
        if (requestPage == firstPage) {
            releases.clear()
        }
        result.data.forEach { releases[it.id] = it }
        lastPageIsEnd = result.isEnd() || result.data.isEmpty()
        if (requestPage == firstPage && form == searchForm) {
            totalData.value = result.allItems ?: result.data.size
            progressState.value = false
        }
        return result.data.map { converter.toCard(it) }
    }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean {
        return !lastPageIsEnd
    }

    override fun getErrorCard(error: Throwable): LoadingCard {
        progressState.value = false
        return super.getErrorCard(error)
    }

    /** Загрузка с первой страницы: смена фильтров или «Повторить». */
    fun reload() {
        selectedPosition = 0
        totalData.value = null
        progressState.value = true
        restart()
    }

    override fun onLoadingCardClick() {
        if (cardsData.value.none { it is LibriaCard }) {
            reload()
        } else {
            loadMore()
        }
    }

    fun onItemSelected(position: Int, item: Any?) {
        if (item !is LibriaCard) return
        selectedPosition = position
        val releaseId = (item.type as? LibriaCard.Type.Release)?.releaseId
        val release = releaseId?.let { releases[it] }
        selectedInfoData.value = if (release != null) {
            SearchSelectedInfo(
                title = release.title?.takeIf { it.isNotBlank() } ?: item.title,
                meta = heroConverter.catalogMeta(release).takeIf { it.isNotEmpty() },
            )
        } else {
            SearchSelectedInfo(item.title, null)
        }
    }

    fun onSearchClick() {
        tabsController.openSearch()
    }

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}

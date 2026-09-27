package ru.radiationx.anilibria.screen.details

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardItem
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.anilibria.similar.SimilarList
import ru.radiationx.anilibria.similar.SimilarReleasesRepository
import ru.radiationx.anilibria.similar.SimilarSource
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.ReleaseRepository
import ru.radiationx.shared.ktx.coRunCatching
import timber.log.Timber
import javax.inject.Inject

/**
 * Ряд «Похожие · <сервис>»: все совпадения с каталогом в порядке сервиса.
 * Карточки — краткие релизы пакетным `releases/list?ids=` (пачки по 50), уже загруженные
 * не перезапрашиваются. Если у сервиса есть ещё страницы (AniList), в конце ряда стоит
 * «Загрузить еще»: карточка привязывается, когда фокус подходит к концу ряда, и
 * догружает одну следующую страницу. Есть ли ряд вообще, решает [DetailsViewModel].
 */
abstract class DetailSimilarViewModel(
    argExtra: DetailExtra,
    private val source: SimilarSource,
    private val similarRepository: SimilarReleasesRepository,
    private val releaseRepository: ReleaseRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter,
) : BaseCardsViewModel() {

    private val releaseId = argExtra.id

    override val loadOnCreate: Boolean = false

    override val defaultTitle: String = "Похожие · ${source.title}"

    private val releases = HashMap<Int, Release>()
    private var list: SimilarList? = null
    private var moreJob: Job? = null

    init {
        cardsData.value = listOf(loadingCard)
        viewModelScope.launch {
            similarRepository
                .observe(releaseId)
                .map { it.lists[source] }
                .filterNotNull()
                .distinctUntilChanged()
                .conflate()
                .collect { newList ->
                    list = newList
                    render(newList)
                }
        }
    }

    override fun onColdCreate() {
        super.onColdCreate()
        list?.also { rowTitle.value = titleFor(it, currentCardsCount()) }
    }

    override fun onLinkCardBind() {
        if (moreJob?.isActive == true) return
        val current = list ?: return
        if (!current.hasMore) return
        moreJob = viewModelScope.launch {
            // «Загрузить еще» остаётся на месте, пока грузится страница: если фокус уже на ней,
            // удаление карточки перекинуло бы фокус в начало ряда.
            val loaded = similarRepository.loadMore(releaseId, source)
            // Нет новой страницы (ошибка / 429) — убрать «Загрузить еще», ряд остаётся как есть.
            if (!loaded) cardsData.value = cardsData.value.filterIsInstance<LibriaCard>()
        }
    }

    override fun onLoadingCardClick() {
        list?.also { current -> viewModelScope.launch { render(current) } }
    }

    // Ряд управляется через render(), BaseCardsViewModel.loadPage не используется.
    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = emptyList()

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }

    private suspend fun render(current: SimilarList) {
        val ids = current.items.map { it.id }
        val missing = ids.filter { it !in releases }
        missing.forEach { id -> releaseInteractor.getItem(ReleaseId(id))?.also { releases[id] = it } }
        val toLoad = missing.filter { it !in releases }
        var error: Throwable? = null
        if (toLoad.isNotEmpty()) {
            coRunCatching { releaseRepository.getShortReleasesById(toLoad.map { ReleaseId(it) }) }
                .onSuccess { loaded ->
                    releaseInteractor.updateItemsCache(loaded)
                    loaded.forEach { releases[it.id.id] = it }
                }
                .onFailure {
                    Timber.w(it, "similar: cards for $releaseId")
                    error = it
                }
        }
        val cards: List<CardItem> = ids.mapNotNull { releases[it] }.map { converter.toCard(it) }
        val failed = error
        cardsData.value = when {
            cards.isEmpty() && failed != null -> listOf(getErrorCard(failed))
            current.hasMore -> cards + loadMoreCard
            else -> cards
        }
        rowTitle.value = titleFor(current, cards.size)
    }

    private fun currentCardsCount(): Int = cardsData.value.count { it is LibriaCard }

    /**
     * «Похожие · AniList (12 из 48)» — если у сервиса есть тайтлы вне каталога;
     * «(27 из 50+)» — у сервиса есть ещё не загруженные страницы.
     */
    private fun titleFor(current: SimilarList, shown: Int): String {
        val more = if (current.hasMore) "+" else ""
        return if (shown > 0 && (shown < current.total || current.hasMore)) {
            "$defaultTitle ($shown из ${current.total}$more)"
        } else {
            defaultTitle
        }
    }
}

class DetailSimilarAniListViewModel @Inject constructor(
    argExtra: DetailExtra,
    similarRepository: SimilarReleasesRepository,
    releaseRepository: ReleaseRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : DetailSimilarViewModel(
    argExtra, SimilarSource.ANILIST, similarRepository, releaseRepository, releaseInteractor, converter, cardRouter
)

class DetailSimilarShikimoriViewModel @Inject constructor(
    argExtra: DetailExtra,
    similarRepository: SimilarReleasesRepository,
    releaseRepository: ReleaseRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : DetailSimilarViewModel(
    argExtra, SimilarSource.SHIKIMORI, similarRepository, releaseRepository, releaseInteractor, converter, cardRouter
)

class DetailSimilarMalViewModel @Inject constructor(
    argExtra: DetailExtra,
    similarRepository: SimilarReleasesRepository,
    releaseRepository: ReleaseRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : DetailSimilarViewModel(
    argExtra, SimilarSource.MAL, similarRepository, releaseRepository, releaseInteractor, converter, cardRouter
)

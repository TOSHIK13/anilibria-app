package ru.radiationx.anilibria.screen.collections

import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.radiationx.anilibria.common.BaseCardsViewModel
import ru.radiationx.anilibria.common.CardItem
import ru.radiationx.anilibria.common.CardsDataConverter
import ru.radiationx.anilibria.common.LibriaCard
import ru.radiationx.anilibria.common.LibriaCardRouter
import ru.radiationx.anilibria.common.LoadingCard
import ru.radiationx.anilibria.screen.AuthGuidedScreen
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import javax.inject.Inject

class CollectionsAuthViewModel @Inject constructor(
    private val router: Router,
) : BaseCardsViewModel() {

    override val defaultTitle: String = "Коллекции"

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> = emptyList()

    override fun getEmptyCard(): CardItem = LoadingCard(
        "Войти",
        "Авторизуйтесь, чтобы открыть свои коллекции",
        isError = true,
    )

    override fun onLoadingCardClick() {
        router.navigateTo(AuthGuidedScreen())
    }

    override fun hasMoreCards(newCards: List<LibriaCard>, allCards: List<LibriaCard>): Boolean =
        false
}

abstract class BaseCollectionCardsViewModel(
    private val type: CollectionType,
    private val title: String,
    private val authRepository: AuthRepository,
    private val collectionRepository: CollectionRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val converter: CardsDataConverter,
    private val cardRouter: LibriaCardRouter,
) : BaseCardsViewModel() {

    override val defaultTitle: String = title

    override val loadOnCreate: Boolean = false

    override val preventClearOnRefresh: Boolean = true

    init {
        authRepository
            .observeSessionToken()
            .distinctUntilChanged()
            .filter { !it.isNullOrBlank() }
            .onEach { onRefreshClick() }
            .launchIn(viewModelScope)
    }

    override fun onResume() {
        super.onResume()
        if (cardsData.value.isNotEmpty()) {
            onRefreshClick()
        }
    }

    override suspend fun getLoader(requestPage: Int): List<LibriaCard> {
        if (!authRepository.hasSessionToken()) {
            return emptyList()
        }
        return collectionRepository
            .getReleases(type, requestPage)
            .also { releaseInteractor.updateItemsCache(it.data) }
            .let { page -> page.data.map { converter.toCard(it) } }
    }

    override fun getEmptyCard(): CardItem = LoadingCard(
        "Пусто",
        "В этой коллекции пока нет релизов",
    )

    override fun onLibriaCardClick(card: LibriaCard) {
        cardRouter.navigate(card)
    }
}

class CollectionsWatchingViewModel @Inject constructor(
    authRepository: AuthRepository,
    collectionRepository: CollectionRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : BaseCollectionCardsViewModel(
    CollectionType.WATCHING,
    "Смотрю",
    authRepository,
    collectionRepository,
    releaseInteractor,
    converter,
    cardRouter,
)

class CollectionsPlannedViewModel @Inject constructor(
    authRepository: AuthRepository,
    collectionRepository: CollectionRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : BaseCollectionCardsViewModel(
    CollectionType.PLANNED,
    "Запланировано",
    authRepository,
    collectionRepository,
    releaseInteractor,
    converter,
    cardRouter,
)

class CollectionsWatchedViewModel @Inject constructor(
    authRepository: AuthRepository,
    collectionRepository: CollectionRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : BaseCollectionCardsViewModel(
    CollectionType.WATCHED,
    "Просмотрено",
    authRepository,
    collectionRepository,
    releaseInteractor,
    converter,
    cardRouter,
)

class CollectionsPostponedViewModel @Inject constructor(
    authRepository: AuthRepository,
    collectionRepository: CollectionRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : BaseCollectionCardsViewModel(
    CollectionType.POSTPONED,
    "Отложено",
    authRepository,
    collectionRepository,
    releaseInteractor,
    converter,
    cardRouter,
)

class CollectionsAbandonedViewModel @Inject constructor(
    authRepository: AuthRepository,
    collectionRepository: CollectionRepository,
    releaseInteractor: ReleaseInteractor,
    converter: CardsDataConverter,
    cardRouter: LibriaCardRouter,
) : BaseCollectionCardsViewModel(
    CollectionType.ABANDONED,
    "Брошено",
    authRepository,
    collectionRepository,
    releaseInteractor,
    converter,
    cardRouter,
)

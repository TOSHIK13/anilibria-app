package ru.radiationx.anilibria.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.interactors.ReleaseInteractor
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.ReleaseRepository
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.data.repository.watch.ContinueWatchingItem
import ru.radiationx.data.repository.watch.WatchHistoryLogic
import javax.inject.Inject

/**
 * «Продолжить просмотр» из серверной истории просмотра (общая с сайтом и телефоном).
 * Используется рядом на главной и на «Я смотрю».
 */
class ContinueWatchingLoader @Inject constructor(
    private val watchProgressRepository: WatchProgressRepository,
    private val releaseRepository: ReleaseRepository,
    private val releaseInteractor: ReleaseInteractor,
    private val authRepository: AuthRepository,
    private val converter: CardsDataConverter,
) {

    /** Показывать ли ряд: только с авторизацией и когда есть что продолжать. */
    fun observeHasItems(): Flow<Boolean> = combine(
        authRepository.observeAuthState(),
        watchProgressRepository.observeContinueList(),
    ) { auth, items ->
        auth == AuthState.AUTH && !items.isNullOrEmpty()
    }.distinctUntilChanged()

    fun observeItems(): Flow<List<ContinueWatchingItem>?> =
        watchProgressRepository.observeContinueList()

    /** [onItems] получает весь список истории, по которому строятся карточки. */
    suspend fun loadCards(onItems: (List<ContinueWatchingItem>) -> Unit = {}): List<LibriaCard> {
        val items = watchProgressRepository.getContinueList().also(onItems).take(MAX_ITEMS)
        if (items.isEmpty()) return emptyList()
        // Карточкам хватает краткой информации о релизе: один запрос releases/list на всех,
        // и только для релизов, которых ещё нет в кэше.
        val missing = items
            .map { it.releaseId }
            .filter { releaseInteractor.getItem(releaseId = it) == null }
        if (missing.isNotEmpty()) {
            releaseInteractor.updateItemsCache(releaseRepository.getReleasesById(missing))
        }
        return items.mapNotNull { item ->
            val release = releaseInteractor.getItem(releaseId = item.releaseId)
                ?: return@mapNotNull null
            val card = converter.toCard(release)
            val ordinal = item.ordinal ?: return@mapNotNull card
            card.copy(
                description = "Вы остановились на ${WatchHistoryLogic.ordinalLabel(ordinal)} серии"
            )
        }
    }

    private companion object {
        const val MAX_ITEMS = 20
    }
}

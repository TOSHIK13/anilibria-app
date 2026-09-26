package ru.radiationx.anilibria.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.data.repository.ReleaseCardRepository
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
    private val releaseCardRepository: ReleaseCardRepository,
    private val authRepository: AuthRepository,
    private val collectionRepository: CollectionRepository,
    private val converter: CardsDataConverter,
) {

    /**
     * Показывать ли ряд: только с авторизацией и когда есть что продолжать
     * (без релизов из коллекций «Просмотрено» и «Брошено»).
     */
    fun observeHasItems(): Flow<Boolean> = combine(
        authRepository.observeAuthState(),
        watchProgressRepository.observeContinueList(),
        collectionRepository.observeCollectionIds(),
    ) { auth, items, collections ->
        auth == AuthState.AUTH &&
                !items.isNullOrEmpty() &&
                WatchHistoryLogic.filterHidden(items, collections).isNotEmpty()
    }.distinctUntilChanged()

    fun observeItems(): Flow<List<ContinueWatchingItem>?> =
        watchProgressRepository.observeContinueList()

    /**
     * Ряд стоит перестроить: фоновое обновление изменило данные релизов
     * или изменился набор релизов в коллекциях «Просмотрено»/«Брошено».
     */
    fun observeCardUpdates(): Flow<Unit> = merge(
        releaseCardRepository.observeUpdates(),
        collectionRepository
            .observeCollectionIds()
            .map { WatchHistoryLogic.hiddenByCollection(it) }
            .distinctUntilChanged()
            // Текущее значение ряд уже учёл при загрузке — реагируем только на изменения.
            .drop(1)
            .map { },
    )

    /**
     * Карточки ряда. История — из дискового снимка (сервер обновляет её в фоне),
     * данные релизов — из памяти/диска; сеть ждём только для релизов, которых нет в кэше.
     * Релизы из коллекций «Просмотрено» и «Брошено» скрываются по кэшу коллекций (сеть не ждём).
     * [onItems] получает весь список истории, [onSource] — `cache` или `network`.
     */
    suspend fun loadCards(
        onItems: (List<ContinueWatchingItem>) -> Unit = {},
        onSource: (String) -> Unit = {},
    ): List<LibriaCard> {
        // Кэш коллекций (null — ещё не загружен); свежие данные подтянутся в фоне.
        val collections = collectionRepository.observeCollectionIds().first()
        val items = WatchHistoryLogic
            .filterHidden(watchProgressRepository.getContinueList().also(onItems), collections)
            .take(MAX_ITEMS)
        if (items.isEmpty()) {
            onSource(SOURCE_CACHE)
            return emptyList()
        }
        val cards = releaseCardRepository.getCards(items.map { it.releaseId })
        onSource(if (cards.fromNetwork) SOURCE_NETWORK else SOURCE_CACHE)
        return items.mapNotNull { item ->
            val info = cards.items[item.releaseId] ?: return@mapNotNull null
            val card = converter.toCard(info)
            val ordinal = item.ordinal ?: return@mapNotNull card
            card.copy(
                description = "Вы остановились на ${WatchHistoryLogic.ordinalLabel(ordinal)} серии"
            )
        }
    }

    private companion object {
        const val MAX_ITEMS = 20
        const val SOURCE_CACHE = "cache"
        const val SOURCE_NETWORK = "network"
    }
}

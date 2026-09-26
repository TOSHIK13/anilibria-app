package ru.radiationx.anilibria.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.repository.AuthRepository
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

    /** Фоновое обновление данных релизов изменило карточки — ряд стоит перестроить. */
    fun observeCardUpdates(): Flow<Unit> = releaseCardRepository.observeUpdates()

    /**
     * Карточки ряда. История — из дискового снимка (сервер обновляет её в фоне),
     * данные релизов — из памяти/диска; сеть ждём только для релизов, которых нет в кэше.
     * [onItems] получает весь список истории, [onSource] — `cache` или `network`.
     */
    suspend fun loadCards(
        onItems: (List<ContinueWatchingItem>) -> Unit = {},
        onSource: (String) -> Unit = {},
    ): List<LibriaCard> {
        val items = watchProgressRepository.getContinueList().also(onItems).take(MAX_ITEMS)
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

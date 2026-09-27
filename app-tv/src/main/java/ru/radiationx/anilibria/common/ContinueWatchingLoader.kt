package ru.radiationx.anilibria.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import ru.radiationx.data.entity.common.AuthState
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.repository.AuthRepository
import ru.radiationx.data.repository.CollectionRepository
import ru.radiationx.data.repository.ReleaseCardRepository
import ru.radiationx.data.repository.WatchProgressRepository
import ru.radiationx.data.repository.watch.ContinueWatchingItem
import ru.radiationx.data.repository.watch.ReleaseCardInfo
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
        val cards = releaseCardRepository.getCards(
            ids = items.map { it.releaseId },
            focusEpisodes = items.associate { it.releaseId to it.episodeId },
        )
        onSource(if (cards.fromNetwork) SOURCE_NETWORK else SOURCE_CACHE)
        return items.mapNotNull { item ->
            val info = cards.items[item.releaseId] ?: return@mapNotNull null
            val card = converter.toCard(info)
            val ordinal = item.ordinal
            card.copy(
                description = ordinal
                    ?.let { "Вы остановились на ${WatchHistoryLogic.ordinalLabel(it)} серии" }
                    ?: card.description,
                continueInfo = continueInfo(item, info),
            )
        }
    }

    /**
     * Серия для карточки: из истории, а если она досмотрена — следующая с начала
     * (как «Продолжить» в деталях). Превью и длительность — из серий релиза.
     */
    private fun continueInfo(item: ContinueWatchingItem, info: ReleaseCardInfo): LibriaCard.ContinueInfo {
        val episodes = info.episodes
        val ordinalLabel = item.ordinal?.let { WatchHistoryLogic.ordinalLabel(it) }
        val index = episodes.indexOfFirst { it.serverId == item.episodeId }
            .takeIf { it >= 0 }
            ?: episodes.indexOfFirst { it.ordinal == ordinalLabel }
        val current = episodes.getOrNull(index)
        val next = if (item.isWatched && index >= 0) episodes.getOrNull(index + 1) else null
        val episode = next ?: current
        val label = episode?.ordinal ?: ordinalLabel
        return LibriaCard.ContinueInfo(
            episodeId = label?.let { EpisodeId(it, item.releaseId) },
            ordinalLabel = label,
            previewUrl = episode?.previewUrl,
            durationSec = episode?.durationSec?.takeIf { it > 0 },
            positionSec = if (next != null) 0 else item.time?.toInt()?.coerceAtLeast(0) ?: 0,
        )
    }

    private companion object {
        const val MAX_ITEMS = 20
        const val SOURCE_CACHE = "cache"
        const val SOURCE_NETWORK = "network"
    }
}

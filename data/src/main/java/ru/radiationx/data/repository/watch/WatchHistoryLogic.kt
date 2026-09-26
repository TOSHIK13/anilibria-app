package ru.radiationx.data.repository.watch

import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.view.ViewHistoryItemResponse
import ru.radiationx.data.repository.ReleaseWatchProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Чистая логика истории просмотра: без сети и хранилища, покрыта unit-тестами. */
object WatchHistoryLogic {

    fun fromResponse(items: List<ViewHistoryItemResponse>): List<WatchHistoryEpisode> =
        items.mapNotNull { item ->
            val episodeId = item.releaseEpisodeId ?: return@mapNotNull null
            val releaseId = item.releaseEpisode?.releaseId
                ?: item.releaseEpisode?.release?.id
                ?: return@mapNotNull null
            WatchHistoryEpisode(
                episodeId = episodeId,
                releaseId = releaseId,
                ordinal = item.releaseEpisode?.ordinal,
                time = item.time,
                isWatched = item.isWatched == true,
                updatedAt = parseDate(item.updatedAt),
                episodesTotal = item.releaseEpisode?.release?.episodesTotal?.takeIf { it > 0 },
            )
        }

    /**
     * Серверный снимок заменяет локальный, кроме серий, изменённых локально после [since]
     * (начало загрузки) — их сервер мог ещё не увидеть.
     */
    fun mergeServer(
        server: List<WatchHistoryEpisode>,
        local: Map<String, WatchHistoryEpisode>,
        since: Long,
    ): Map<String, WatchHistoryEpisode> {
        val result = LinkedHashMap<String, WatchHistoryEpisode>()
        server.forEach { episode ->
            val old = result[episode.episodeId]
            if (old == null || episode.updatedAt >= old.updatedAt) {
                result[episode.episodeId] = episode
            }
        }
        local.values.filter { it.updatedAt > since }.forEach { episode ->
            result[episode.episodeId] = mergeEpisode(result[episode.episodeId], episode)
        }
        return result
    }

    /** Новое значение серии поверх старого: неизвестные поля берутся из старого. */
    fun mergeEpisode(old: WatchHistoryEpisode?, new: WatchHistoryEpisode): WatchHistoryEpisode {
        old ?: return new
        return new.copy(
            ordinal = new.ordinal ?: old.ordinal,
            time = new.time ?: old.time,
            episodesTotal = new.episodesTotal ?: old.episodesTotal,
        )
    }

    /** Накладывает неотправленные изменения на снимок, чтобы после перезапуска они не пропадали. */
    fun applyPending(
        episodes: Map<String, WatchHistoryEpisode>,
        pending: List<PendingTimecode>,
    ): Map<String, WatchHistoryEpisode> {
        if (pending.isEmpty()) return episodes
        val result = LinkedHashMap(episodes)
        pending.forEach { op ->
            val old = result[op.episodeId]
            if (old != null && old.updatedAt > op.createdAt) return@forEach
            if (op.isDelete) {
                result.remove(op.episodeId)
            } else {
                result[op.episodeId] = mergeEpisode(
                    old,
                    WatchHistoryEpisode(
                        episodeId = op.episodeId,
                        releaseId = op.releaseId,
                        ordinal = op.ordinal,
                        time = op.time?.toFloat(),
                        isWatched = op.isWatched,
                        updatedAt = op.createdAt,
                    )
                )
            }
        }
        return result
    }

    fun progress(episodes: Collection<WatchHistoryEpisode>): Map<ReleaseId, ReleaseWatchProgress> =
        episodes.groupBy { it.releaseId }.entries.associate { (releaseId, items) ->
            ReleaseId(releaseId) to ReleaseWatchProgress(
                watched = items.count { it.isWatched },
                total = items.mapNotNull { it.episodesTotal }.maxOrNull(),
            )
        }

    /**
     * Релизы по убыванию времени последнего просмотра, по одной (последней) серии на релиз.
     * Досмотренное не отсекается: скрытие — только по коллекциям ([filterHidden]).
     */
    fun continueList(episodes: Collection<WatchHistoryEpisode>): List<ContinueWatchingItem> =
        episodes
            .groupBy { it.releaseId }
            .values
            .mapNotNull { items ->
                val total = items.mapNotNull { it.episodesTotal }.maxOrNull()
                val watched = items.count { it.isWatched }
                val last = items.maxWithOrNull(
                    compareBy<WatchHistoryEpisode> { it.updatedAt }.thenBy { it.ordinal ?: 0f }
                ) ?: return@mapNotNull null
                ContinueWatchingItem(
                    releaseId = ReleaseId(last.releaseId),
                    episodeId = last.episodeId,
                    ordinal = last.ordinal,
                    time = last.time,
                    isWatched = last.isWatched,
                    updatedAt = last.updatedAt,
                    watchedCount = watched,
                    episodesTotal = total,
                )
            }
            .sortedByDescending { it.updatedAt }

    /** Коллекции, релизы из которых не показываются в «Продолжить просмотр». */
    val HIDDEN_COLLECTIONS: Set<CollectionType> = setOf(CollectionType.WATCHED, CollectionType.ABANDONED)

    /** Релизы, скрытые из «Продолжить просмотр» по коллекции пользователя. */
    fun hiddenByCollection(collections: Map<ReleaseId, CollectionType>?): Set<ReleaseId> =
        collections.orEmpty().filterValues { it in HIDDEN_COLLECTIONS }.keys

    /**
     * Убирает релизы из коллекций «Просмотрено» и «Брошено».
     * [collections] == null (ещё не загружены) — список не меняется.
     */
    fun filterHidden(
        items: List<ContinueWatchingItem>,
        collections: Map<ReleaseId, CollectionType>?,
    ): List<ContinueWatchingItem> {
        val hidden = hiddenByCollection(collections)
        if (hidden.isEmpty()) return items
        return items.filter { it.releaseId !in hidden }
    }

    /** Добавляет изменения в очередь: на серию остаётся только самое позднее. */
    fun mergePending(
        queue: List<PendingTimecode>,
        ops: List<PendingTimecode>,
        maxSize: Int = MAX_PENDING,
    ): List<PendingTimecode> {
        val result = LinkedHashMap<String, PendingTimecode>()
        (queue + ops).forEach { op ->
            val old = result[op.episodeId]
            if (old == null || op.createdAt >= old.createdAt) {
                result.remove(op.episodeId)
                result[op.episodeId] = op
            }
        }
        return result.values.toList().takeLast(maxSize)
    }

    /** Убирает из очереди отправленное; более новые изменения той же серии остаются. */
    fun dropSent(queue: List<PendingTimecode>, sent: List<PendingTimecode>): List<PendingTimecode> {
        val sentAt = sent.associate { it.episodeId to it.createdAt }
        return queue.filter { op ->
            val at = sentAt[op.episodeId] ?: return@filter true
            op.createdAt > at
        }
    }

    fun ordinalLabel(ordinal: Float): String {
        val text = ordinal.toString()
        return if (text.endsWith(".0")) text.dropLast(2) else text
    }

    private val fractionRegex = Regex("\\.\\d+(?=(Z|[+-]\\d{2}:?\\d{2})?$)")

    /** ISO-8601 (`2025-01-02T03:04:05Z`, `...05.123456+00:00`) в миллисекунды, 0 — не разобрать. */
    fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        var text = value.trim().replace(fractionRegex, "")
        if (!text.endsWith("Z") && !Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(text)) {
            text += "Z"
        }
        return runCatching { createDateFormat().parse(text)?.time ?: 0L }.getOrDefault(0L)
    }

    fun formatDate(value: Long): String = createDateFormat().format(Date(value))

    private fun createDateFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    const val MAX_PENDING = 200
}

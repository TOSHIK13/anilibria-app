package ru.radiationx.data.external

import ru.radiationx.data.entity.domain.collection.CollectionType

/** Желаемый статус записи. [REMOVE] — убрать запись из списка сервиса. */
enum class DesiredStatus(val ru: String) {
    PLANNING("Запланировано"),
    CURRENT("Смотрю"),
    COMPLETED("Просмотрено"),
    PAUSED("Отложено"),
    DROPPED("Брошено"),
    REMOVE("Убрано из коллекций");

    companion object {
        fun of(type: CollectionType?): DesiredStatus = when (type) {
            CollectionType.PLANNED -> PLANNING
            CollectionType.WATCHING -> CURRENT
            CollectionType.WATCHED -> COMPLETED
            CollectionType.POSTPONED -> PAUSED
            CollectionType.ABANDONED -> DROPPED
            null -> REMOVE
        }
    }
}

enum class OutboxSource { EPISODE, COLLECTION, MANUAL }

enum class OutboxState {
    /** Ждёт отправки (в т.ч. по расписанию повтора). */
    PENDING,

    /** Исчерпаны попытки: ждёт ручного «Повторить сейчас» / «Пропустить». */
    ERROR,
}

/** Желаемое состояние записи. `status == null` — только просмотр серии (статус выводится по правилам). */
data class DesiredState(
    val status: DesiredStatus? = null,
    val progress: Int? = null,
    val totalEpisodes: Int? = null,
)

/** Элемент очереди отправки: одно ожидающее изменение на пару (serviceId, malId). */
data class OutboxItem(
    val id: Long,
    val serviceId: String,
    val malId: Int,
    val releaseId: Int?,
    val title: String,
    val desired: DesiredState,
    val source: OutboxSource,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    /** Сколько событий слито в этот элемент, включая первое. */
    val mergedCount: Int = 1,
    /** Номера серий из объединённых событий (для журнала). */
    val episodes: List<Int> = emptyList(),
    val state: OutboxState = OutboxState.PENDING,
    /** Растёт при каждом слиянии: отправка не удаляет элемент, если он изменился во время запроса. */
    val version: Int = 0,
)

/** Чистые правила очереди: слияние событий и расписание повторов. */
object SyncRules {

    /** Задержки перед повтором после n-й неудачи: 30 с, 2 мин, 10 мин, 30 мин. */
    val BACKOFF_MS = longArrayOf(30_000, 120_000, 600_000, 1_800_000, 3_600_000)

    /** После стольких неудачных попыток элемент переходит в [OutboxState.ERROR]. */
    const val MAX_ATTEMPTS = 5

    /** Пауза перед повтором после [attempts]-й неудачи (1..); null — попытки кончились. */
    fun backoffMs(attempts: Int): Long? =
        if (attempts >= MAX_ATTEMPTS) null else BACKOFF_MS[(attempts - 1).coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * Слияние нового события [new] с ожидающим [old] той же пары (сервис, тайтл):
     * progress = max, статус — последний заданный (просмотр серии не «понижает» COMPLETED/CURRENT,
     * но выводит Отложено/Брошено/Запланировано/Убрано в «Смотрю» — статус сбрасывается для правил).
     */
    fun merge(old: OutboxItem, new: OutboxItem): OutboxItem {
        val newStatus = new.desired.status
        val status = when {
            newStatus != null -> newStatus
            old.desired.status == DesiredStatus.CURRENT || old.desired.status == DesiredStatus.COMPLETED -> old.desired.status
            else -> null
        }
        val progress = if (newStatus == DesiredStatus.REMOVE) null else listOfNotNull(old.desired.progress, new.desired.progress).maxOrNull()
        return old.copy(
            releaseId = new.releaseId ?: old.releaseId,
            title = new.title.ifEmpty { old.title },
            desired = DesiredState(status, progress, new.desired.totalEpisodes ?: old.desired.totalEpisodes),
            source = new.source,
            mergedCount = old.mergedCount + 1,
            episodes = (old.episodes + new.episodes).distinct().sorted(),
            // новое событие даёт свежую попытку сразу, но накопленный backoff (нет сети) не сбрасываем
            state = OutboxState.PENDING,
            version = old.version + 1,
        )
    }

    /** Фраза журнала об объединении, null — не объединялось. */
    fun mergeDetail(item: OutboxItem): String? {
        if (item.mergedCount <= 1) return null
        val eps = item.episodes
        return when {
            eps.size == 2 && eps[1] == eps[0] + 1 -> "серии ${eps[0]} и ${eps[1]} объединены в один запрос"
            eps.size == 2 -> "серии ${eps[0]} и ${eps[1]} объединены в один запрос"
            eps.size > 2 -> "серии ${eps.first()}–${eps.last()} объединены в один запрос"
            else -> "${item.mergedCount} изменения объединены в один запрос"
        }
    }
}

/** Что известно о тайтле на стороне сервиса. */
data class MediaInfo(val id: Long, val episodes: Int?, val format: String?) {
    val isMovie: Boolean get() = format == "MOVIE" || episodes == 1
}

/** Запись списка пользователя на стороне сервиса. */
data class RemoteEntry(val id: Long, val status: String, val progress: Int, val updatedAtSec: Long)

sealed class SyncPlan {
    /** Уже актуально, слать нечего. */
    data class UpToDate(val reason: String) : SyncPlan()

    data class Delete(val entryId: Long) : SyncPlan()

    data class Save(val status: String, val progress: Int) : SyncPlan()
}

/** Правила записи (п.3 ТЗ): что отправлять, исходя из желаемого состояния и записи на стороне сервиса. */
object AniListWriteRules {

    fun plan(desired: DesiredState, media: MediaInfo, remote: RemoteEntry?): SyncPlan {
        val episodes = media.episodes?.takeIf { it > 0 } ?: desired.totalEpisodes?.takeIf { it > 0 }
        val remoteProgress = remote?.progress ?: 0
        val status = desired.status

        if (status == DesiredStatus.REMOVE) {
            return if (remote != null) SyncPlan.Delete(remote.id) else SyncPlan.UpToDate("в AniList записи нет")
        }

        val targetStatus: String
        val targetProgress: Int
        if (status != null) {
            targetStatus = when {
                status == DesiredStatus.CURRENT && remote?.status == "REPEATING" -> "REPEATING"
                else -> status.name
            }
            targetProgress = when {
                status == DesiredStatus.COMPLETED && episodes != null -> episodes
                desired.progress != null -> maxOf(remoteProgress, desired.progress)
                else -> remoteProgress
            }
        } else {
            var progress = maxOf(remoteProgress, desired.progress ?: 0)
            if (episodes != null && progress > episodes) progress = episodes
            targetProgress = progress
            val remoteStatus = remote?.status
            targetStatus = when {
                remoteStatus == "COMPLETED" -> "COMPLETED"
                episodes != null && progress >= episodes -> "COMPLETED"
                remoteStatus == "CURRENT" || remoteStatus == "REPEATING" -> remoteStatus
                else -> "CURRENT" // нет записи, PLANNING, PAUSED, DROPPED
            }
        }

        if (remote != null && remote.status == targetStatus && remote.progress == targetProgress) {
            return SyncPlan.UpToDate("уже актуально")
        }
        return SyncPlan.Save(targetStatus, targetProgress)
    }

    private val STATUS_RU = mapOf(
        "PLANNING" to "Запланировано",
        "CURRENT" to "Смотрю",
        "REPEATING" to "Пересматриваю",
        "COMPLETED" to "Просмотрено",
        "PAUSED" to "Отложено",
        "DROPPED" to "Брошено",
    )

    fun statusRu(status: String): String = STATUS_RU[status] ?: status

    /** «5/11»; у фильмов — «Фильм». */
    fun progressText(progress: Int, media: MediaInfo, fallbackTotal: Int?): String {
        if (media.isMovie) return "Фильм"
        val total = media.episodes ?: fallbackTotal
        return if (total != null && total > 0) "$progress/$total" else "$progress"
    }

    /** Текст журнала для отправляемого/отправленного изменения. */
    fun describe(item: OutboxItem, plan: SyncPlan?, media: MediaInfo?): String {
        val d = item.desired
        val m = media ?: MediaInfo(0, null, null)
        if (d.status == DesiredStatus.REMOVE) return "коллекция снята · запись удалена из AniList"
        val progress = (plan as? SyncPlan.Save)?.progress ?: d.progress
        val text = progress?.let { progressText(it, m, d.totalEpisodes) }
        if (item.source == OutboxSource.EPISODE) {
            if (m.isMovie) return "просмотрен · Фильм"
            val n = item.episodes.lastOrNull()
            val head = if (n != null) "серия $n" else "все серии отмечены"
            return if (text != null) "$head → прогресс $text" else head
        }
        val status = d.status ?: return "прогресс ${text ?: ""}".trim()
        return "коллекция «${status.ru}»" + if (status == DesiredStatus.COMPLETED && text != null) " · $text" else ""
    }
}

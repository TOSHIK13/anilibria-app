package ru.radiationx.data.repository.watch

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import ru.radiationx.data.entity.domain.types.ReleaseId

/**
 * Одна серия из серверной истории просмотра (`views/history`) или локальное изменение поверх неё.
 * Хранится на диске, поэтому только примитивы.
 */
@JsonClass(generateAdapter = true)
data class WatchHistoryEpisode(
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "release_id") val releaseId: Int,
    @Json(name = "ordinal") val ordinal: Float? = null,
    /** Позиция в секундах. */
    @Json(name = "time") val time: Float? = null,
    @Json(name = "is_watched") val isWatched: Boolean = false,
    /** updated_at в миллисекундах UTC, 0 — неизвестно. */
    @Json(name = "updated_at") val updatedAt: Long = 0L,
    @Json(name = "episodes_total") val episodesTotal: Int? = null,
)

@JsonClass(generateAdapter = true)
data class WatchHistorySnapshot(
    @Json(name = "episodes") val episodes: List<WatchHistoryEpisode>,
    @Json(name = "loaded_at") val loadedAt: Long,
)

/**
 * Неотправленное изменение таймкода. [time] == null — удаление таймкода серии.
 * В очереди на серию хранится только последнее изменение.
 */
@JsonClass(generateAdapter = true)
data class PendingTimecode(
    @Json(name = "episode_id") val episodeId: String,
    @Json(name = "release_id") val releaseId: Int,
    @Json(name = "time") val time: Double?,
    @Json(name = "is_watched") val isWatched: Boolean = false,
    @Json(name = "ordinal") val ordinal: Float? = null,
    @Json(name = "created_at") val createdAt: Long,
) {
    val isDelete: Boolean get() = time == null
}

/**
 * Минимум о релизе для карточки «Продолжить просмотр» — хранится на диске,
 * чтобы ряд рисовался при холодном старте без сети.
 */
@JsonClass(generateAdapter = true)
data class ReleaseCardInfo(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "year") val year: String? = null,
    @Json(name = "season") val season: String? = null,
    @Json(name = "genres") val genres: List<String> = emptyList(),
    /** episodes_total строкой, как Release.series. */
    @Json(name = "series") val series: String? = null,
    /** fresh_at, unix-секунды (Release.torrentUpdate). */
    @Json(name = "torrent_update") val torrentUpdate: Int = 0,
    /** Сколько серий уже вышло, null — неизвестно. */
    @Json(name = "episodes_available") val episodesAvailable: Int? = null,
)

/** Релиз для «Продолжить просмотр»: последняя серия, которую смотрели. */
data class ContinueWatchingItem(
    val releaseId: ReleaseId,
    val episodeId: String,
    val ordinal: Float?,
    val time: Float?,
    val isWatched: Boolean,
    val updatedAt: Long,
    /** Сколько серий релиза отмечено просмотренными. */
    val watchedCount: Int = 0,
    /** episodes_total из истории, null — сервер не прислал. */
    val episodesTotal: Int? = null,
)

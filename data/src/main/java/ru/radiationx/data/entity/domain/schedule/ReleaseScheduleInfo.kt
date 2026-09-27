package ru.radiationx.data.entity.domain.schedule

import ru.radiationx.data.entity.domain.types.ReleaseId

/** Данные V1 расписания (`/api/v1/anime/schedule/week`) по одному релизу. */
data class ReleaseScheduleInfo(
    val releaseId: ReleaseId,
    /** Номер следующей серии (`next_release_episode_number`), null — неизвестно/сезон вышел. */
    val nextEpisodeNumber: Int?,
    /** Сезон вышел полностью (`full_season_is_released`). */
    val fullSeasonIsReleased: Boolean,
    /** Номер серии, вышедшей на этой неделе (`published_release_episode.ordinal`). */
    val publishedEpisodeOrdinal: Float?,
    /** День выхода в формате [java.util.Calendar.DAY_OF_WEEK], null — неизвестно. */
    val publishDay: Int?,
)

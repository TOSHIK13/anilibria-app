package ru.radiationx.data.entity.response.view

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** GET /accounts/users/me/views/history — одна запись на серию с таймкодом. */
@JsonClass(generateAdapter = true)
data class ViewHistoryPageResponse(
    @Json(name = "data") val data: List<ViewHistoryItemResponse>?,
    @Json(name = "meta") val meta: ViewHistoryMetaResponse?,
)

@JsonClass(generateAdapter = true)
data class ViewHistoryItemResponse(
    @Json(name = "is_watched") val isWatched: Boolean?,
    @Json(name = "release_episode_id") val releaseEpisodeId: String?,
    @Json(name = "release_episode") val releaseEpisode: ViewHistoryEpisodeResponse?,
)

@JsonClass(generateAdapter = true)
data class ViewHistoryEpisodeResponse(
    @Json(name = "release_id") val releaseId: Int?,
    @Json(name = "release") val release: ViewHistoryReleaseResponse?,
)

@JsonClass(generateAdapter = true)
data class ViewHistoryReleaseResponse(
    @Json(name = "id") val id: Int?,
    @Json(name = "episodes_total") val episodesTotal: Int?,
)

@JsonClass(generateAdapter = true)
data class ViewHistoryMetaResponse(
    @Json(name = "pagination") val pagination: ViewHistoryPaginationResponse?,
)

@JsonClass(generateAdapter = true)
data class ViewHistoryPaginationResponse(
    @Json(name = "current_page") val currentPage: Int?,
    @Json(name = "total_pages") val totalPages: Int?,
)

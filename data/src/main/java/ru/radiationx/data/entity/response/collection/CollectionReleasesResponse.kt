package ru.radiationx.data.entity.response.collection

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CollectionReleasesResponse(
    @Json(name = "data") val data: List<CollectionReleaseResponse>,
    @Json(name = "meta") val meta: CollectionMetaResponse?,
)

@JsonClass(generateAdapter = true)
data class CollectionMetaResponse(
    @Json(name = "pagination") val pagination: CollectionPaginationResponse?,
)

@JsonClass(generateAdapter = true)
data class CollectionPaginationResponse(
    @Json(name = "total") val total: Int?,
    @Json(name = "count") val count: Int?,
    @Json(name = "per_page") val perPage: Int?,
    @Json(name = "current_page") val currentPage: Int?,
    @Json(name = "total_pages") val totalPages: Int?,
)

@JsonClass(generateAdapter = true)
data class CollectionReleaseResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "alias") val alias: String?,
    @Json(name = "name") val name: CollectionReleaseNameResponse?,
    @Json(name = "poster") val poster: CollectionImageResponse?,
    @Json(name = "genres") val genres: List<CollectionGenreResponse>?,
    @Json(name = "type") val type: CollectionValueResponse?,
    @Json(name = "year") val year: Int?,
    @Json(name = "season") val season: CollectionValueResponse?,
    @Json(name = "publish_day") val publishDay: CollectionPublishDayResponse?,
    @Json(name = "description") val description: String?,
    @Json(name = "notification") val notification: String?,
    @Json(name = "episodes_total") val episodesTotal: Int?,
    @Json(name = "is_ongoing") val isOngoing: Boolean?,
    @Json(name = "updated_at") val updatedAt: String?,
    @Json(name = "external_player") val externalPlayer: String?,
    @Json(name = "is_blocked_by_geo") val isBlockedByGeo: Boolean?,
    @Json(name = "is_blocked_by_copyrights") val isBlockedByCopyrights: Boolean?,
    @Json(name = "episodes") val episodes: List<CollectionEpisodeResponse>?,
    @Json(name = "latest_episode") val latestEpisode: CollectionEpisodeResponse?,
)

@JsonClass(generateAdapter = true)
data class CollectionReleaseNameResponse(
    @Json(name = "main") val main: String?,
    @Json(name = "english") val english: String?,
    @Json(name = "alternative") val alternative: String?,
)

@JsonClass(generateAdapter = true)
data class CollectionImageResponse(
    @Json(name = "src") val src: String?,
    @Json(name = "preview") val preview: String?,
    @Json(name = "thumbnail") val thumbnail: String?,
    @Json(name = "optimized") val optimized: CollectionImageResponse?,
)

@JsonClass(generateAdapter = true)
data class CollectionGenreResponse(
    @Json(name = "name") val name: String?,
)

@JsonClass(generateAdapter = true)
data class CollectionValueResponse(
    @Json(name = "label") val label: String?,
    @Json(name = "value") val value: String?,
    @Json(name = "description") val description: String?,
)

@JsonClass(generateAdapter = true)
data class CollectionPublishDayResponse(
    @Json(name = "value") val value: Int?,
    @Json(name = "description") val description: String?,
)

@JsonClass(generateAdapter = true)
data class CollectionEpisodeResponse(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String?,
    @Json(name = "name_english") val nameEnglish: String?,
    @Json(name = "ordinal") val ordinal: Float?,
    @Json(name = "opening") val opening: CollectionSkipResponse?,
    @Json(name = "ending") val ending: CollectionSkipResponse?,
    @Json(name = "hls_480") val hls480: String?,
    @Json(name = "hls_720") val hls720: String?,
    @Json(name = "hls_1080") val hls1080: String?,
    @Json(name = "rutube_id") val rutubeId: String?,
    @Json(name = "updated_at") val updatedAt: String?,
)

@JsonClass(generateAdapter = true)
data class CollectionSkipResponse(
    @Json(name = "start") val start: Int?,
    @Json(name = "stop") val stop: Int?,
)

@JsonClass(generateAdapter = true)
data class V1GenreReferenceResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
)

@JsonClass(generateAdapter = true)
data class V1ScheduleItemResponse(
    @Json(name = "release") val release: CollectionReleaseResponse,
)

package ru.radiationx.data.entity.response.view

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ViewTimecodeResponse(
    @Json(name = "id") val id: Int?,
    @Json(name = "time") val time: Float?,
    @Json(name = "user_id") val userId: Int?,
    @Json(name = "is_watched") val isWatched: Boolean?,
    @Json(name = "updated_at") val updatedAt: String?,
    @Json(name = "release_episode_id") val releaseEpisodeId: String,
)

@JsonClass(generateAdapter = true)
data class ViewTimecodeUpdateRequest(
    @Json(name = "time") val time: Double,
    @Json(name = "is_watched") val isWatched: Boolean,
    @Json(name = "release_episode_id") val releaseEpisodeId: String,
)

@JsonClass(generateAdapter = true)
data class ViewTimecodeDeleteRequest(
    @Json(name = "release_episode_id") val releaseEpisodeId: String,
)

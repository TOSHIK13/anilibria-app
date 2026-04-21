package ru.radiationx.data.entity.response.favorite

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FavoriteUpdateRequest(
    @Json(name = "release_id") val releaseId: Int,
)

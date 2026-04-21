package ru.radiationx.data.entity.response.collection

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CollectionUpdateRequest(
    @Json(name = "release_id") val releaseId: Int,
    @Json(name = "type_of_collection") val type: String,
)

@JsonClass(generateAdapter = true)
data class CollectionDeleteRequest(
    @Json(name = "release_id") val releaseId: Int,
)

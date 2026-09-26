package ru.radiationx.data.entity.response.media

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import ru.radiationx.data.entity.response.collection.CollectionImageResponse

/** Элемент `GET /api/v1/media/videos` (голый массив, limit <= 50, без пагинации). */
@JsonClass(generateAdapter = true)
data class V1VideoResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "url") val url: String?,
    @Json(name = "title") val title: String?,
    @Json(name = "views") val views: Int?,
    @Json(name = "comments") val comments: Int?,
    @Json(name = "image") val image: CollectionImageResponse?,
    @Json(name = "video_id") val videoId: String?,
    @Json(name = "created_at") val createdAt: String?,
)

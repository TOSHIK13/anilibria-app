package ru.radiationx.data.entity.response.other

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class V1ProfileResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "nickname") val nickname: String?,
    @Json(name = "login") val login: String?,
    @Json(name = "avatar") val avatar: V1ProfileImageResponse?,
)

@JsonClass(generateAdapter = true)
data class V1ProfileImageResponse(
    @Json(name = "preview") val preview: String?,
    @Json(name = "thumbnail") val thumbnail: String?,
    @Json(name = "optimized") val optimized: V1ProfileImageResponse?,
)

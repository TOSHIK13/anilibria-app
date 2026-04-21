package ru.radiationx.data.entity.response.team

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class V1TeamResponse(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "description") val description: String?,
    @Json(name = "sort_order") val sortOrder: Int?,
)

@JsonClass(generateAdapter = true)
data class V1TeamRoleResponse(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "color") val color: String?,
    @Json(name = "sort_order") val sortOrder: Int?,
)

@JsonClass(generateAdapter = true)
data class V1TeamUserResponse(
    @Json(name = "id") val id: String,
    @Json(name = "nickname") val nickname: String,
    @Json(name = "is_intern") val isIntern: Boolean,
    @Json(name = "is_vacation") val isVacation: Boolean,
    @Json(name = "sort_order") val sortOrder: Int?,
    @Json(name = "team") val team: V1TeamResponse,
    @Json(name = "roles") val roles: List<V1TeamRoleResponse>,
)

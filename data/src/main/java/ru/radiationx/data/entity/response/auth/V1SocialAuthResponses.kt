package ru.radiationx.data.entity.response.auth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class V1SocialAuthLoginResponse(
    @Json(name = "url") val url: String,
    @Json(name = "state") val state: String,
)

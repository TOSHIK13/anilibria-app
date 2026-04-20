package ru.radiationx.data.entity.response.auth

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class V1OtpInfoResponse(
    @Json(name = "otp") val otp: V1OtpResponse,
    @Json(name = "remaining_time") val remainingTime: Double,
)

@JsonClass(generateAdapter = true)
data class V1OtpResponse(
    @Json(name = "code") val code: String,
)

@JsonClass(generateAdapter = true)
data class V1TokenResponse(
    @Json(name = "token") val token: String,
)

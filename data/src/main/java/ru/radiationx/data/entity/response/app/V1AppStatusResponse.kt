package ru.radiationx.data.entity.response.app

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** `GET /api/v1/app/status` (~170 байт): лёгкий health-check адреса. */
@JsonClass(generateAdapter = true)
data class V1AppStatusResponse(
    @Json(name = "is_alive") val isAlive: Boolean? = null,
)

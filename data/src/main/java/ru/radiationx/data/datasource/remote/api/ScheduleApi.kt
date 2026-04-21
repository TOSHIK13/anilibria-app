package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchListResponse
import ru.radiationx.data.entity.response.collection.V1ScheduleItemResponse
import javax.inject.Inject

class ScheduleApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    suspend fun getSchedule(): List<V1ScheduleItemResponse> {
        val args = mapOf<String, String>()
        return client
            .get("${apiConfig.animeBaseUrl}/api/v1/anime/schedule/week", args)
            .fetchListResponse(moshi)
    }

}

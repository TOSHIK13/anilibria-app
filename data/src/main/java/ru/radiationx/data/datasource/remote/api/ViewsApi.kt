package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.view.ViewHistoryPageResponse
import ru.radiationx.data.entity.response.view.ViewTimecodeDeleteRequest
import ru.radiationx.data.entity.response.view.ViewTimecodeResponse
import ru.radiationx.data.entity.response.view.ViewTimecodeUpdateRequest
import javax.inject.Inject

class ViewsApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    private val animeUrl: String
        get() = "${apiConfig.animeBaseUrl}/api/v1/anime"

    private val accountUrl: String
        get() = "${apiConfig.accountsBaseUrl}/api/v1/accounts/users/me/views/timecodes"

    private val historyUrl: String
        get() = "${apiConfig.accountsBaseUrl}/api/v1/accounts/users/me/views/history"

    suspend fun getReleaseTimecodes(releaseId: Int): List<ViewTimecodeResponse> {
        return client
            .get("$animeUrl/releases/$releaseId/episodes/timecodes", emptyMap())
            .fetchList()
    }

    suspend fun getEpisodeTimecode(releaseEpisodeId: String): ViewTimecodeResponse {
        return client
            .get("$animeUrl/releases/episodes/$releaseEpisodeId/timecode", emptyMap())
            .fetchResponse(moshi)
    }

    suspend fun getHistory(page: Int, limit: Int, include: String?): ViewHistoryPageResponse {
        val args = buildMap {
            put("page", page.toString())
            put("limit", limit.toString())
            include?.also { put("include", it) }
        }
        return client
            .get(historyUrl, args)
            .fetchResponse(moshi)
    }

    suspend fun updateTimecodes(items: List<ViewTimecodeUpdateRequest>) {
        client.postJson(accountUrl, toJson(items))
    }

    suspend fun deleteTimecodes(items: List<ViewTimecodeDeleteRequest>) {
        client.deleteJson(accountUrl, toJson(items))
    }

    private inline fun <reified T> toJson(value: List<T>): String {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return moshi.adapter<List<T>>(type).toJson(value)
    }

    private suspend inline fun <reified T> String.fetchList(): List<T> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return fetchResponse(moshi, type)
    }
}

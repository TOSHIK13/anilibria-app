package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.release.RandomReleaseResponse
import javax.inject.Inject

/* Created by radiationx on 31.10.17. */

class ReleaseApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi
) {

    private val animeUrl: String
        get() = "${apiConfig.animeBaseUrl}/api/v1/anime"

    suspend fun getRandomRelease(): RandomReleaseResponse {
        val args = mapOf(
            "limit" to "1",
        )
        return client
            .get("$animeUrl/releases/random", args)
            .fetchList<CollectionReleaseResponse>()
            .first()
            .let { RandomReleaseResponse(it.alias ?: it.id.toString()) }
    }

    suspend fun getRelease(releaseId: Int): CollectionReleaseResponse {
        return getRelease(releaseId.toString())
    }

    suspend fun getRelease(releaseCode: String): CollectionReleaseResponse {
        val args = mapOf<String, String>()
        return client
            .get("$animeUrl/releases/$releaseCode", args)
            .fetchResponse(moshi)
    }

    suspend fun getReleasesByIds(ids: List<Int>): List<CollectionReleaseResponse> {
        val args = mapOf(
            "ids" to ids.joinToString(","),
        )
        return client
            .get("$animeUrl/releases/list", args)
            .fetchResponse<CollectionReleasesResponse>(moshi)
            .data
    }

    suspend fun getFullReleasesByIds(ids: List<Int>): List<CollectionReleaseResponse> {
        return getReleasesByIds(ids)
    }

    suspend fun getReleases(page: Int): CollectionReleasesResponse {
        val args = mapOf(
            "page" to page.toString(),
            "limit" to "10",
            "f[sorting]" to "FRESH_AT_DESC",
        )
        return client
            .get("$animeUrl/catalog/releases", args)
            .fetchResponse(moshi)
    }

    suspend fun getRecommendedReleases(releaseId: Int? = null): List<CollectionReleaseResponse> {
        val args = buildMap {
            put("limit", "10")
            releaseId?.also { put("release_id", it.toString()) }
        }
        return client
            .get("$animeUrl/releases/recommended", args)
            .fetchList()
    }

    private suspend inline fun <reified T> String.fetchList(): List<T> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return fetchResponse(moshi, type)
    }
}

        

package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.collection.V1FranchiseResponse
import ru.radiationx.data.entity.response.release.RandomReleaseResponse
import javax.inject.Inject

/* Created by radiationx on 31.10.17. */

class ReleaseApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi
) {

    companion object {
        /** Поля ленты + серии без ссылок на видео (превью `optimized.preview`, как в CollectionMapper). */
        const val SHORT_RELEASE_FIELDS = FeedApi.RELEASE_FIELDS +
                ",episodes.id,episodes.ordinal,episodes.name,episodes.preview.optimized.preview," +
                "episodes.duration"
    }

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

    /**
     * Краткие релизы для карточек: только поля, которые читает CollectionMapper
     * (как в ленте, [FeedApi.RELEASE_FIELDS]) плюс номер/превью/длительность серий
     * для «Продолжить просмотр» — ответ в разы легче полного.
     */
    suspend fun getShortReleasesByIds(ids: List<Int>): List<CollectionReleaseResponse> {
        val args = mapOf(
            "ids" to ids.joinToString(","),
            "include" to SHORT_RELEASE_FIELDS,
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
            // Сервер принимает не больше 14.
            put("limit", "14")
            releaseId?.also { put("release_id", it.toString()) }
        }
        return client
            .get("$animeUrl/releases/recommended", args)
            .fetchList()
    }

    /** Франшизы релиза: голый массив, части в `franchise_releases[]` с кратким `release`. */
    suspend fun getFranchisesByRelease(releaseId: Int): List<V1FranchiseResponse> {
        val args = mapOf<String, String>()
        return client
            .get("$animeUrl/franchises/release/$releaseId", args)
            .fetchList()
    }

    private suspend inline fun <reified T> String.fetchList(): List<T> {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return fetchResponse(moshi, type)
    }
}

        

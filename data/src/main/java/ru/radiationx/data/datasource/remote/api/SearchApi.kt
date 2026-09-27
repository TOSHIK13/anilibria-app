package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchListResponse
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.collection.V1GenreReferenceResponse
import javax.inject.Inject

class SearchApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    private val animeUrl: String
        get() = "${apiConfig.animeBaseUrl}/api/v1/anime"

    suspend fun getGenres(): List<V1GenreReferenceResponse> {
        val args = mapOf<String, String>()
        return client
            .get("$animeUrl/catalog/references/genres", args)
            .fetchListResponse(moshi)
    }

    suspend fun getYears(): List<Int> {
        val args = mapOf<String, String>()
        return client
            .get("$animeUrl/catalog/references/years", args)
            .fetchListResponse(moshi)
    }

    suspend fun fastSearch(name: String): List<CollectionReleaseResponse> {
        val args = mapOf(
            "query" to name,
        )
        return client
            .get("${apiConfig.animeBaseUrl}/api/v1/app/search/releases", args)
            .fetchListResponse(moshi)
    }

    suspend fun searchReleases(
        genre: String,
        year: String,
        season: String,
        sort: String,
        complete: String,
        page: Int,
        limit: Int = DEFAULT_LIMIT,
    ): CollectionReleasesResponse {
        val args = buildMap {
            put("page", page.toString())
            put("limit", limit.toString())
            put("f[sorting]", sort)
            genre.takeIf { it.isNotBlank() }?.also { put("f[genres]", it) }
            // V1 фильтрует годы только диапазоном: список `f[years]=1996,2001` сервер игнорирует.
            val years = year.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (years.isNotEmpty()) {
                put("f[years][from_year]", years.min().toString())
                put("f[years][to_year]", years.max().toString())
            }
            season.takeIf { it.isNotBlank() }?.also { put("f[seasons]", it) }
            if (complete == "true") {
                put("f[publish_statuses]", "IS_NOT_ONGOING")
            }
        }
        return client
            .get("$animeUrl/catalog/releases", args)
            .fetchResponse(moshi)
    }

    companion object {
        const val DEFAULT_LIMIT = 10
    }
}

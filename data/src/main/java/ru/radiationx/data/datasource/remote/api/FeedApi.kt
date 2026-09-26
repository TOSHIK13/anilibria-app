package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import javax.inject.Inject

/**
 * Лента на V1: релизы из `releases/latest` (первая страница) и
 * `catalog/releases?f[sorting]=FRESH_AT_DESC` (дальше) — порядок у них совпадает.
 * Видео берутся из [YoutubeApi.getVideos].
 */
class FeedApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi
) {

    companion object {
        /** Только поля, которые читает CollectionMapper: ответ в 2–8 раз легче полного. */
        const val RELEASE_FIELDS = "id,alias,name,poster,genres.name,type,year,season," +
                "publish_day,description,notification,episodes_total,is_ongoing,fresh_at," +
                "updated_at,external_player,is_blocked_by_geo,is_blocked_by_copyrights," +
                "added_in_users_favorites"
        const val MAX_LATEST_LIMIT = 50
    }

    private val animeUrl: String
        get() = "${apiConfig.animeBaseUrl}/api/v1/anime"

    /** Голый массив, limit 1..50, `page` сервер игнорирует. */
    suspend fun getLatestReleases(limit: Int): List<CollectionReleaseResponse> {
        val args = mapOf(
            "limit" to limit.coerceIn(1, MAX_LATEST_LIMIT).toString(),
            "include" to RELEASE_FIELDS,
        )
        val type = Types.newParameterizedType(List::class.java, CollectionReleaseResponse::class.java)
        return client
            .get("$animeUrl/releases/latest", args)
            .fetchResponse(moshi, type)
    }

    suspend fun getFreshReleases(page: Int, limit: Int): CollectionReleasesResponse {
        val args = mapOf(
            "page" to page.toString(),
            "limit" to limit.toString(),
            "f[sorting]" to "FRESH_AT_DESC",
            "include" to RELEASE_FIELDS,
        )
        return client
            .get("$animeUrl/catalog/releases", args)
            .fetchResponse(moshi)
    }
}

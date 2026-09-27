package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.favorite.FavoriteUpdateRequest
import ru.radiationx.data.entity.response.release.FavoriteInfoResponse
import javax.inject.Inject

class FavoriteApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi
) {

    private val favoritesUrl: String
        get() = "${apiConfig.accountsBaseUrl}/api/v1/accounts/users/me/favorites"

    /**
     * [sorting] — значение `f[sorting]` (enum `CREATED_AT_DESC|..|FRESH_AT_DESC|..`),
     * без него сервер отдаёт свой порядок (по дате добавления).
     */
    suspend fun getFavorites(page: Int, sorting: String? = null): CollectionReleasesResponse {
        val args = buildMap {
            put("page", page.toString())
            put("limit", "10")
            if (sorting != null) put("f[sorting]", sorting)
        }
        return client
            .get("$favoritesUrl/releases", args)
            .fetchResponse(moshi)
    }

    suspend fun addFavorite(releaseId: Int): FavoriteInfoResponse {
        val body = toJson(listOf(FavoriteUpdateRequest(releaseId)))
        client.postJson(favoritesUrl, body)
        return FavoriteInfoResponse(0, true)
    }

    suspend fun deleteFavorite(releaseId: Int): FavoriteInfoResponse {
        val body = toJson(listOf(FavoriteUpdateRequest(releaseId)))
        client.deleteJson(favoritesUrl, body)
        return FavoriteInfoResponse(0, false)
    }

    private inline fun <reified T> toJson(value: List<T>): String {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return moshi.adapter<List<T>>(type).toJson(value)
    }

}

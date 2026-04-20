package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import javax.inject.Inject

class CollectionApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    suspend fun getCollectionReleases(
        type: CollectionType,
        page: Int,
        limit: Int,
    ): CollectionReleasesResponse {
        val args = mapOf(
            "type_of_collection" to type.value,
            "page" to page.toString(),
            "limit" to limit.toString(),
        )
        return client
            .get("${apiConfig.baseUrl}/api/v1/accounts/users/me/collections/releases", args)
            .fetchResponse(moshi)
    }
}

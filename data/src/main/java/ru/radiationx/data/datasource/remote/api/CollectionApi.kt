package ru.radiationx.data.datasource.remote.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import ru.radiationx.data.ApiClient
import ru.radiationx.data.datasource.remote.IClient
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.fetchResponse
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.response.collection.CollectionDeleteRequest
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.collection.CollectionUpdateRequest
import javax.inject.Inject

class CollectionApi @Inject constructor(
    @ApiClient private val client: IClient,
    private val apiConfig: ApiConfig,
    private val moshi: Moshi,
) {

    private val collectionsUrl: String
        get() = "${apiConfig.accountBaseUrl}/api/v1/accounts/users/me/collections"

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
            .get("$collectionsUrl/releases", args)
            .fetchResponse(moshi)
    }

    suspend fun getCollectionIds(): List<List<Any>> {
        val itemType = Types.newParameterizedType(List::class.java, Any::class.java)
        val responseType = Types.newParameterizedType(List::class.java, itemType)
        return client
            .get("$collectionsUrl/ids", emptyMap())
            .fetchResponse(moshi, responseType)
    }

    suspend fun addToCollection(releaseId: Int, type: CollectionType) {
        val body = toJson(listOf(CollectionUpdateRequest(releaseId, type.value)))
        client.postJson(collectionsUrl, body)
    }

    suspend fun deleteFromCollections(releaseId: Int) {
        val body = toJson(listOf(CollectionDeleteRequest(releaseId)))
        client.deleteJson(collectionsUrl, body)
    }

    private inline fun <reified T> toJson(value: List<T>): String {
        val type = Types.newParameterizedType(List::class.java, T::class.java)
        return moshi.adapter<List<T>>(type).toJson(value)
    }
}

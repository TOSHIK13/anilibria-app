package ru.radiationx.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.CollectionApi
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.collection.CollectionType
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.interactors.ReleaseUpdateMiddleware
import ru.radiationx.data.system.ApiUtils
import javax.inject.Inject

class CollectionRepository @Inject constructor(
    private val collectionApi: CollectionApi,
    private val updateMiddleware: ReleaseUpdateMiddleware,
    private val apiUtils: ApiUtils,
    private val apiConfig: ApiConfig,
) {

    suspend fun getReleases(
        type: CollectionType,
        page: Int,
        limit: Int = 10,
    ): Paginated<Release> = withContext(Dispatchers.IO) {
        collectionApi
            .getCollectionReleases(type, page, limit)
            .toDomain(apiUtils, apiConfig)
            .also { updateMiddleware.handle(it.data) }
    }
}

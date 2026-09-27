package ru.radiationx.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.datasource.remote.api.FavoriteApi
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.release.FavoriteInfo
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.mapper.toDomain
import ru.radiationx.data.interactors.ReleaseUpdateMiddleware
import ru.radiationx.data.system.ApiUtils
import javax.inject.Inject

class FavoriteRepository @Inject constructor(
    private val favoriteApi: FavoriteApi,
    private val updateMiddleware: ReleaseUpdateMiddleware,
    private val apiUtils: ApiUtils,
    private val apiConfig: ApiConfig
) {

    companion object {
        /** `f[sorting]`: сначала релизы с самым свежим обновлением (fresh_at = [Release.torrentUpdate]). */
        const val SORTING_FRESH_AT_DESC = "FRESH_AT_DESC"
    }

    suspend fun getFavorites(
        page: Int,
        sorting: String? = null,
    ): Paginated<Release> = withContext(Dispatchers.IO) {
        favoriteApi
            .getFavorites(page, sorting)
            .toDomain(apiUtils, apiConfig, favoriteAdded = true)
            .also { updateMiddleware.handle(it.data) }
    }

    /**
     * V1 add/delete не возвращают счётчик, поэтому [current] нужен,
     * чтобы не обнулять количество добавивших в избранное.
     */
    suspend fun deleteFavorite(
        releaseId: ReleaseId,
        current: FavoriteInfo? = null,
    ): FavoriteInfo = withContext(Dispatchers.IO) {
        favoriteApi
            .deleteFavorite(releaseId.id)
            .toDomain()
            .withRatingFrom(current)
    }

    suspend fun addFavorite(
        releaseId: ReleaseId,
        current: FavoriteInfo? = null,
    ): FavoriteInfo = withContext(Dispatchers.IO) {
        favoriteApi
            .addFavorite(releaseId.id)
            .toDomain()
            .withRatingFrom(current)
    }

    private fun FavoriteInfo.withRatingFrom(current: FavoriteInfo?): FavoriteInfo {
        if (current == null) return this
        val delta = when {
            isAdded == current.isAdded -> 0
            isAdded -> 1
            else -> -1
        }
        return copy(rating = (current.rating + delta).coerceAtLeast(0))
    }
}

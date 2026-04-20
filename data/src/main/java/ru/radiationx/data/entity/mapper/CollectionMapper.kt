package ru.radiationx.data.entity.mapper

import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.release.BlockedInfo
import ru.radiationx.data.entity.domain.release.FavoriteInfo
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.types.ReleaseCode
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.response.collection.CollectionImageResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.system.ApiUtils

fun CollectionReleasesResponse.toDomain(
    apiUtils: ApiUtils,
    apiConfig: ApiConfig,
): Paginated<Release> = Paginated(
    data = data.map { it.toDomain(apiUtils, apiConfig) },
    page = meta?.pagination?.currentPage,
    allPages = meta?.pagination?.totalPages,
    perPage = meta?.pagination?.perPage,
    allItems = meta?.pagination?.total,
)

private fun CollectionReleaseResponse.toDomain(
    apiUtils: ApiUtils,
    apiConfig: ApiConfig,
): Release {
    val names = listOfNotNull(
        name?.main,
        name?.english,
        name?.alternative,
    ).map { apiUtils.escapeHtml(it).toString() }
    val releaseCode = alias ?: id.toString()
    return Release(
        id = ReleaseId(id),
        code = ReleaseCode(releaseCode),
        names = names,
        series = episodesTotal?.toString(),
        poster = poster?.toPosterUrl(apiConfig),
        torrentUpdate = 0,
        status = null,
        statusCode = if (isOngoing == true) {
            Release.STATUS_CODE_PROGRESS
        } else {
            Release.STATUS_CODE_COMPLETE
        },
        types = listOfNotNull(type?.description ?: type?.value),
        genres = genres?.mapNotNull { it.name }.orEmpty(),
        voices = emptyList(),
        members = null,
        year = year?.toString(),
        season = season?.description ?: season?.value,
        days = emptyList(),
        description = description?.trim(),
        announce = notification?.trim(),
        favoriteInfo = FavoriteInfo(0, false),
        link = releaseCode.takeIf { it.isNotEmpty() }?.let { "${apiConfig.siteUrl}/release/$it.html" },
        franchises = emptyList(),
        showDonateDialog = false,
        blockedInfo = BlockedInfo(false, null),
        moonwalkLink = null,
        episodes = emptyList(),
        sourceEpisodes = emptyList(),
        externalPlaylists = emptyList(),
        rutubePlaylist = emptyList(),
        torrents = emptyList(),
    )
}

private fun CollectionImageResponse.toPosterUrl(apiConfig: ApiConfig): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.thumbnail
        ?: thumbnail
    return path?.let {
        if (it.startsWith("http")) it else it.appendBaseUrl(apiConfig.baseImagesUrl)
    }
}

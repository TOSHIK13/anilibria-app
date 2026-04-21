package ru.radiationx.data.entity.mapper

import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.release.BlockedInfo
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.FavoriteInfo
import ru.radiationx.data.entity.domain.release.PlayerSkips
import ru.radiationx.data.entity.domain.release.QualityInfo
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.release.RutubeEpisode
import ru.radiationx.data.entity.domain.release.SourceEpisode
import ru.radiationx.data.entity.domain.types.ReleaseCode
import ru.radiationx.data.entity.domain.types.ReleaseId
import ru.radiationx.data.entity.domain.types.EpisodeId
import ru.radiationx.data.entity.response.collection.CollectionImageResponse
import ru.radiationx.data.entity.response.collection.CollectionEpisodeResponse
import ru.radiationx.data.entity.response.collection.CollectionReleaseResponse
import ru.radiationx.data.entity.response.collection.CollectionReleasesResponse
import ru.radiationx.data.entity.response.collection.CollectionSkipResponse
import ru.radiationx.data.system.ApiUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun CollectionReleasesResponse.toDomain(
    apiUtils: ApiUtils,
    apiConfig: ApiConfig,
    favoriteAdded: Boolean = false,
): Paginated<Release> = Paginated(
    data = data.map { it.toDomain(apiUtils, apiConfig, favoriteAdded) },
    page = meta?.pagination?.currentPage,
    allPages = meta?.pagination?.totalPages,
    perPage = meta?.pagination?.perPage,
    allItems = meta?.pagination?.total,
)

fun CollectionReleaseResponse.toDomain(
    apiUtils: ApiUtils,
    apiConfig: ApiConfig,
    favoriteAdded: Boolean = false,
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
        days = listOfNotNull(publishDay?.value?.toString()),
        description = description?.trim(),
        announce = notification?.trim(),
        favoriteInfo = FavoriteInfo(0, favoriteAdded),
        link = releaseCode.takeIf { it.isNotEmpty() }?.let { "${apiConfig.siteUrl}/release/$it.html" },
        franchises = emptyList(),
        showDonateDialog = false,
        blockedInfo = BlockedInfo(
            isBlockedByGeo == true || isBlockedByCopyrights == true,
            null
        ),
        moonwalkLink = externalPlayer,
        episodes = episodes?.map { it.toOnlineDomain(ReleaseId(id)) }.orEmpty(),
        sourceEpisodes = episodes?.map { it.toSourceDomain(ReleaseId(id)) }.orEmpty(),
        externalPlaylists = emptyList(),
        rutubePlaylist = episodes?.mapNotNull { it.toRutubeDomain(ReleaseId(id)) }.orEmpty(),
        torrents = emptyList(),
    )
}

private fun CollectionImageResponse.toPosterUrl(apiConfig: ApiConfig): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.src
        ?: src
        ?: optimized?.thumbnail
        ?: thumbnail
    return path?.let {
        if (it.startsWith("http")) it else it.appendBaseUrl(apiConfig.baseImagesUrl)
    }
}

fun CollectionReleaseResponse.toSuggestionDomain(
    apiUtils: ApiUtils,
    apiConfig: ApiConfig,
) = ru.radiationx.data.entity.domain.search.SuggestionItem(
    id = ReleaseId(id),
    code = ReleaseCode(alias ?: id.toString()),
    names = listOfNotNull(
        name?.main,
        name?.english,
        name?.alternative,
    ).map { apiUtils.escapeHtml(it).toString() },
    poster = poster?.toPosterUrl(apiConfig)
)

private fun CollectionEpisodeResponse.toOnlineDomain(releaseId: ReleaseId): Episode = Episode(
    id = EpisodeId((ordinal ?: 0f).toString().trimEnd('0').trimEnd('.'), releaseId),
    serverId = id,
    title = createCombinedTitle(),
    qualityInfo = QualityInfo(
        urlSd = hls480,
        urlHd = hls720,
        urlFullHd = hls1080,
    ),
    updatedAt = updatedAt?.isoToDate(),
    skips = PlayerSkips(
        opening = opening?.toDomain(),
        ending = ending?.toDomain(),
    )
)

private fun CollectionEpisodeResponse.toSourceDomain(releaseId: ReleaseId): SourceEpisode =
    SourceEpisode(
        id = EpisodeId((ordinal ?: 0f).toString().trimEnd('0').trimEnd('.'), releaseId),
        serverId = id,
        title = createCombinedTitle(),
        updatedAt = updatedAt?.isoToDate(),
        qualityInfo = QualityInfo(
            urlSd = hls480,
            urlHd = hls720,
            urlFullHd = hls1080,
        )
    )

private fun CollectionEpisodeResponse.toRutubeDomain(releaseId: ReleaseId): RutubeEpisode? {
    val rutubeId = rutubeId ?: return null
    return RutubeEpisode(
        id = EpisodeId((ordinal ?: 0f).toString().trimEnd('0').trimEnd('.'), releaseId),
        serverId = id,
        title = createCombinedTitle(),
        updatedAt = updatedAt?.isoToDate(),
        rutubeId = rutubeId,
        url = "https://rutube.ru/play/embed/$rutubeId"
    )
}

private fun CollectionEpisodeResponse.createCombinedTitle(): String? {
    return listOfNotNull(ordinal?.let { "Серия ${it.toString().trimEnd('0').trimEnd('.')}" }, name)
        .joinToString(" • ")
        .takeIf { it.isNotBlank() }
}

private fun CollectionSkipResponse.toDomain(): PlayerSkips.Skip? {
    val start = start?.secToMillis() ?: return null
    val end = stop?.secToMillis() ?: return null
    return PlayerSkips.Skip(start, end)
}

private fun String.isoToDate() = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(this)
}.getOrNull()

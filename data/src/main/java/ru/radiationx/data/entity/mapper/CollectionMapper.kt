package ru.radiationx.data.entity.mapper

import ru.radiationx.data.datasource.remote.IApiUtils
import ru.radiationx.data.datasource.remote.address.ApiConfig
import ru.radiationx.data.entity.domain.Paginated
import ru.radiationx.data.entity.domain.release.BlockedInfo
import ru.radiationx.data.entity.domain.release.Episode
import ru.radiationx.data.entity.domain.release.FavoriteInfo
import ru.radiationx.data.entity.domain.release.PlayerSkips
import ru.radiationx.data.entity.domain.release.QualityInfo
import ru.radiationx.data.entity.domain.release.Release
import ru.radiationx.data.entity.domain.release.ReleaseCollectionStats
import ru.radiationx.data.entity.domain.release.ReleaseFranchise
import ru.radiationx.data.entity.domain.release.ReleaseFranchisePart
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
import ru.radiationx.data.entity.response.collection.V1FranchiseResponse
import ru.radiationx.data.system.ApiUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun CollectionReleasesResponse.toDomain(
    apiUtils: IApiUtils,
    apiConfig: ApiConfig,
    favoriteAdded: Boolean = false,
): Paginated<Release> = toDomain(apiUtils, apiConfig.baseImagesUrl, apiConfig.siteUrl, favoriteAdded)

fun CollectionReleasesResponse.toDomain(
    apiUtils: IApiUtils,
    imagesBaseUrl: String,
    siteUrl: String,
    favoriteAdded: Boolean = false,
): Paginated<Release> = Paginated(
    data = data.map { it.toDomain(apiUtils, imagesBaseUrl, siteUrl, favoriteAdded) },
    page = meta?.pagination?.currentPage,
    allPages = meta?.pagination?.totalPages,
    perPage = meta?.pagination?.perPage,
    allItems = meta?.pagination?.total,
)

fun CollectionReleaseResponse.toDomain(
    apiUtils: IApiUtils,
    apiConfig: ApiConfig,
    favoriteAdded: Boolean = false,
): Release = toDomain(
    apiUtils = apiUtils,
    imagesBaseUrl = apiConfig.baseImagesUrl,
    siteUrl = apiConfig.siteUrl,
    favoriteAdded = favoriteAdded,
)

fun CollectionReleaseResponse.toDomain(
    apiUtils: IApiUtils,
    imagesBaseUrl: String,
    siteUrl: String,
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
        poster = poster?.toPosterUrl(imagesBaseUrl),
        // fresh_at == legacy release.last (unix seconds): даты обновления и бейджи новых серий
        torrentUpdate = (freshAt ?: updatedAt)?.isoToUnixSeconds() ?: 0,
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
        favoriteInfo = FavoriteInfo(addedInUsersFavorites ?: 0, favoriteAdded),
        link = releaseCode.takeIf { it.isNotEmpty() }?.let { "$siteUrl/release/$it.html" },
        franchises = emptyList(),
        showDonateDialog = false,
        blockedInfo = BlockedInfo(
            isBlockedByGeo == true || isBlockedByCopyrights == true,
            null
        ),
        moonwalkLink = externalPlayer,
        episodes = episodes?.map { it.toOnlineDomain(ReleaseId(id), imagesBaseUrl) }.orEmpty(),
        sourceEpisodes = episodes?.map { it.toSourceDomain(ReleaseId(id)) }.orEmpty(),
        externalPlaylists = emptyList(),
        rutubePlaylist = episodes?.mapNotNull { it.toRutubeDomain(ReleaseId(id)) }.orEmpty(),
        torrents = emptyList(),
        // полный релиз отдаёт episodes, лента (releases/latest) — только latest_episode
        episodesAvailable = episodes?.takeIf { it.isNotEmpty() }?.size
            ?: latestEpisode?.ordinal?.toInt()?.takeIf { it > 0 },
        ageRating = ageRating?.label?.takeIf { it.isNotBlank() },
        averageEpisodeDurationMin = averageDurationOfEpisode?.takeIf { it > 0 },
        shikimoriRating = shikimori?.rating?.takeIf { it > 0.0 },
        shikimoriVotes = shikimori?.votes?.takeIf { it > 0 },
        malRating = mal?.rating?.takeIf { it > 0.0 },
        shikimoriId = shikimori?.id?.takeIf { it > 0 },
        malVotes = mal?.votes?.takeIf { it > 0 },
        malId = (mal?.id ?: shikimori?.id)?.takeIf { it > 0 },
        ownRatingAverage = rating?.average?.takeIf { it > 0.0 },
        ownRatingVotes = rating?.votes,
        collectionStats = ReleaseCollectionStats(
            favorites = addedInUsersFavorites,
            watching = addedInWatchingCollection,
            planned = addedInPlannedCollection,
            watched = addedInWatchedCollection,
            postponed = addedInPostponedCollection,
            abandoned = addedInAbandonedCollection,
        ).takeUnless { it.isEmpty },
        backgroundCover = backgroundCovers
            ?.firstNotNullOfOrNull { it.toBackgroundUrl(imagesBaseUrl) },
        nameEnglish = name?.english
            ?.let { apiUtils.escapeHtml(it).toString().trim() }
            ?.takeIf { it.isNotEmpty() },
    )
}

fun V1FranchiseResponse.toDomain(
    apiUtils: IApiUtils,
    imagesBaseUrl: String,
    siteUrl: String,
): ReleaseFranchise = ReleaseFranchise(
    id = id,
    name = name.orEmpty(),
    nameEnglish = nameEnglish,
    image = image?.toPosterUrl(imagesBaseUrl),
    firstYear = firstYear,
    lastYear = lastYear,
    totalReleases = totalReleases,
    totalEpisodes = totalEpisodes,
    parts = franchiseReleases
        .orEmpty()
        .mapIndexedNotNull { index, part ->
            val release = part.release ?: return@mapIndexedNotNull null
            ReleaseFranchisePart(
                sortOrder = part.sortOrder ?: (index + 1),
                release = release.toDomain(apiUtils, imagesBaseUrl, siteUrl),
            )
        }
        .sortedBy { it.sortOrder },
)

/** Фон 1920x1080: у `background_covers` `preview` — полный кадр, `thumbnail` — 32x18. */
internal fun CollectionImageResponse.toBackgroundUrl(imagesBaseUrl: String): String? {
    val path = preview
        ?: src
        ?: optimized?.preview
        ?: optimized?.src
    return path?.takeIf { it.isNotBlank() }?.toImageUrl(imagesBaseUrl)
}

/** Превью серии: `optimized.preview` (webp 720x405) → fallback на исходники. */
internal fun CollectionImageResponse.toEpisodePreviewUrl(imagesBaseUrl: String): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.src
        ?: src
    return path?.takeIf { it.isNotBlank() }?.toImageUrl(imagesBaseUrl)
}

/**
 * Один канонический вариант постера для всех v1-ответов (лента, каталог, детали),
 * чтобы у Coil был один и тот же ключ кэша и постер в деталях не перезагружался.
 */
internal fun CollectionImageResponse.toPosterUrl(imagesBaseUrl: String): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.src
        ?: src
        ?: optimized?.thumbnail
        ?: thumbnail
    return path?.takeIf { it.isNotBlank() }?.toImageUrl(imagesBaseUrl)
}

/** Относительный путь v1 (`/storage/...`) + база картинок без двойного слэша. */
internal fun String.toImageUrl(imagesBaseUrl: String): String {
    if (startsWith("http://") || startsWith("https://")) return this
    if (imagesBaseUrl.isBlank()) return this
    return "${imagesBaseUrl.trimEnd('/')}/${trimStart('/')}"
}

internal fun String.isoToUnixSeconds(): Int? = isoToDate()?.let { (it.time / 1000L).toInt() }

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
    poster = poster?.toPosterUrl(apiConfig.baseImagesUrl)
)

private fun CollectionEpisodeResponse.toOnlineDomain(
    releaseId: ReleaseId,
    imagesBaseUrl: String,
): Episode = Episode(
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
    ),
    previewUrl = preview?.toEpisodePreviewUrl(imagesBaseUrl),
    durationSec = duration?.takeIf { it > 0 },
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

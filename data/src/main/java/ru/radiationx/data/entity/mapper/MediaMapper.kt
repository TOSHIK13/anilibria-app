package ru.radiationx.data.entity.mapper

import ru.radiationx.data.datasource.remote.IApiUtils
import ru.radiationx.data.entity.domain.types.YoutubeId
import ru.radiationx.data.entity.domain.youtube.YoutubeItem
import ru.radiationx.data.entity.response.collection.CollectionImageResponse
import ru.radiationx.data.entity.response.media.V1VideoResponse

fun V1VideoResponse.toDomain(
    apiUtils: IApiUtils,
    imagesBaseUrl: String,
): YoutubeItem = YoutubeItem(
    id = YoutubeId(id),
    title = apiUtils.escapeHtml(title),
    image = image?.toVideoImageUrl(imagesBaseUrl),
    vid = videoId,
    views = views ?: 0,
    comments = comments ?: 0,
    // created_at == legacy youtube timestamp; updated_at у всех одинаковый, не использовать
    timestamp = createdAt?.isoToUnixSeconds() ?: 0,
    url = url?.takeIf { it.isNotBlank() },
)

private fun CollectionImageResponse.toVideoImageUrl(imagesBaseUrl: String): String? {
    val path = optimized?.preview
        ?: preview
        ?: optimized?.thumbnail
        ?: thumbnail
        ?: optimized?.src
        ?: src
    return path?.takeIf { it.isNotBlank() }?.toImageUrl(imagesBaseUrl)
}
